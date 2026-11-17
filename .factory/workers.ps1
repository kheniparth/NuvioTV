# Reads workers.json. Dot-source after project.ps1 ($state must be set).
# Select-Worker <role> [-Prefer cursor|deepseek (a worker name, never a label)]: first dispatchable worker of the role that is not frozen (FREEZE files in $state, or its schedule),
# restricted to -Prefer when given. Returns @{ worker; model; modelId } or $null when every candidate is frozen.
# Newest non-fast model of the family whose id ends in -<effort>, from `agent models`; 'auto' when none matches.
function Resolve-CursorModel([string]$family, [string]$effort) {
    $agent = Join-Path $env:LOCALAPPDATA 'cursor-agent/agent.cmd'
    if (-not $family -or -not $effort -or -not (Test-Path $agent)) { return 'auto' }
    $ids = @(& $agent models 2>$null | ForEach-Object { if ($_ -match '^(\S+) - ') { $Matches[1] } })
    $best = $ids | Where-Object { $_ -like "*$family*" -and $_ -match "-$effort$" } |
        Sort-Object { if ($_ -match '(\d+(\.\d+)*)') { [version]$Matches[1] } else { [version]'0.0' } } -Descending | Select-Object -First 1
    if ($best) { $best } else { 'auto' }
}
# Reason text when the worker's schedule says it is frozen right now (e.g. peak pricing), else $null. Independent of the FREEZE files.
function Get-ScheduleFreeze($w) {
    if (-not $w.schedule) { return $null }
    $tz = [TimeZoneInfo]::FindSystemTimeZoneById($(if ($w.schedule.timezone) { $w.schedule.timezone } else { 'UTC' }))
    $now = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $tz)
    if (@($w.schedule.offPeakDates) -contains $now.ToString('yyyy-MM-dd')) { return $null }
    foreach ($r in @($w.schedule.freeze)) {
        if ($r.days -and (@($r.days) -notcontains $now.DayOfWeek.ToString().Substring(0, 3))) { continue }
        foreach ($h in @($r.hours)) {
            $from, $to = $h -split '-'
            if ($now.TimeOfDay -ge [TimeSpan]::Parse($from) -and $now.TimeOfDay -lt [TimeSpan]::Parse($to)) { return $w.schedule.reason }
        }
    }
    return $null
}
function Select-Worker([string]$role, [string]$Prefer = '') {
    $cfg = Get-Content (Join-Path $PSScriptRoot 'workers.json') -Raw | ConvertFrom-Json
    foreach ($e in @($cfg.roles.$role)) {
        if ($Prefer -and $e.worker -ne $Prefer) { continue }
        $w = $cfg.workers.($e.worker)
        if ($w.dispatchable -eq $false) { continue }
        if (Test-Path (Join-Path $state $w.freezeFile)) { continue }
        if (Get-ScheduleFreeze $w) { continue }
        $id = if ($e.worker -eq 'cursor') { Resolve-CursorModel $w.family $e.effort } else { $w.models.($e.model) }
        return @{ worker = $e.worker; model = $e.model; effort = $e.effort; modelId = $id }
    }
    return $null
}
