<#
.SYNOPSIS
    Stops the VDCS services and frees their ports.

.DESCRIPTION
    Stops processes listening on the configured VDCS ports. Docker
    containers are stopped only when StopDb is supplied.

.PARAMETER StopDb
    Also stops the database containers.

.EXAMPLE
    .\scripts\stop-all.ps1
    .\scripts\stop-all.ps1 -StopDb
#>
param(
    [switch] $StopDb
)

$root = Split-Path -Parent $PSScriptRoot
$ports = 8080,8081,8082,8083,8084,8085,8086,8087,8088,8089,8090,8091,9092,9093

Write-Host "Stopping VDCS services..." -ForegroundColor Cyan
foreach ($p in $ports) {
    $conns = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    foreach ($conn in $conns) {
        try {
            $proc = Get-Process -Id $conn.OwningProcess -ErrorAction Stop
            Stop-Process -Id $proc.Id -Force
            Write-Host ("  freed port {0} (PID {1})" -f $p, $proc.Id) -ForegroundColor Green
        } catch {}
    }
}

Start-Sleep -Seconds 2

# Report any still-in-use
$stillUsed = @()
foreach ($p in $ports) { if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { $stillUsed += $p } }
if ($stillUsed.Count -eq 0) {
    Write-Host "All service ports free." -ForegroundColor Green
} else {
    Write-Host ("Still in use: {0}" -f ($stillUsed -join ', ')) -ForegroundColor Red
}

if ($StopDb) {
    Write-Host "Stopping database containers..." -ForegroundColor Cyan
    docker compose -f "$root\docker-compose.yml" stop | Out-Null
    Write-Host "  DB containers stopped (data preserved in volumes)." -ForegroundColor Green
}
