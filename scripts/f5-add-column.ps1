<#
.SYNOPSIS
    Adds a nationality column and exposes it through the federation.

.DESCRIPTION
    Switches to 'f5-nationality', adds and populates the PostgreSQL column,
    rebuilds the affected modules, restarts the services, and verifies the
    property through the global ontology. The databases and an initial
    build must already exist. Use f5-rollback.ps1 to revert it.

.PARAMETER SkipBuild
    Skips the Maven rebuild when the branch JARs are already current.

.EXAMPLE
    .\scripts\f5-add-column.ps1
#>
param(
    [switch] $SkipBuild,
    [string] $JavaHome = $(
        # A launcher may still carry JAVA_HOME from before the user changed it.
        $savedJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
        if ([string]::IsNullOrWhiteSpace($savedJavaHome)) { $env:JAVA_HOME } else { $savedJavaHome }
    ),
    [string] $Branch   = "f5-nationality"
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
    Write-Host "Already on '$Branch' - the column mapping appears to be staged already." -ForegroundColor Yellow
    Write-Host "If services are not running, this script will still apply the column, (re)build and start them." -ForegroundColor Yellow
} else {
    Write-Host "[1] Switching to branch '$Branch' (stages the nationality property + mapping + routing)..." -ForegroundColor Cyan
    git switch $Branch
    if ($LASTEXITCODE -ne 0) { Fail "git switch $Branch failed." }
}

# stop everything so the mediator restarts with the new mapping

Write-Host "`n[2] Stopping running services (so the mediator reloads the PG mapping)..." -ForegroundColor Cyan
& "$PSScriptRoot\stop-all.ps1" | Out-Null
Start-Sleep -Seconds 2

# source side: add the column + backfill
# Done BEFORE the rebuild/restart: Ontop validates the .obda against the
# live PostgreSQL schema at mediator startup, so the column must exist.

Write-Host "`n[3] Adding 'nationality' to PostgreSQL students and backfilling..." -ForegroundColor Cyan
$container = "student-registry"
$psql = "psql -U registry -d registry -v ON_ERROR_STOP=1 -c"
docker exec $container $psql.Split(' ') "ALTER TABLE students ADD COLUMN IF NOT EXISTS nationality VARCHAR(64);"
docker exec $container $psql.Split(' ') "UPDATE students SET nationality = 'Dutch'   WHERE id IN (1, 3, 5, 7);"
docker exec $container $psql.Split(' ') "UPDATE students SET nationality = 'German'  WHERE id IN (2, 4, 6);"
docker exec $container $psql.Split(' ') "UPDATE students SET nationality = 'Italian' WHERE id IN (8, 9, 10);"
Write-Host "  column added + backfilled (Dutch/German/Italian)." -ForegroundColor Green

# Build the affected modules, including a fresh mediator JAR.

if (-not $SkipBuild) {
    Write-Host "`n[4] Building changed modules (ontology, mappings, metadata-service, mediator)..." -ForegroundColor Cyan
    mvn -q -DskipTests -pl ontology,mappings,metadata-service,semantic-mediator install
    if ($LASTEXITCODE -ne 0) { Fail "Build failed." }
    Write-Host "  build OK" -ForegroundColor Green
}

# restart the stack (mediator now serves :nationality)

Write-Host "`n[5] Restarting the stack (mediator reloads the PG mapping with :nationality)..." -ForegroundColor Cyan
& "$PSScriptRoot\run-all.ps1" -JavaHome $JavaHome -SkipDb -SkipBuild

# verify

Write-Host "`n[6] Verifying the new column is queryable through the federation..." -ForegroundColor Cyan

# catalogue now routes :nationality to PostgreSQL
try {
    $sources = Invoke-RestMethod "http://localhost:8082/metadata/sources" -TimeoutSec 5
    $pg = $sources | Where-Object { $_.id -eq "student-registry" }
    if ($pg.produces -contains "nationality") {
        Write-Host "  catalogue: student-registry now produces :nationality." -ForegroundColor Green
    } else {
        Write-Host "  catalogue did NOT list :nationality under student-registry." -ForegroundColor Red
    }
} catch {
    Write-Host "  (could not read the catalogue: $($_.Exception.Message))" -ForegroundColor Yellow
}

# query the NEW PostgreSQL column through the global ontology
$q = @'
PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
SELECT ?name ?nationality WHERE {
  ?s a :Student ; :fullName ?name ; :nationality ?nationality
} ORDER BY ?name
'@

try {
    $res = Invoke-RestMethod "http://localhost:8081/sparql" -Method Post -Body $q `
        -ContentType "application/sparql-query" -Headers @{Accept="application/sparql-results+json"} -TimeoutSec 20
    $rows = @($res.results.bindings)
    Write-Host ("  students-with-nationality query returned {0} row(s):" -f $rows.Count) -ForegroundColor Green
    foreach ($r in $rows) {
        Write-Host ("    {0,-18} {1}" -f $r.name.value, $r.nationality.value)
    }
    if ($rows.Count -gt 0) {
        Write-Host "`n  SUCCESS - the new column is live through the global ontology. The ontology," -ForegroundColor Green
        Write-Host "  PostgreSQL mapping, and catalogue changed; other source implementations were retained." -ForegroundColor Green
    } else {
        Write-Host "`n  Query ran but returned no rows - check logs\mediator.out." -ForegroundColor Red
    }
} catch {
    Write-Host "  Query failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`nRoll back with:  .\scripts\f5-rollback.ps1" -ForegroundColor Cyan
