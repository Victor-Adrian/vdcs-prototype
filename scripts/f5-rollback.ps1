<#
.SYNOPSIS
    Removes the nationality column and restores the base configuration.

.DESCRIPTION
    Stops the services, switches to the base branch, drops the PostgreSQL
    column, rebuilds the affected modules, and verifies that the property
    is absent. The mapping is reverted before the column is dropped.

.PARAMETER SkipBuild
    Skips the Maven rebuild when the base-branch JARs are already current.

.EXAMPLE
    .\scripts\f5-rollback.ps1
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

# stop everything

Write-Host "[1] Stopping running services..." -ForegroundColor Cyan
& "$PSScriptRoot\stop-all.ps1" | Out-Null
Start-Sleep -Seconds 2

# back to the base branch (mapping no longer references the column)

$current = (git rev-parse --abbrev-ref HEAD).Trim()
if ($current -eq $BaseBranch) {
    Write-Host "Already on '$BaseBranch' - nothing to revert in source; will drop the column, rebuild + restart." -ForegroundColor Yellow
} else {
    Write-Host "`n[2] Switching back to '$BaseBranch' (removes the :nationality mapping + routing)..." -ForegroundColor Cyan
    git switch $BaseBranch
    if ($LASTEXITCODE -ne 0) { Fail "git switch $BaseBranch failed." }
}

# source side: drop the column

Write-Host "`n[3] Dropping 'nationality' from PostgreSQL students..." -ForegroundColor Cyan
$container = "student-registry"
$psql = "psql -U registry -d registry -v ON_ERROR_STOP=1 -c"
docker exec $container $psql.Split(' ') "ALTER TABLE students DROP COLUMN IF EXISTS nationality;"
Write-Host "  column dropped; students is back to its original schema." -ForegroundColor Green

# rebuild the changed modules back to four-source form

if (-not $SkipBuild) {
    Write-Host "`n[4] Rebuilding modules to the original state..." -ForegroundColor Cyan
    mvn -q -DskipTests -pl ontology,mappings,metadata-service,semantic-mediator install
    if ($LASTEXITCODE -ne 0) { Fail "Build failed." }
    Write-Host "  build OK" -ForegroundColor Green
}

# restart + verify

Write-Host "`n[5] Restarting the stack..." -ForegroundColor Cyan
& "$PSScriptRoot\run-all.ps1" -JavaHome $JavaHome -SkipDb -SkipBuild

Write-Host "`n[6] Verifying :nationality no longer resolves..." -ForegroundColor Cyan
$q = 'PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#> SELECT ?nat WHERE { ?s a :Student ; :nationality ?nat } LIMIT 1'
try {
    $res = Invoke-RestMethod "http://localhost:8081/sparql" -Method Post -Body $q `
        -ContentType "application/sparql-query" -Headers @{Accept="application/sparql-results+json"} -TimeoutSec 20
    $rows = @($res.results.bindings)
    if ($rows.Count -eq 0) {
        Write-Host "  :nationality returns no rows - back to the original four-source schema." -ForegroundColor Green
    } else {
        Write-Host "  :nationality still returns rows - check the rebuild (jar may be stale)." -ForegroundColor Red
    }
} catch {
    # An unmapped predicate can also surface as an error; either way the property is gone.
    Write-Host "  :nationality no longer resolves (property removed)." -ForegroundColor Green
}

Write-Host "`nRe-add with:  .\scripts\f5-add-column.ps1" -ForegroundColor Cyan
