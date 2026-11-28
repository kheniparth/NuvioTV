# Keeps the Projects board honest with no model call: a card whose issue is closed leaves its column.
# Closed as completed -> Done; closed as not planned / duplicate -> card removed. Pinned owner issues are skipped.
# Why: review.md step 3c did this only when a model review was due, so cards of work merged by other threads stayed purple.
# Get-BoardFixes is pure (tested in tests/test_factory_board_sync.py). Usage: sync-board.ps1 [-DryRun] [-Test <json>]
param([switch]$DryRun, [string]$Test)
$ErrorActionPreference = 'Stop'
$env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'safe.directory'; $env:GIT_CONFIG_VALUE_0 = '*'

# items: @{ id; status; number; state; reason } -> @{ id; number; action = 'done'|'remove' } for closed cards not yet Done.
function Get-BoardFixes($items, $pinned) {
    foreach ($i in $items) {
        if ($i.state -notin 'CLOSED', 'MERGED' -or $i.number -in $pinned) { continue }
        if ($i.reason -in 'NOT_PLANNED', 'DUPLICATE') { @{ id = $i.id; number = $i.number; action = 'remove' } }
        elseif ($i.status -ne 'Done') { @{ id = $i.id; number = $i.number; action = 'done' } }
    }
}

if ($Test) {
    $in = Get-Content $Test -Raw | ConvertFrom-Json
    ConvertTo-Json -InputObject @(Get-BoardFixes $in.items $in.pinned) -Compress
    return
}

. "$PSScriptRoot/project.ps1"
$doneId = $board.done; $fieldId = $board.field; $projectId = $board.id
$owner, $name = $repo -split '/'
$q = 'query($o:String!,$c:String){user(login:$o){projectV2(number:' + $board.number + '){items(first:100,after:$c){pageInfo{hasNextPage endCursor} nodes{id fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}} content{... on Issue{number state stateReason} ... on PullRequest{number state}}}}}}}'
$items = @(); $cur = $null
do {
    $args2 = @('api', 'graphql', '-f', ('query=' + $q.Replace('"', '\"')), '-f', "o=$owner"); if ($cur) { $args2 += @('-f', "c=$cur") }
    $raw = gh @args2; if ($LASTEXITCODE) { throw 'gh graphql failed' }
    $p = ($raw | ConvertFrom-Json).data.user.projectV2.items
    foreach ($n in $p.nodes) { if ($n.content.number) { $items += @{ id = $n.id; status = $n.fieldValueByName.name; number = $n.content.number; state = $n.content.state; reason = $n.content.stateReason } } }
    $cur = $p.pageInfo.endCursor
} while ($p.pageInfo.hasNextPage)

$fixes = @(Get-BoardFixes $items $pinned); $log = @()
foreach ($f in $fixes) {
    if ($DryRun) { $log += "would $($f.action) #$($f.number)"; continue }
    if ($f.action -eq 'done') { gh project item-edit --project-id $projectId --id $f.id --field-id $fieldId --single-select-option-id $doneId | Out-Null }
    else { gh project item-delete $board.number --owner $owner --id $f.id | Out-Null }
    $log += "$($f.action) #$($f.number)"
}
if ($log) { Write-Output "board: $($log -join ', ')" } else { Write-Output 'board: nothing to fix' }
