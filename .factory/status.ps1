# Plain-words status of the factory: ticks, workers, PRs, issues, leftovers. Read-only, no model call.
# Usage: status.ps1
$ErrorActionPreference = 'Continue'
$env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'safe.directory'; $env:GIT_CONFIG_VALUE_0 = '*'
. "$PSScriptRoot/project.ps1"
$warn = @()

$t = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if (-not $t) { $warn += "$taskName task is missing (register-tick-task.ps1)"; Write-Output 'TICK: not registered' }
else {
    $i = Get-ScheduledTaskInfo $t
    $age = [int]((Get-Date) - $i.LastRunTime).TotalMinutes
    Write-Output "TICK: last ran $age min ago (result $($i.LastTaskResult)), next $($i.NextRunTime.ToString('HH:mm'))"
    if ($age -gt 90 -or $i.LastTaskResult -ne 0) { $warn += "tick looks unhealthy: ran $age min ago, result $($i.LastTaskResult)" }
}
foreach ($f in 'FREEZE', 'FREEZE-deepseek') { if (Test-Path (Join-Path $state $f)) { Write-Output "PAUSED: $f file present (freeze.ps1 -Off to resume)" } }

$lock = Join-Path $state 'review-running.json'
if (Test-Path $lock) {
    $r = Get-Content $lock -Raw | ConvertFrom-Json
    $mins = [int]((Get-Date) - [datetime]$r.started).TotalMinutes
    if (Get-Process -Id $r.pid -ErrorAction SilentlyContinue) {
        Write-Output "REVIEW: RUNNING $mins min ($($r.what))"
        if ($mins -gt 60) { $warn += "review has run $mins min: probably stuck (kill pid $($r.pid), delete $lock)" }
    } else { $warn += "review lock is held by dead pid $($r.pid); the next tick clears it" }
} else { Write-Output 'REVIEW: idle' }

$w = Get-WorkerStatus
Write-Output "WORKER: $($w.text)"
if ($w.warn) { $warn += $w.warn }

Write-Output 'PRS:'
$prs = gh pr list -R $repo --state open --json number,title,headRefOid,reviewDecision | ConvertFrom-Json
if (-not $prs) { Write-Output '  none open' }
foreach ($pr in $prs) {
    $short = $pr.headRefOid.Substring(0, 7)
    $c = (gh pr view $pr.number -R $repo --json comments | ConvertFrom-Json).comments
    $rev = $c | Where-Object { $_.body.TrimStart([char]0xFEFF, ' ', "`n", "`r") -like "Claude review @ $short*" } | Select-Object -Last 1
    $s = if (-not $rev) { 'waiting for review' } elseif ($rev.body -match '(?i)request(ed)? changes|needs changes') { 'review asked for changes (worker fixes it)' } else { 'reviewed, waiting to merge or for you' }
    Write-Output "  #$($pr.number) $($pr.title) - $s"
}

$issues = gh issue list -R $repo --state open --limit 200 --json number,labels | ConvertFrom-Json
$n = @{ ready = 0; later = 0; owner = 0; none = 0 }
foreach ($x in $issues) {
    $l = @($x.labels.name)
    if ($l -contains 'owner-check') { $n.owner++ } elseif ($l -contains 'later') { $n.later++ } elseif ($l -match '^(architect|implement)$') { $n.ready++ } elseif ($pinned -notcontains $x.number) { $n.none++ }
}
Write-Output "ISSUES: $($n.ready) ready, $($n.owner) waiting for you (owner-check), $($n.later) later, $($n.none) unlabelled"

# Worktrees whose branch has a merged PR and no open one are leftovers (cleanup.ps1 removes them each tick; case-insensitive).
$allPrs = gh pr list -R $repo --state all --limit 500 --json state,headRefName | ConvertFrom-Json
git -C $root worktree list --porcelain | Where-Object { $_ -like 'branch *' } | ForEach-Object {
    $b = $_ -replace '^branch refs/heads/', ''
    if ($b -in 'main', $base) { return }
    $l = @($allPrs | Where-Object { $_.headRefName -ieq $b })
    if ($l -and -not ($l | Where-Object { $_.state -eq 'OPEN' })) { $warn += "leftover worktree for $b (PR $($l[0].state)): cleanup.ps1 removes it" }
}
Write-Output ''
if ($warn) { Write-Output 'WARNINGS:'; $warn | ForEach-Object { Write-Output "  - $_" } } else { Write-Output 'Nothing stuck.' }
