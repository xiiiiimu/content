# Windows PowerShell: start Content frontend on port 5314 (no WSL).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot\..
if (-not (Test-Path node_modules)) { pnpm install }
pnpm dev
