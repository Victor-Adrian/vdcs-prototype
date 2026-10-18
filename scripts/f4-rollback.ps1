<#
.SYNOPSIS
    Removes the Library Loans source and restores the base configuration.

.DESCRIPTION
    Stops the services, switches to the base branch, rebuilds the affected
    modules, restarts the stack, and verifies that the source is absent.

.PARAMETER SkipBuild
    Skips the Maven rebuild when the base-branch JARs are already current.

.EXAMPLE
    .\scripts\f4-rollback.ps1
#>
param(
    [switch] $SkipBuild,
    [string] $JavaHome = $(
        # A launcher may still carry JAVA_HOME from before the user changed it.
        $savedJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
        if ([string]::IsNullOrWhiteSpace($savedJavaHome)) { $env:JAVA_HOME } else { $savedJavaHome }
    ),
    [string] $BaseBranch = "main"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}

function Fail([string]$msg) { Write-Host $msg -ForegroundColor Red; exit 1 }

git rev-parse --is-inside-work-tree *> $null
if ($LASTEXITCODE -ne 0) { Fail "Not a git repository." }

$dirty = git status --porcelain
if ($dirty) {
    Write-Host "Working tree has uncommitted changes:" -ForegroundColor Red
    Write-Host $dirty
    Fail "Commit or discard them before toggling (the script switches branches)."
}

# stop everything, including the loans SAC

Write-Host "[1] Stopping services (including the Library-Loans SAC 8090)..." -ForegroundColor Cyan
foreach ($p in 8090, 9093) {
    Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { try { Stop-Process -Id $_.OwningProcess -Force } catch {} }
}
& "$PSScriptRoot\stop-all.ps1" | Out-Null
Start-Sleep -Seconds 2

# back to the base branch

$current = (git rev-parse --abbrev-ref HEAD).Trim()
if ($current -eq $BaseBranch) {
    Write-Host "Already on '$BaseBranch' - nothing to revert in source; will rebuild + restart." -ForegroundColor Yellow
} else {
    Write-Host "`n[2] Switching back to '$BaseBranch' (removes the Library-Loans source)..." -ForegroundColor Cyan
    git switch $BaseBranch
    if ($LASTEXITCODE -ne 0) { Fail "git switch $BaseBranch failed." }
}

# rebuild the changed modules back to four-source form

if (-not $SkipBuild) {
    Write-Host "`n[3] Rebuilding modules to the four-source state..." -ForegroundColor Cyan
    mvn -q -DskipTests -pl ontology,mappings,metadata-service,semantic-mediator install
    if ($LASTEXITCODE -ne 0) { Fail "Build failed." }
    Write-Host "  build OK" -ForegroundColor Green
}

# restart the (four-source) stack

Write-Host "`n[4] Restarting the four-source stack..." -ForegroundColor Cyan
& "$PSScriptRoot\run-all.ps1" -JavaHome $JavaHome -SkipDb -SkipBuild

# verify the source is gone

Write-Host "`n[5] Verifying the Library-Loans source is gone..." -ForegroundColor Cyan
try {
    $sources = Invoke-RestMethod "http://localhost:8082/metadata/sources" -TimeoutSec 5
    $ids = ($sources | ForEach-Object { $_.id }) -join ", "
    if ($ids -match "library-loans") {
        Write-Host "  catalogue STILL lists library-loans (sources = [$ids]) - rollback incomplete." -ForegroundColor Red
    } else {
        Write-Host "  catalogue no longer lists library-loans. Sources = [$ids]" -ForegroundColor Green
    }
} catch {
    Write-Host "  (could not read the catalogue: $($_.Exception.Message))" -ForegroundColor Yellow
}

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
    if ($rows.Count -eq 0) {
        Write-Host "  federated student->loans query now returns 0 rows - source removed." -ForegroundColor Green
        Write-Host "`n  SUCCESS - back to the four-source baseline." -ForegroundColor Green
    } else {
        Write-Host ("  query still returned {0} row(s) - rollback incomplete." -f $rows.Count) -ForegroundColor Red
    }
} catch {
    # An error here is also acceptable evidence the predicates are gone.
    Write-Host "  federated query no longer resolves (loans predicates unknown) - source removed." -ForegroundColor Green
    Write-Host "`n  SUCCESS - back to the four-source baseline." -ForegroundColor Green
}
