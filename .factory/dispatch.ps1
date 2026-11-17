# Starts a local agent CLI on the next piece of NuvioTV fork work, in the background: Cursor by default, or the DeepSeek worker
# (`claude -p` in WSL pointed at DeepSeek's Anthropic-compatible API, key in the user env var DEEPSEEK_API_KEY)
# (labels name the task, never the worker). A ready issue (task label `implement`/`architect`) goes to whichever worker is free: Cursor, or DeepSeek while Cursor is frozen
# or unusable (agent missing, not logged in). DeepSeek branches are `deepseek/<n>-...`.
# Work, in order: an open PR whose latest Claude review asked for changes on its current head,
# then the next open issue with an intent label (bugs first, lowest number) that has no PR yet.
# One agent at a time (lock file); each piece is dispatched at most once per head/issue.
# Usage: dispatch.ps1 [-DryRun] [-Issue <n>] [-FixPr <n>]
param([switch]$DryRun, [int]$Issue = 0, [int]$FixPr = 0)
$ErrorActionPreference = 'Stop'
$env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'safe.directory'; $env:GIT_CONFIG_VALUE_0 = '*'
. "$PSScriptRoot/project.ps1"
. "$PSScriptRoot/workers.ps1"
$agent = "$env:LOCALAPPDATA\cursor-agent\agent.cmd"
# Lock and markers live outside the repo so they survive pulls and stop double starts.
New-Item -ItemType Directory -Force $state | Out-Null
$lock = Join-Path $state 'running.json'

function Say($text) { Write-Output "dispatch: $text" }

# Worker choice comes from workers.json (workers.ps1). Owner freeze: FREEZE stops new Cursor starts, FREEZE-deepseek stops new DeepSeek starts (delete a file to resume);
# a running agent is not killed. Work Cursor would take goes to DeepSeek while only FREEZE exists.
$cursorFrozen = Test-Path (Join-Path $state 'FREEZE')
$dsFrozen = Test-Path (Join-Path $state 'FREEZE-deepseek')
# DeepSeek also freezes itself on the schedule in workers.json (peak pricing); that is separate from the FREEZE-deepseek file.
$cfg = Get-Content (Join-Path $PSScriptRoot 'workers.json') -Raw | ConvertFrom-Json
$dsSchedule = Get-ScheduleFreeze $cfg.workers.deepseek
if ($dsSchedule) { $dsFrozen = $true }
if ($cursorFrozen -and $dsFrozen) { Say "frozen: Cursor (FREEZE) and DeepSeek ($(if ($dsSchedule) { $dsSchedule } else { 'FREEZE-deepseek' })), not dispatching"; exit 0 }

if (Test-Path $lock) {
    $running = Get-Content $lock -Raw | ConvertFrom-Json
    if (Get-Process -Id $running.pid -ErrorAction SilentlyContinue) {
        Say "busy: $($running.what) since $($running.started) (log $($running.log))"
        exit 0
    }
    Say "previous run finished: $($running.what) (log $($running.log))"
    Remove-Item $lock
}

git -C $root fetch -q origin
$prs = gh pr list -R $repo --state open --json number,headRefName,headRefOid | ConvertFrom-Json

# 1. A PR whose latest Claude review on its current head asked for changes.
$job = $null
foreach ($pr in ($prs | Sort-Object number)) {
    if ($FixPr -and $pr.number -ne $FixPr) { continue }
    if ($Issue) { break }
    $short = $pr.headRefOid.Substring(0, 7)
    $marker = Join-Path $state "fix-$($pr.number)-$short.txt"
    if (Test-Path $marker) { continue }
    $comments = (gh pr view $pr.number -R $repo --json comments | ConvertFrom-Json).comments
    # Comment files written by PowerShell 5 may start with a byte-order mark; ignore it.
    $review = $comments | Where-Object { $_.body.TrimStart([char]0xFEFF, ' ', "`n", "`r") -like "Claude review @ $short*" } | Select-Object -Last 1
    if ($review -and $review.body -match 'Changes requested') {
        # The worker that wrote the PR fixes it; if it is frozen, the next worker for the implement role (workers.json) takes over.
        $author = if ($pr.headRefName -like 'deepseek/*') { 'deepseek' } else { 'cursor' }
        $pick = Select-Worker 'implement' $author
        if (-not $pick) { $pick = Select-Worker 'implement' }
        if (-not $pick) { continue }
        $job = @{ kind = 'fix'; number = $pr.number; branch = $pr.headRefName; marker = $marker; worker = $pick.worker; pick = $pick }
        break
    }
}

# 2. The next open issue with an intent label (`architect`, `implement`) without a PR.
if (-not $job -and -not $FixPr) {
    $issues = gh issue list -R $repo --state open --limit 100 --json number,title,labels | ConvertFrom-Json
    # Intent labels (workers.json `intents`): architect or implement.
    $readyLabels = @($cfg.intents.architect.labels) + @($cfg.intents.implement.labels)
    $taken = @($prs | ForEach-Object { if ($_.headRefName -match '^(cursor|deepseek)/(\d+)-') { [int]$Matches[2] } })
    $open = $issues | Where-Object {
        $names = @($_.labels | ForEach-Object { $_.name })
        ($taken -notcontains $_.number) -and ($names -notcontains 'later') -and (@($names | Where-Object { $readyLabels -contains $_ }).Count -gt 0) -and
        (-not (Test-Path (Join-Path $state "issue-$($_.number).txt"))) -and
        ((-not $Issue) -or $_.number -eq $Issue)
    } | Sort-Object @{ Expression = { if (@($_.labels | ForEach-Object { $_.name }) -contains 'bug') { 0 } else { 1 } } }, number
    $next = $open | Select-Object -First 1
    if ($next) {
        # Model policy: Cursor's pooled "auto" (the default, cheapest) unless the work is firmware,
        # protocol or design-heavy: label `strong` or such a word in the title picks the strong model.
        $strong = (@($next.labels | ForEach-Object { $_.name }) -contains 'strong') -or ($next.title -match 'player|preview|guide|epg grid')
        # Intent -> role -> worker, all from workers.json: `architect` uses the architect role, everything else `implement` (or
        # `implement-strong`).
        $nn = @($next.labels | ForEach-Object { $_.name })
        $intent = if ($nn -contains 'architect') { 'architect' } else { 'implement' }
        $pick = Select-Worker $(if ($intent -eq 'architect') { 'architect' } elseif ($strong) { 'implement-strong' } else { 'implement' })
        if ($pick) { $job = @{ kind = 'issue'; intent = $intent; number = $next.number; title = $next.title; strong = $strong; marker = (Join-Path $state "issue-$($next.number).txt"); worker = $pick.worker; pick = $pick } }
    }
}

if (-not $job) {
    # Say why, so the history shows more than "nothing": counts of what was passed over and the freeze state.
    $why = @("no PR needs fixes ($(@($prs).Count) open)")
    if ($issues) {
        $lab = @($issues | Where-Object { $n = @($_.labels | ForEach-Object { $_.name }); @($n | Where-Object { $readyLabels -contains $_ }).Count -gt 0 })
        $later = @($lab | Where-Object { @($_.labels | ForEach-Object { $_.name }) -contains 'later' }).Count
        $had = @($lab | Where-Object { $taken -contains $_.number -or (Test-Path (Join-Path $state "issue-$($_.number).txt")) }).Count
        $why += "$($lab.Count) ready (architect/implement) issues: $later on hold (later), $had already taken (started or has a PR)"
    }
    if ($cursorFrozen) { $why += 'Cursor frozen (FREEZE)' }
    if ($dsSchedule) { $why += "DeepSeek frozen by schedule ($dsSchedule)" }
    Say "nothing to dispatch: $($why -join '; ')"; exit 0
}
# Cursor unusable (agent missing or logged out): hand the job to DeepSeek when it is not frozen.
if ($job.worker -eq 'cursor' -and -not $dsFrozen -and -not ((Test-Path $agent) -and -not ((& $agent status 2>&1 | Out-String) -match 'Not logged in'))) {
    $alt = Select-Worker $(if ($job.intent -eq 'architect') { 'architect' } elseif ($job.strong) { 'implement-strong' } else { 'implement' }) 'deepseek'
    if ($alt) { $job.worker = 'deepseek'; $job.pick = $alt; Say 'Cursor agent unavailable, using DeepSeek' }
}
if ($job.worker -eq 'deepseek' -and $dsFrozen) { Say "frozen: DeepSeek ($(if ($dsSchedule) { $dsSchedule } else { 'FREEZE-deepseek' })), not starting $($job.kind) #$($job.number)"; exit 0 }
$ds = $job.worker -eq 'deepseek'

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$name = "$($job.worker)-$($job.kind)-$($job.number)"
$wt = Join-Path $root ".claude\worktrees\$name"
$task = Join-Path $state "$name.md"
$log = Join-Path $state "$name-$stamp.log"
$checks = $checks.Replace('<this working folder>', $wt.Replace([string][char]92, '/'))   # from project.ps1
if ($job.kind -eq 'issue') {
    $what = "issue #$($job.number) $($job.title)"
    $intentNote = if ($job.intent -eq 'architect') { "This issue is labelled architect: deliver a design, not the full implementation. Write a short design doc under design/ at the repo root (docs/ is gitignored), open a PR with it, and file the follow-up implement issues (complete briefs per .factory/AGENTS.md)." } else { '' }
    $text = @"
You are Cursor, implementing GitHub issue #$($job.number) of $repo unattended. The owner is away: nobody answers questions in this terminal.
Working folder: $wt (a git worktree of $root, detached at origin/$base). Never change, check out or commit in $root itself.

1. Read .factory/AGENTS.md (all rules) and the issue with its comments:
   gh issue view $($job.number) -R $repo --comments
2. git switch -c cursor/$($job.number)-<short-name>, then implement the whole issue with tests, inside its scope. $intentNote
3. $checks
4. git push -u origin HEAD, then gh pr create -R $repo --base $base --body-file <file>, filling the PR template's structured handoff (.factory/handoff.md): Status, Risk, What changed, Verify (the checks you ran), Owner tests, Next agent must, Do not reopen, Open questions. Include "Closes #$($job.number)".
5. Never merge, never push to $base, never force-push, never open a PR against NuvioMedia/*. If the issue says to ask the owner first (money, licences, large downloads, a hardware or container change), or you are blocked, comment on the issue (gh issue comment $($job.number) -R $repo --body-file <file>) and stop.
"@
    $start = "origin/$base"
} else {
    $what = "fix PR #$($job.number) ($($job.branch))"
    $text = @"
You are Cursor, fixing pull request #$($job.number) of $repo unattended after Claude's review asked for changes. The owner is away: nobody answers questions in this terminal.
Working folder: $wt (a git worktree of $root, detached at origin/$($job.branch)). Never change, check out or commit in $root itself.

1. Read .factory/AGENTS.md and the PR with its comments: gh pr view $($job.number) -R $repo --comments
   The latest comment starting "Claude review @" lists the fixes to make.
2. Make every requested fix, with tests.
3. $checks
4. git push origin HEAD:$($job.branch), update the PR body's Status, Verify and Next agent must (see .factory/handoff.md), then comment on the PR: "Cursor: addressed review" and a numbered list of what you changed.
5. Never merge, never push to $base, never force-push.
"@
    $start = "origin/$($job.branch)"
}

# Fix PRs keep the branch they already have (cursor/ or deepseek/); only new issue branches are renamed.
if ($ds) { $text = $text -replace 'Cursor', 'DeepSeek'; if ($job.kind -eq 'issue') { $text = $text -replace 'cursor/', 'deepseek/' } }

if ($DryRun) { Say "would start: $what in $wt"; Write-Output $text; exit 0 }

if (Test-Path $wt) { git -C $root worktree remove --force $wt }
git -C $root worktree add -q --detach $wt $start
Set-Content -Path $task -Value $text -Encoding utf8

if ($ds) {
    # A process started before the key was set (an open terminal, an old session) lacks it; the user-level value is the source of truth.
    if (-not $env:DEEPSEEK_API_KEY) { $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User') }
    $wsl = (wsl.exe -e bash -lc 'command -v claude' 2>$null)
    if (-not $env:DEEPSEEK_API_KEY -or -not $wsl) { Say 'DeepSeek worker needs the user env var DEEPSEEK_API_KEY and claude inside WSL'; exit 1 }
}
else {
    if (-not (Test-Path $agent)) { Say "Cursor agent CLI not found at $agent"; exit 1 }
    if ((& $agent status 2>&1 | Out-String) -match 'Not logged in') { Say "the Cursor agent is not logged in: the owner must run 'agent login'"; exit 1 }
}
$prompt = "First read $root\.factory\ponytail.md and follow it at the full level for the whole run: the smallest complete change, no extras, keep your reading and output lean to save credits (.factory/AGENTS.md wins where they differ). Then follow the instructions in the file $task exactly. Work only in $wt."
$model = $job.pick.modelId
$modelArgs = if (-not $ds -and $model -ne 'auto') { @('--model', $model) } else { @() }
$arguments = $modelArgs + @('-p', '--force', '--trust', '--output-format', 'text', '--workspace', $wt, '--add-dir', $state, "`"$prompt`"")
if ($ds) {
    # DeepSeek's Anthropic-compatible API through the Claude CLI in WSL. Every model slot (main, small, opus/sonnet/haiku aliases, subagents) is deepseek-flash: pro was billed more and is not used. The key reaches WSL by name (WSLENV), never on a command line.
    # Output is JSON (result text plus token usage) so the factory UI can show tokens. Permissions: edits plus an allow-list (git, gh, PowerShell, the Windows venv and Windows git for the checks and commits, docker logs); JIVA_DEEPSEEK_ARGS replaces the list.
    $saved = @{}
    $vars = @{ ANTHROPIC_BASE_URL = 'https://api.deepseek.com/anthropic'; ANTHROPIC_AUTH_TOKEN = $env:DEEPSEEK_API_KEY; ANTHROPIC_MODEL = $model; ANTHROPIC_SMALL_FAST_MODEL = 'deepseek-flash'; ANTHROPIC_DEFAULT_OPUS_MODEL = 'deepseek-flash'; ANTHROPIC_DEFAULT_SONNET_MODEL = 'deepseek-flash'; ANTHROPIC_DEFAULT_HAIKU_MODEL = 'deepseek-flash'; CLAUDE_CODE_SUBAGENT_MODEL = 'deepseek-flash'; WSLENV = 'ANTHROPIC_BASE_URL:ANTHROPIC_AUTH_TOKEN:ANTHROPIC_MODEL:ANTHROPIC_SMALL_FAST_MODEL:ANTHROPIC_DEFAULT_OPUS_MODEL:ANTHROPIC_DEFAULT_SONNET_MODEL:ANTHROPIC_DEFAULT_HAIKU_MODEL:CLAUDE_CODE_SUBAGENT_MODEL' }
    foreach ($k in $vars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $vars[$k]) }
    $wslWt = (wsl.exe -e wslpath -u $wt) -join ''
    $wslState = (wsl.exe -e wslpath -u $state) -join ''
    $perm = if ($env:JIVA_DEEPSEEK_ARGS) { $env:JIVA_DEEPSEEK_ARGS } else { "--permission-mode acceptEdits --allowedTools 'Bash(git *)' 'Bash(gh *)' 'Bash(powershell.exe *)' 'Bash(python3 *)' 'Bash(bash /mnt/d/nuvio-factory/NuvioTV/.factory/build.sh *)' 'Bash(/mnt/c/Program Files/Git/cmd/git.exe *)' 'Bash(ls *)' 'Bash(grep *)' 'Bash(find *)'" }
    $cmd = "cd '$wslWt' && env -u ANTHROPIC_API_KEY claude -p $perm --add-dir '$wslState' --output-format json '$($prompt.Replace("'", '').Replace('D:\', '/mnt/d/').Replace('\', '/'))'"
    try {
        $process = Start-Process -FilePath wsl.exe -ArgumentList @('-e', 'bash', '-lc', "`"$cmd`"") -WorkingDirectory $wt `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
    } finally { foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) } }
} else {
    $process = Start-Process -FilePath $agent -ArgumentList $arguments -WorkingDirectory $wt `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
}
# running.json doubles as the machine-readable worker status.
@{ pid = $process.Id; worker = $job.worker; kind = $job.kind; number = $job.number; model = $model; what = $what; started = (Get-Date -Format s); log = $log } | ConvertTo-Json | Set-Content $lock -Encoding utf8
Set-Content -Path $job.marker -Value (Get-Date -Format s) -Encoding utf8
Say "started: $what [$($job.worker), model $model] (pid $($process.Id), log $log)"
