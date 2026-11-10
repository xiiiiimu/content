# Windows PowerShell: run frontend unit tests.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot\..
if (-not (Test-Path node_modules)) { pnpm install }
pnpm test
exit $LASTEXITCODE
