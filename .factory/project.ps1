# The only file that differs per project. Every other script here dot-sources it; copy this folder to a new project and edit this file (see README.md).
$repo = 'kheniparth/NuvioTV'                  # GitHub owner/name (our fork; never NuvioMedia/*)
$root = '\\wsl.localhost\Ubuntu\home\techmuzz\nuvio\factory\nuvio-tv'            # factory checkout on this machine (Windows git + Cursor work here)
$wslRoot = '/home/techmuzz/nuvio/factory/nuvio-tv'     # the same checkout seen from WSL
$base = 'dev'                                 # base branch for worktrees and PRs
$upstream = 'https://github.com/NuvioMedia/NuvioTV.git'   # merged into $base by the review run (review.md Step 0b)
$taskName = 'nuvio-tv-tick'                   # Windows Scheduled Task registered by register-tick-task.ps1
$pinned = @(1)                                # pinned owner issues, never triaged (#1 Autopilot steering)
$state = Join-Path $env:USERPROFILE '.claude\scheduled-tasks\nuvio-tv\dispatch'   # locks, FREEZE files, review marks
$history = Join-Path $env:USERPROFILE '.nuvio\nuvio-tv-history.log'                # the one tick history
# GitHub Projects board "Nuvio" (user project 2): Status field and its option ids.
$board = @{ number = 2; id = 'PVT_kwHOAGS5vc4BsBpn'; field = 'PVTSSF_lAHOAGS5vc4BsBpnzhqRoTY'
    later = 'bbd11c24'; ready = '3a4b1aec'; inProgress = '69ad0ed5'; inReview = 'a3793284'; ownerCheck = '0618618c'; done = '91eb0252' }
# Checks every worker and the reviewer run before committing. Gradle runs in WSL on a native-disk copy (build.sh), because builds over /mnt/d are 10x slower.
$checks = @'
Checks (all must pass before you commit). From PowerShell:
  wsl -d Ubuntu -- bash /home/techmuzz/nuvio/factory/nuvio-tv/.factory/build.sh "<this working folder>"
or, if your shell is already WSL bash: bash /home/techmuzz/nuvio/factory/nuvio-tv/.factory/build.sh "<this working folder>"
It copies the folder to a WSL build dir and runs :app:assembleFullDebug and :app:testFullDebugUnitTest. It prints a short
summary and the path of the full log; read that log only when a check fails. Never commit build output.
git and gh need the safe-directory exception: the GIT_CONFIG_* environment variables are already set; use `git -c safe.directory=*` if git complains.
Write PR and comment bodies to a file and pass --body-file.
'@

# What the worker is doing right now: the dispatch lock first, then a process scan for a run whose lock is missing.
# Returns @{ text; warn }. shortcut: the scan matches wsl/agent/node command lines holding a worktree path, so a worker launched another way shows as idle.
function Get-WorkerStatus {
    # Two lanes: running.json (Cursor, or DeepSeek when Cursor is out) and running-deepseek.json (the parallel DeepSeek lane).
    $lockNote = $null; $run = @(); $warn = @()
    foreach ($lockFile in (Join-Path $state 'running.json'), (Join-Path $state 'running-deepseek.json')) {
        if (-not (Test-Path $lockFile)) { continue }
        $r = Get-Content $lockFile -Raw | ConvertFrom-Json
        $mins = [int]((Get-Date) - [datetime]$r.started).TotalMinutes
        if (Get-Process -Id $r.pid -ErrorAction SilentlyContinue) {
            if ($mins -gt 120) { $warn += "worker has been on $($r.what) for $mins min: probably stuck (pid $($r.pid))" }
            $run += "$($r.worker) on $($r.what) since $($r.started) ($mins min, pid $($r.pid))"
        } elseif (-not $lockNote) { $lockNote = "last run ($($r.worker): $($r.what), started $($r.started)) has ended" }
    }
    if ($run) { return @{ text = "RUNNING $($run -join ' AND ')"; warn = $(if ($warn) { $warn -join '; ' }) } }
    $found = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(wsl|agent|node|cursor-agent)\.exe$' -and $_.CommandLine -match 'NuvioTV.*worktrees[\/](cursor|deepseek)-(issue|fix)-\d+'
    } | ForEach-Object { if ($_.CommandLine -match 'worktrees[\/]((cursor|deepseek)-(issue|fix)-\d+)') { $Matches[1] } } | Select-Object -Unique)
    if ($found) { return @{ text = "RUNNING $($found -join ', ') (found by process scan, no lock file)"; warn = "worker running without the lock file: dispatch would start a second one" } }
    @{ text = $(if ($lockNote) { "idle ($lockNote)" } else { 'idle' }); warn = $null }
}
