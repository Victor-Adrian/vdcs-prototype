<#
.SYNOPSIS
    Adds the Library Loans source to the running federation.

.DESCRIPTION
    Switches to 'f4-loans', rebuilds the affected modules, restarts the
    services with the loans service on port 8090, and verifies the source
    through the catalogue and a federated query. The databases and an
    initial build must already exist. Use f4-rollback.ps1 to revert it.

.PARAMETER SkipBuild
    Skips the Maven rebuild when the branch JARs are already current.

.EXAMPLE
    .\scripts\f4-add-source.ps1
#>
param(
    [switch] $SkipBuild,
    [string] $JavaHome = $(
        # A launcher may still carry JAVA_HOME from before the user changed it.
        $savedJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
        if ([string]::IsNullOrWhiteSpace($savedJavaHome)) { $env:JAVA_HOME } else { $savedJavaHome }
    ),
    [string] $Branch   = "f4-loans"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}

function Fail([string]$msg) { Write-Host $msg -ForegroundColor Red; exit 1 }

# preconditions

git rev-parse --is-inside-work-tree *> $null
if ($LASTEXITCODE -ne 0) { Fail "Not a git repository. Run the one-time git init first." }

$dirty = git status --porcelain
if ($dirty) {
    Write-Host "Working tree has uncommitted changes:" -ForegroundColor Red
    Write-Host $dirty
    Fail "Commit or discard them before toggling (the script switches branches)."
}

$current = (git rev-parse --abbrev-ref HEAD).Trim()
if ($current -eq $Branch) {
    Write-Host "Already on '$Branch' - the source appears to be added already." -ForegroundColor Yellow
    Write-Host "If services are not running, this script will still (re)build and start them." -ForegroundColor Yellow
} else {
    Write-Host "[1] Switching to branch '$Branch' (adds the Library-Loans source)..." -ForegroundColor Cyan
    git switch $Branch
    if ($LASTEXITCODE -ne 0) { Fail "git switch $Branch failed." }
}

# stop everything so the mediator restarts with 5 sources

Write-Host "`n[2] Stopping running services (so the mediator rewires)..." -ForegroundColor Cyan
& "$PSScriptRoot\stop-all.ps1" | Out-Null
# free the loans SAC port + its H2 TCP port too (stop-all doesn't know them)
foreach ($p in 8090, 9093) {
    Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { try { Stop-Process -Id $_.OwningProcess -Force } catch {} }
}
Start-Sleep -Seconds 2

# build the modules that differ on this branch

if (-not $SkipBuild) {
    Write-Host "`n[3] Building changed modules (ontology, mappings, metadata, mediator, loans SAC)..." -ForegroundColor Cyan
    mvn -q -DskipTests -pl ontology,mappings,metadata-service,semantic-mediator,source-access/source-access-loans-service install
    if ($LASTEXITCODE -ne 0) { Fail "Build failed." }
    Write-Host "  build OK" -ForegroundColor Green
}

# start the new loans SAC (8090), then everything else

$loansJar = "source-access\source-access-loans-service\target\vdcs-source-access-loans-service-1.0-SNAPSHOT.jar"
if (-not (Test-Path $loansJar)) { Fail "Loans SAC jar not found: $loansJar (run without -SkipBuild)." }

Write-Host "`n[4] Starting the Library-Loans SAC on port 8090..." -ForegroundColor Cyan
Start-Process -FilePath "java" -ArgumentList "-jar", $loansJar `
    -RedirectStandardOutput "$root\logs\loans-sac.out" `
    -RedirectStandardError  "$root\logs\loans-sac.err" `
    -WindowStyle Hidden | Out-Null

$deadline = (Get-Date).AddSeconds(60)
$up = $false
while ((Get-Date) -lt $deadline) {
    try { Invoke-WebRequest "http://localhost:8090/sac/health" -UseBasicParsing -TimeoutSec 2 | Out-Null; $up = $true; break } catch {}
    Start-Sleep -Seconds 2
}
if ($up) { Write-Host "  loans-sac      UP   (8090)" -ForegroundColor Green }
else     { Fail "Loans SAC did not come up - see logs\loans-sac.out" }

Write-Host "`n[5] Starting the rest of the stack (mediator now sees 5 sources)..." -ForegroundColor Cyan
& "$PSScriptRoot\run-all.ps1" -JavaHome $JavaHome -SkipDb -SkipBuild

# verify

Write-Host "`n[6] Verifying the new source joined the federation..." -ForegroundColor Cyan

# 4a. catalogue now lists library-loans
try {
    $sources = Invoke-RestMethod "http://localhost:8082/metadata/sources" -TimeoutSec 5
    $ids = ($sources | ForEach-Object { $_.id }) -join ", "
    if ($ids -match "library-loans") {
        Write-Host "  catalogue: library-loans is registered. Sources = [$ids]" -ForegroundColor Green
    } else {
        Write-Host "  catalogue did NOT list library-loans (sources = [$ids])" -ForegroundColor Red
    }
} catch {
    Write-Host "  (could not read the catalogue: $($_.Exception.Message))" -ForegroundColor Yellow
}

# 4b. federated query joining PostgreSQL names to the new Loans source
$q = @'
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?name ?title WHERE {
  ?s :fullName ?name ; :hasLoan ?l .
  ?l :loanTitle ?title
} ORDER BY ?name
'@

try {
    $res = Invoke-RestMethod "http://localhost:8081/sparql" -Method Post -Body $q `
        -ContentType "application/sparql-query" -Headers @{Accept="application/sparql-results+json"} -TimeoutSec 20
    $rows = @($res.results.bindings)
    Write-Host ("  federated student->loans query returned {0} row(s):" -f $rows.Count) -ForegroundColor Green
    foreach ($r in $rows) {
        Write-Host ("    {0,-16} -> {1}" -f $r.name.value, $r.title.value)
    }
    if ($rows.Count -gt 0) {
        Write-Host "`n  SUCCESS - the Library-Loans source is live and federating." -ForegroundColor Green
    } else {
        Write-Host "`n  Query ran but returned no rows - check logs\mediator.out." -ForegroundColor Red
    }
} catch {
    Write-Host "  Federated query failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`nRoll back with:  .\scripts\f4-rollback.ps1" -ForegroundColor Cyan
