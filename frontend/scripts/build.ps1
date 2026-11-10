# Windows PowerShell: production build (no WSL).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot\..
if (-not (Test-Path node_modules)) { pnpm install }
pnpm build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Build OK. Start with: pnpm start  (PORT=5314 via cross-env)"
