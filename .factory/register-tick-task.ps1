# Registers the beast Windows Scheduled Task that runs .factory/tick.ps1 every 10 minutes
# on its own (the one history, $history from project.ps1, holds tick lines and review output; rotated to .1 at 1 MB), with no model call unless review work is due. Run once on beast:
#   powershell -File \\wsl.localhost\Ubuntu\home\techmuzz\nuvio\factory\nuvio-tv\.factory\register-tick-task.ps1
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/project.ps1"
# conhost --headless: Windows 11 hands consoles to Windows Terminal, so powershell.exe alone pops a window that steals focus every tick.
$action = New-ScheduledTaskAction -Execute 'conhost.exe' `
    -Argument ('--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$h = ''' + $history + '''; if ((Test-Path $h) -and (Get-Item $h).Length -gt 1MB) { Move-Item $h $h.1 -Force }; & { Get-Date -Format s; & ''' + $root + '\.factory\tick.ps1'' } *>> $h"')
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 10) `
    -RepetitionDuration (New-TimeSpan -Days 3650)
New-Item -ItemType Directory -Force (Split-Path $history) | Out-Null
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Force `
    -Description 'Runs .factory/tick.ps1 every 10 minutes; starts Cursor on ready work and the Claude review run when due. No model call of its own.' `
| Out-Null
Write-Output "Registered ${taskName}: runs .factory\tick.ps1 every 10 minutes."
