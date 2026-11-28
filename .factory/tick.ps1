# Compatibility shim: the scheduled task (nuvio-tv-tick) still points here. The real script is router.ps1.
param([switch]$DryRun, [switch]$ReviewNow)
& "$PSScriptRoot/router.ps1" @PSBoundParameters
