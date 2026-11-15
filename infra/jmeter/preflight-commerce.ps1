<#
.SYNOPSIS
  Pre-load checks: readiness, sample tokens, post visibility, campaign OPEN, CSV row counts.
  Aborts the formal run when data is not ready (do not count seed time in the load window).
#>
#Requires -Version 7
param(
  [Parameter(Mandatory)][string]$MetaJson,
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$SampleUsers = 5,
  [int]$MinBuyers = 1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$m) { throw "PREFLIGHT_FAIL: $m" }

if (-not (Test-Path $MetaJson)) { Fail "missing meta $MetaJson" }
$meta = Get-Content $MetaJson -Raw | ConvertFrom-Json
$csv = [string]$meta.buyersCsv
if (-not $csv -or -not (Test-Path $csv)) { Fail "buyersCsv missing: $csv" }

$h = $null
try { $h = Invoke-RestMethod "$ApiBase/actuator/health/readiness" -TimeoutSec 5 } catch { Fail "readiness unreachable: $_" }
if ($h.status -ne 'UP') { Fail "readiness=$($h.status)" }

$lines = @(Get-Content $csv | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' })
if ($lines.Count -lt $MinBuyers) { Fail "buyers CSV rows=$($lines.Count) < min=$MinBuyers" }

$campaignId = [long]$meta.campaignId
$postId = [long]$meta.postId
$authorTok = [string]$meta.authorToken
if (-not $authorTok) { Fail 'meta.authorToken required for campaign check' }
$HA = @{ Authorization = "Bearer $authorTok" }

$camp = $null
try {
  $camp = Invoke-RestMethod -Headers $HA "$ApiBase/api/materials/$postId/sale-campaigns/current" -TimeoutSec 15
} catch { Fail "campaign current failed: $($_.ErrorDetails.Message)" }
if (-not $camp -or $camp.id -ne $campaignId) { Fail "campaign mismatch id=$($camp.id) expected=$campaignId" }
if ($camp.state -ne 'OPEN') { Fail "campaign state=$($camp.state) expected OPEN" }

$checked = 0
foreach ($line in ($lines | Select-Object -First $SampleUsers)) {
  $parts = $line.Split(',',3)
  if ($parts.Count -lt 3) { Fail "bad CSV line: $line" }
  $tok = $parts[1]
  $HB = @{ Authorization = "Bearer $tok" }
  try {
    $vis = Invoke-RestMethod -Headers $HB "$ApiBase/api/materials/$postId" -TimeoutSec 15
    if (-not $vis.postId -and -not $vis.id) { Fail "material not visible for sampled token" }
  } catch { Fail "visibility sample failed: $($_.ErrorDetails.Message)" }
  try {
    $c2 = Invoke-RestMethod -Headers $HB "$ApiBase/api/materials/$postId/sale-campaigns/current" -TimeoutSec 15
    if ($c2.state -ne 'OPEN') { Fail "buyer sees campaign state=$($c2.state)" }
  } catch { Fail "buyer campaign sample failed: $($_.ErrorDetails.Message)" }
  $checked++
}

$result = [pscustomobject]@{
  ok = $true
  buyers = $lines.Count
  sampled = $checked
  postId = $postId
  campaignId = $campaignId
  campaignState = $camp.state
  remainingStock = $camp.remainingStock
  stockCap = $camp.stockCap
}
Write-Host ($result | ConvertTo-Json -Compress)
return $result
