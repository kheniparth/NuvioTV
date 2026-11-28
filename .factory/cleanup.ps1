# Housekeeping with no model call: removes worktrees and local/remote branches whose PR is merged or closed, whoever merged it.
# Run by router.ps1 on every tick (review.md step 3c used to be the only place, and it ran only when a review was due).
# Safe by construction: never touches main, an open PR's branch, review/deploy worktrees, a dirty worktree, or a branch
# that holds commits the merged PR did not contain. Branch names compare case-insensitively (Windows: DeepSeek/ vs deepseek/).
# Usage: cleanup.ps1 [-DryRun]
param([switch]$DryRun)
$ErrorActionPreference = 'Continue'   # git writes progress to stderr
$env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'safe.directory'; $env:GIT_CONFIG_VALUE_0 = '*'
. "$PSScriptRoot/project.ps1"
$wtRoot = Join-Path $root '.claude\worktrees'
$done = @()
function G { git -C $root @args 2>$null }
function Act($what, [scriptblock]$do) {
    if ($DryRun) { $script:done += "would $what"; return }
    try { & $do; $script:done += $what } catch { $script:done += "FAILED $what ($($_.Exception.Message))" }
}

G fetch origin --prune | Out-Null
$prs = gh pr list -R $repo --state all --limit 500 --json number,state,headRefName,headRefOid | ConvertFrom-Json
# branch (lower case) -> its PRs; a branch is dead when it has PRs and none is open.
$by = @{}
foreach ($p in $prs) { $k = $p.headRefName.ToLower(); if (-not $by[$k]) { $by[$k] = @() }; $by[$k] += $p }
function Dead($branch) {
    $l = $by[$branch.ToLower()]
    [bool]($l -and -not ($l | Where-Object { $_.state -eq 'OPEN' }))
}
function Merged($sha) { G merge-base --is-ancestor $sha origin/$base | Out-Null; $LASTEXITCODE -eq 0 }
function DeadTip($branch, $sha) {
    (Dead $branch) -and ((Merged $sha) -or [bool]($by[$branch.ToLower()] | Where-Object { $_.headRefOid -eq $sha }))
}

# 1. Worktrees
$wts = @(); $cur = $null
foreach ($line in (G worktree list --porcelain)) {
    if ($line -like 'worktree *') { $cur = @{ path = ($line.Substring(9) -replace '/', '\'); head = ''; branch = $null }; $wts += $cur }
    elseif ($line -like 'HEAD *') { $cur.head = $line.Substring(5) }
    elseif ($line -like 'branch *') { $cur.branch = $line -replace '^branch refs/heads/', '' }
}
foreach ($w in $wts) {
    $name = Split-Path $w.path -Leaf
    if (-not $w.path.StartsWith($wtRoot, [StringComparison]::OrdinalIgnoreCase) -or $name -in 'review', 'deploy') { continue }
    $dead = $false
    if ($w.branch) { $dead = Dead $w.branch }
    elseif ($name -match '^(cursor|deepseek)-issue-(\d+)$') {   # detached worker worktree: match by issue number
        $n = $Matches[2]; $l = @($prs | Where-Object { $_.headRefName -match "^(cursor|deepseek)/$n-" })
        $dead = [bool]($l -and -not ($l | Where-Object { $_.state -eq 'OPEN' }))
        if (-not $l) { $dead = (gh issue view $n -R $repo --json state --jq .state) -eq 'CLOSED' }   # no PR ever: dead once the issue is closed
    } elseif ($name -match '^(cursor|deepseek)-fix-(\d+)$') {
        $l = @($prs | Where-Object { $_.number -eq [int]$Matches[2] }); $dead = [bool]($l -and $l[0].state -ne 'OPEN')
    }
    if (-not $dead) { continue }
    if (git -C $w.path status --short 2>$null) { $done += "skipped $name (uncommitted changes)"; continue }
    if (git -C $w.path log HEAD --not --remotes --oneline 2>$null) { $done += "skipped $name (unpushed commits)"; continue }
    Act "remove worktree $name" { git -C $root worktree remove --force $w.path 2>&1 | Out-Null; if ($LASTEXITCODE) { throw 'git worktree remove failed (in use?)' } }
}
G worktree prune | Out-Null

# 2. Unregistered leftover folders (not in `git worktree list`) that are empty
$reg = @(G worktree list --porcelain | Where-Object { $_ -like 'worktree *' } | ForEach-Object { ($_.Substring(9) -replace '/', '\').ToLower() })
if (Test-Path $wtRoot) {
    Get-ChildItem $wtRoot -Directory | Where-Object { $reg -notcontains $_.FullName.ToLower() -and -not (Get-ChildItem $_.FullName -Force) } |
        ForEach-Object { $d = $_.FullName; Act "delete empty folder $($_.Name)" { Remove-Item $d -Force } }
}

# 3. Local branches (not checked out anywhere) and remote branches of dead PRs
$checked = @(G worktree list --porcelain | Where-Object { $_ -like 'branch *' } | ForEach-Object { ($_ -replace '^branch refs/heads/', '').ToLower() })
foreach ($r in (G for-each-ref --format='%(refname:short) %(objectname)' refs/heads)) {
    $b, $sha = $r -split ' '
    if ($b -in 'main', $base -or $checked -contains $b.ToLower()) { continue }
    if (DeadTip $b $sha) { Act "delete local branch $b" { git -C $root branch -D $b | Out-Null } }
}
foreach ($r in (G for-each-ref --format='%(refname:short) %(objectname)' refs/remotes/origin)) {
    $b, $sha = $r -split ' '; $b = $b -replace '^origin/', ''
    if ($b -in 'main', $base, 'HEAD', 'origin') { continue }
    if (DeadTip $b $sha) { Act "delete remote branch $b" { git -C $root push origin --delete $b 2>&1 | Out-Null; if ($LASTEXITCODE) { throw 'push --delete failed' } } }
}

if ($done) { Write-Output "cleanup: $($done -join '; ')" } else { Write-Output 'cleanup: nothing to clean' }
