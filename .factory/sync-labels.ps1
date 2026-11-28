# Keeps the intent label honest, no model call (run by router.ps1 every tick). Root cause it fixes: nothing ever moved an issue
# from `implement`/`architect` to `review` when its PR opened, so issues in review kept the work label (or none).
# Rule: an open issue named by "Closes #n" in an open PR carries `review` and no `implement`/`architect`; an open issue with
# `review` but no open PR gets `implement` back (the PR was closed unmerged).
# Get-LabelFixes is pure (tested in tests/test_factory_label_sync.py). Usage: sync-labels.ps1 [-DryRun] [-Test <json>]
param([switch]$DryRun, [string]$Test)
$ErrorActionPreference = 'Stop'
function Get-LabelFixes($issues, $prs) {
    $inReview = @{}
    foreach ($p in $prs) { foreach ($m in [regex]::Matches([string]$p.body, '(?i)(?:closes|fixes|resolves)\s+#(\d+)')) { $inReview[[int]$m.Groups[1].Value] = $true } }
    foreach ($i in $issues) {
        $l = @($i.labels | ForEach-Object { $_.name })
        if ($inReview[[int]$i.number]) {
            $add = @(if ($l -notcontains 'review') { 'review' })
            $rm = @($l | Where-Object { $_ -in 'implement', 'architect' })
        } elseif ($l -contains 'review') { $add = @('implement'); $rm = @('review') } else { continue }
        if ($add -or $rm) { [pscustomobject]@{ number = $i.number; add = $add; remove = $rm } }
    }
}
if ($Test) { $d = Get-Content $Test -Raw | ConvertFrom-Json; ConvertTo-Json -Compress -InputObject @(Get-LabelFixes $d.issues $d.prs); exit 0 }
. "$PSScriptRoot/project.ps1"
$issues = gh issue list -R $repo --state open --limit 300 --json number,labels | ConvertFrom-Json
$prs = gh pr list -R $repo --state open --json number,body | ConvertFrom-Json
$out = foreach ($f in Get-LabelFixes $issues $prs) {
    if ($f.number -in $pinned) { continue }
    if (-not $DryRun) {
        $a = @($f.add | ForEach-Object { '--add-label'; $_ }); $r = @($f.remove | ForEach-Object { '--remove-label'; $_ })
        gh issue edit $f.number -R $repo @a @r *> $null
    }
    "#$($f.number) +[$($f.add -join ',')] -[$($f.remove -join ',')]"
}
$verb = if ($DryRun) { 'would fix ' } else { 'fixed ' }
Write-Output "labels: $(if ($out) { $verb + ($out -join '; ') } else { 'ok' })"
