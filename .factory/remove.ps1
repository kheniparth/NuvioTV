# Takes the factory out of a project: unregisters the scheduled task and deletes its state and history. Prints what it would do unless -Yes.
# Does not stop a worker that is running and does not touch worktrees or GitHub labels; -DeleteFolder also deletes this .factory folder (commit that through a PR).
# Usage: remove.ps1 [-Yes] [-DeleteFolder]
param([switch]$Yes, [switch]$DeleteFolder)
. "$PSScriptRoot/project.ps1"
$todo = @()
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) { $todo += @{ t = "unregister scheduled task $taskName"; a = { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false } } }
if (Test-Path $state) { $todo += @{ t = "delete state folder $state"; a = { Remove-Item $state -Recurse -Force } } }
if (Test-Path $history) { $todo += @{ t = "delete history $history"; a = { Remove-Item $history -Force } } }
if ($DeleteFolder) { $todo += @{ t = "delete $PSScriptRoot"; a = { Remove-Item $PSScriptRoot -Recurse -Force } } }
if (-not $todo) { Write-Output 'nothing to remove'; exit 0 }
foreach ($x in $todo) { Write-Output ($(if ($Yes) { 'doing: ' } else { 'would: ' }) + $x.t); if ($Yes) { & $x.a } }
if (-not $Yes) { Write-Output 'rerun with -Yes to do it' }
else { Write-Output 'Check for leftover worktrees: git worktree list' }
