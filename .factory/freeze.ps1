# Pauses or resumes new agent starts by creating or deleting the FREEZE file (Cursor) or FREEZE-deepseek file (DeepSeek) dispatch.ps1 checks.
# While only FREEZE exists, work Cursor would take goes to the DeepSeek worker. A run already in progress is not stopped.
# Usage: freeze.ps1 [-DeepSeek] (pause) | freeze.ps1 [-DeepSeek] -Off (resume) | freeze.ps1 -Status
param([switch]$Off, [switch]$Status, [switch]$DeepSeek)
. "$PSScriptRoot/project.ps1"
. "$PSScriptRoot/workers.ps1"
$file = Join-Path $state $(if ($DeepSeek) { 'FREEZE-deepseek' } else { 'FREEZE' })
if ($Off) { Remove-Item $file -ErrorAction SilentlyContinue }
elseif (-not $Status) { New-Item -ItemType Directory -Force $state | Out-Null; New-Item -ItemType File -Force $file | Out-Null }
foreach ($w in @(@('cursor', 'FREEZE', ''), @('deepseek', 'FREEZE-deepseek', ' -DeepSeek'))) {
    $f = Join-Path $state $w[1]
    Write-Output ("$($w[0]) dispatch is " + $(if (Test-Path $f) { "FROZEN (freeze.ps1$($w[2]) -Off to resume)" } else { "running (freeze.ps1$($w[2]) to pause)" }))
}
$sched = Get-ScheduleFreeze (Get-Content (Join-Path $PSScriptRoot 'workers.json') -Raw | ConvertFrom-Json).workers.deepseek
if ($sched) { Write-Output "deepseek is also frozen by its schedule ($sched); that ends on its own, and freeze.ps1 does not change it" }
