# The factory router (formerly tick), run every 30 minutes by the Windows Scheduled Task (nuvio-tv-tick; tick.ps1 forwards here). No model call unless review work is due.
# 1. dispatch.ps1 starts Cursor on ready work (the FREEZE file pauses only this part).
# 2. A cheap gh check decides whether the Claude review run (review.md) is due; only then is `claude -p` started.
# Review is due when an open PR has no "Claude review @ <head>" comment, an open issue has no intent/later/owner-check
# label, or no review has started in 24 hours. Each PR head starts at most one review; one review runs at a time.
# Env: JIVA_CLAUDE = path of the claude CLI (default: `claude` on PATH, else `claude` inside WSL);
#      JIVA_CLAUDE_ARGS = permission flags for the unattended run, set by the owner (default: --permission-mode acceptEdits).
# The review's output goes to a scratch file while it runs; the first tick after it ends copies it, indented, into the history (Cursor's own per-run logs stay in the dispatch folder).
# -ReviewNow: manual run; skips Cursor dispatch and starts the review even if nothing is due (the one-at-a-time lock still applies).
# Usage: tick.ps1 [-DryRun] [-ReviewNow]
param([switch]$DryRun, [switch]$ReviewNow)
$ErrorActionPreference = 'Stop'
$env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'safe.directory'; $env:GIT_CONFIG_VALUE_0 = '*'
. "$PSScriptRoot/project.ps1"
New-Item -ItemType Directory -Force $state | Out-Null
$lock = Join-Path $state 'review-running.json'

$dispatch = if ($ReviewNow) { 'dispatch: skipped (-ReviewNow)' } elseif ($DryRun) { & "$PSScriptRoot\dispatch.ps1" -DryRun } else { & "$PSScriptRoot\dispatch.ps1" }
# dispatch: lines are summarised in the tick line below; anything else (a dry run's task text) passes through.
$dispatch | Where-Object { $_ -notlike 'dispatch:*' } | ForEach-Object { Write-Output $_ }
$d = ($dispatch | Where-Object { $_ -like 'dispatch:*' } | Select-Object -Last 1) -replace '^dispatch: ', ''

function Review {
    if (Test-Path $lock) {
        $r = Get-Content $lock -Raw | ConvertFrom-Json
        if (Get-Process -Id $r.pid -ErrorAction SilentlyContinue) { return "busy since $($r.started) (output joins the history when it ends)" }
        # The finished run's output joins the single history (this script's output is appended to $history).
        Write-Output "review finished: $($r.what) (started $($r.started), ended $((Get-Item $r.log).LastWriteTime.ToString('s')))"
        foreach ($f in $r.log, "$($r.log).err") {
            if (Test-Path $f) { Get-Content $f | ForEach-Object { Write-Output "    $_" }; Remove-Item $f }
        }
        Remove-Item $lock
    }
    $why = @()
    $marks = @()
    $prs = gh pr list -R $repo --state open --json number,headRefOid | ConvertFrom-Json
    foreach ($pr in $prs) {
        $short = $pr.headRefOid.Substring(0, 7)
        $mark = Join-Path $state "review-$($pr.number)-$short.txt"
        if (Test-Path $mark) { continue }
        $comments = (gh pr view $pr.number -R $repo --json comments | ConvertFrom-Json).comments
        if (-not ($comments | Where-Object { $_.body.TrimStart([char]0xFEFF, ' ', "`n", "`r") -like "Claude review @ $short*" })) {
            $why += "PR #$($pr.number) @ $short"; $marks += $mark
        }
    }
    $issues = gh issue list -R $repo --state open --limit 200 --json number,labels | ConvertFrom-Json
    $new = @($issues | Where-Object {
        ($pinned -notcontains $_.number) -and (-not (@($_.labels.name) | Where-Object { $_ -in 'architect', 'implement', 'review', 'later', 'owner-check' }))
    } | ForEach-Object { "#$($_.number)" })
    if ($new) { $why += "unlabelled issues $($new -join ' ')" }
    $stamp = Join-Path $state 'review-last.txt'
    if (-not (Test-Path $stamp) -or ((Get-Date) - (Get-Item $stamp).LastWriteTime).TotalHours -ge 24) { $why += 'daily triage' }
    if ($ReviewNow) { $why += 'manual run (-ReviewNow)' }
    if (-not $why) { return "nothing due ($(@($prs).Count) open PRs all reviewed at their current head, no unlabelled issues, daily triage done)" }

    $claude = if ($env:JIVA_CLAUDE) { $env:JIVA_CLAUDE } else { (Get-Command claude -ErrorAction SilentlyContinue).Source }
    $wsl = -not $claude -and (wsl.exe -e bash -lc 'command -v claude' 2>$null)
    if (-not $claude -and -not $wsl) { return "due ($($why -join '; ')) but the claude CLI was not found: install it and log in, or set JIVA_CLAUDE" }
    if ($DryRun) { return "would start: $($why -join '; ')" }

    $perm = if ($env:JIVA_CLAUDE_ARGS) { @($env:JIVA_CLAUDE_ARGS -split ' ') } else { @('--permission-mode', 'acceptEdits') }
    $log = Join-Path $state "review-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"
    $prompt = "In PowerShell run git -c safe.directory=* -C $root pull --ff-only. Then read $root\.factory\review.md and follow it exactly for this whole run; it is the only copy of these instructions. Your trigger: $($why -join '; ')."
    if ($wsl) {
        # In WSL the checkout is $($wslRoot) and the PowerShell steps of review.md run through powershell.exe.
        $claude = 'wsl.exe'
        $cmd = "cd $($wslRoot) && claude -p $($perm -join ' ') --output-format text '$($prompt -replace "'", '') Your shell is WSL bash: run every PowerShell step of review.md with powershell.exe.'"
        $args = @('-e', 'bash', '-lc', "`"$cmd`"")
    } else { $args = @('-p') + $perm + @('--output-format', 'text', "`"$prompt`"") }
    $p = Start-Process -FilePath $claude -WorkingDirectory $root -WindowStyle Hidden -PassThru -ArgumentList $args `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    @{ pid = $p.Id; what = ($why -join '; '); started = (Get-Date -Format s); log = $log } | ConvertTo-Json | Set-Content $lock -Encoding utf8
    $marks | ForEach-Object { Set-Content $_ (Get-Date -Format s) -Encoding utf8 }
    Set-Content $stamp (Get-Date -Format s) -Encoding utf8
    "started: $($why -join '; ') (pid $($p.Id))"
}

# Board cards of closed issues move to Done (or go), no model call.
try { Write-Output (& "$PSScriptRoot\sync-board.ps1" -DryRun:$DryRun | Select-Object -Last 1) } catch { Write-Output "board: error: $($_.Exception.Message)" }
# Housekeeping every tick, no model call, for PRs merged by anyone (reviewer, other threads, the owner).
$clean = try { (& "$PSScriptRoot\cleanup.ps1" -DryRun:$DryRun | Select-Object -Last 1) } catch { "cleanup: error: $($_.Exception.Message)" }
Write-Output $clean
# Intent labels follow the PR state (implement -> review when a PR opens), no model call.
try { Write-Output (& "$PSScriptRoot\sync-labels.ps1" -DryRun:$DryRun | Select-Object -Last 1) } catch { Write-Output "labels: error: $($_.Exception.Message)" }
try { $r = Review } catch { $r = "error: $($_.Exception.Message)" }
Write-Output "router: dispatch=[$d] review=[$r]"
$w = Get-WorkerStatus
Write-Output "worker: $($w.text)"
$next = if ($w.text -like 'RUNNING*') { 'worker busy; the next tick re-checks' } elseif ($r -like 'started*' -or $r -like 'busy*') { 'review running; its output joins this history when it ends' } elseif ($d -like 'started*') { 'worker started; the next tick re-checks' } elseif ($d -like 'frozen*') { 'paused by FREEZE file; freeze.ps1 -Off resumes' } else { 'nothing; label an issue implement or architect (and not later) to give a worker work' }
Write-Output "next: $next"
