# Checks a project is ready for the factory loop and creates its GitHub labels. Run after editing project.ps1; read-only except labels.
# -Register also registers the scheduled task (register-tick-task.ps1; needs an elevated shell).
# Usage: setup.ps1 [-Register]
param([switch]$Register)
$env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'safe.directory'; $env:GIT_CONFIG_VALUE_0 = '*'
. "$PSScriptRoot/project.ps1"
$bad = 0
function Check($ok, $text) { Write-Output ("{0}  {1}" -f $(if ($ok) { 'ok  ' } else { 'FAIL' }), $text); if (-not $ok) { $script:bad++ } }
Check (Test-Path "$root\.git") "checkout at $root"
Check ([bool](Get-Command gh -ErrorAction SilentlyContinue)) 'gh installed'
gh auth status *> $null; Check ($LASTEXITCODE -eq 0) 'gh logged in'
gh repo view $repo --json name *> $null; Check ($LASTEXITCODE -eq 0) "repo $repo reachable"
Check ((Test-Path "$root\.factory\review.md") -and (Test-Path "$root\.factory\handoff.md")) 'review.md and handoff.md present (edit them for this project)'
Check ([bool]((Get-Command claude -ErrorAction SilentlyContinue) -or (wsl.exe -e bash -lc 'command -v claude' 2>$null))) 'claude CLI for the review run'
Write-Output ("info  Cursor agent: " + $(if (Test-Path "$env:LOCALAPPDATA\cursor-agent\agent.cmd") { 'found' } else { 'not found (ready issues go unstarted)' }))
Write-Output ("info  DeepSeek key: " + $(if ([Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')) { 'set' } else { 'DEEPSEEK_API_KEY not set (issues pinned to DeepSeek go unstarted)' }))
if (-not $bad) {
    foreach ($l in 'architect', 'implement', 'review', 'later', 'owner-check') { gh label create $l -R $repo --force *> $null }
    Write-Output 'ok    labels architect, implement, review, later, owner-check exist'
}
if ($Register -and -not $bad) { & "$PSScriptRoot\register-tick-task.ps1" } elseif ($Register) { Write-Output 'not registering: fix the FAIL lines first' }
else { Write-Output "next: tick.ps1 -DryRun, then register-tick-task.ps1 (or setup.ps1 -Register)" }
exit $bad
