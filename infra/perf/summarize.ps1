$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$dir = Join-Path $root 'target\perf-evidence'
if (-not (Test-Path $dir)) { Write-Error "No evidence under $dir"; exit 1 }
$rows = @()
Get-ChildItem $dir -Directory | ForEach-Object {
  $summary = Join-Path $_.FullName 'summary.json'
  if (Test-Path $summary) {
    $j = Get-Content $summary -Raw | ConvertFrom-Json
    $rows += [pscustomobject]@{
      Dir = $_.Name
      Mode = $j.mode
      Throughput = $j.throughputOpsPerSec
      P95 = $j.p95
      P99 = $j.p99
      Errors = $j.errors
      Ops = $j.ops
    }
  }
}
$rows | Format-Table -AutoSize
$rows | ConvertTo-Json | Set-Content (Join-Path $dir 'aggregate.json')
Write-Host "Wrote $dir\aggregate.json"
