<#
.SYNOPSIS
    Starts the VDCS databases and services in dependency order.

.DESCRIPTION
    Runs the services as host JVMs and waits for each tier to become
    healthy. Existing listeners are left running. Logs are written under
    logs\.

.PARAMETER SkipDb
    Leaves the Docker containers unchanged.

.PARAMETER SkipBuild
    Skips the Maven build.

.EXAMPLE
    .\scripts\run-all.ps1
    .\scripts\run-all.ps1 -SkipDb -SkipBuild
#>
param(
    [switch] $SkipDb,
    [switch] $SkipBuild,
    [string] $JavaHome = $(
        # A launcher may still carry JAVA_HOME from before the user changed it.
        $savedJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
        if ([string]::IsNullOrWhiteSpace($savedJavaHome)) { $env:JAVA_HOME } else { $savedJavaHome }
    )
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}
Write-Host ("Java home: {0}" -f $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "using Java from PATH" }))
New-Item -ItemType Directory -Path "$root\logs" -Force | Out-Null

# helpers

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Http([string]$Url, [int]$Secs = 90) {
    $deadline = (Get-Date).AddSeconds($Secs)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop | Out-Null
            return $true
        } catch [System.Net.WebException] {
            if ($_.Exception.Response) { return $true }   # any HTTP response = up
        } catch {}
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-Service([string]$Name, [string]$Jar, [int]$Port, [string]$HealthUrl, [int]$Secs = 90) {
    if (Test-Port $Port) {
        Write-Host ("  {0,-14} already listening on {1} - skipped" -f $Name, $Port) -ForegroundColor Yellow
        return $true
    }
    if (-not (Test-Path $Jar)) {
        Write-Host ("  {0,-14} JAR not found: {1}" -f $Name, $Jar) -ForegroundColor Red
        return $false
    }
    Start-Process -FilePath "java" -ArgumentList "-jar", $Jar `
        -RedirectStandardOutput "$root\logs\$Name.out" `
        -RedirectStandardError  "$root\logs\$Name.err" `
        -WindowStyle Hidden | Out-Null
    if (Wait-Http $HealthUrl $Secs) {
        Write-Host ("  {0,-14} UP   ({1})" -f $Name, $Port) -ForegroundColor Green
        return $true
    } else {
        Write-Host ("  {0,-14} FAILED to start - see logs\{1}.out" -f $Name, $Name) -ForegroundColor Red
        return $false
    }
}

# databases

if (-not $SkipDb) {
    Write-Host "`n[0] Starting database containers..." -ForegroundColor Cyan
    docker compose -f "$root\docker-compose.yml" up -d | Out-Null
    $containers = @("student-registry", "academic-records", "student-activities")
    foreach ($c in $containers) {
        $deadline = (Get-Date).AddSeconds(90)
        $healthy = $false
        while ((Get-Date) -lt $deadline) {
            $status = (docker inspect --format "{{.State.Health.Status}}" $c 2>$null)
            if ($status -eq "healthy") { $healthy = $true; break }
            Start-Sleep -Seconds 2
        }
        if ($healthy) { Write-Host "  $c healthy" -ForegroundColor Green }
        else { Write-Host "  $c NOT healthy - aborting" -ForegroundColor Red; exit 1 }
    }
    # The Fuseki image (research-publications) carries no Docker
    # healthcheck; probe its SPARQL endpoint from the host instead
    # (Wait-Http counts any HTTP response as up).
    if (Wait-Http "http://localhost:5435/publications/sparql?query=ASK%7B%7D" 60) {
        Write-Host "  research-publications healthy" -ForegroundColor Green
    } else {
        Write-Host "  research-publications NOT responding - aborting" -ForegroundColor Red; exit 1
    }
} else {
    Write-Host "`n[0] -SkipDb: assuming DB containers are up" -ForegroundColor Cyan
}

# optional build

if (-not $SkipBuild) {
    Write-Host "`n[*] Building (mvn -q clean install -DskipTests)..." -ForegroundColor Cyan
    mvn -q clean install -DskipTests
    if ($LASTEXITCODE -ne 0) { Write-Host "Build failed" -ForegroundColor Red; exit 1 }
    Write-Host "  build OK" -ForegroundColor Green
}

# service tiers

$SA = "source-access"
$MONGO = "$SA\source-access-mongo"

Write-Host "`n[1] Tier 1 - secret store, IAM, metadata" -ForegroundColor Cyan
$ok  = Start-Service "secret"   "secret-store-service\target\vdcs-secret-store-service-1.0-SNAPSHOT.jar"          8088 "http://localhost:8088/secrets/health"
$ok  = (Start-Service "iam"      "iam-service\target\vdcs-iam-service-1.0-SNAPSHOT.jar"                            8087 "http://localhost:8087/iam/health") -and $ok
$ok  = (Start-Service "metadata" "metadata-service\target\vdcs-metadata-service-1.0-SNAPSHOT.jar"                 8082 "http://localhost:8082/metadata") -and $ok
if (-not $ok) { Write-Host "Tier 1 incomplete - aborting" -ForegroundColor Red; exit 1 }

Write-Host "`n[2] Tier 2 - source access + canonical adapter" -ForegroundColor Cyan
$ok  = Start-Service "pg-sac"    "$SA\source-access-postgres-service\target\vdcs-source-access-postgres-service-1.0-SNAPSHOT.jar" 8083 "http://localhost:8083/sac/health"
$ok  = (Start-Service "mysql-sac" "$SA\source-access-mysql-service\target\vdcs-source-access-mysql-service-1.0-SNAPSHOT.jar"      8084 "http://localhost:8084/sac/health") -and $ok
$ok  = (Start-Service "mongo-csa" "$MONGO\canonical-adapter-service\target\vdcs-source-access-mongo-canonical-adapter-service-1.0-SNAPSHOT.jar" 8085 "http://localhost:8085/csa/health") -and $ok
$ok  = (Start-Service "mongo-sac" "$MONGO\service\target\vdcs-source-access-mongo-service-1.0-SNAPSHOT.jar"        8086 "http://localhost:8086/sac/health") -and $ok
# Research publications (RDF/Fuseki): no adapter -- the source is
# natively RDF, so the SAC only hands the mediator the SPARQL endpoint.
$ok  = (Start-Service "pubs-sac"  "$SA\source-access-publications-service\target\vdcs-source-access-publications-service-1.0-SNAPSHOT.jar"                 8091 "http://localhost:8091/sac/health") -and $ok
if (-not $ok) { Write-Host "Tier 2 incomplete - aborting" -ForegroundColor Red; exit 1 }

Write-Host "`n[3] Tier 3 - mediator + write orchestrator" -ForegroundColor Cyan
$ok  = Start-Service "mediator"     "semantic-mediator\target\vdcs-semantic-mediator-1.0-SNAPSHOT.jar" 8081 "http://localhost:8081/sparql?query=SELECT%20*%20WHERE%7B%3Fs%20%3Fp%20%3Fo%7D%20LIMIT%201" 120
$ok  = (Start-Service "orchestrator" "write-orchestrator-service\target\vdcs-write-orchestrator-service-1.0-SNAPSHOT.jar" 8089 "http://localhost:8089/orchestrator/health") -and $ok
if (-not $ok) { Write-Host "Tier 3 incomplete - aborting" -ForegroundColor Red; exit 1 }

Write-Host "`n[4] Tier 4 - access interface" -ForegroundColor Cyan
$ok = Start-Service "access" "access-interface\target\vdcs-access-interface-1.0-SNAPSHOT.jar" 8080 "http://localhost:8080/"
if (-not $ok) { Write-Host "Access interface failed" -ForegroundColor Red; exit 1 }

Write-Host "`n========================================================" -ForegroundColor Green
Write-Host " VDCS is up. Open the UI at  http://localhost:8080" -ForegroundColor Green
Write-Host " Stop everything with        .\scripts\stop-all.ps1" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
