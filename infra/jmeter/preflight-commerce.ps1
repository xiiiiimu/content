<#
.SYNOPSIS
  Pre-load checks: readiness, sample authenticated campaign reads, campaign OPEN, CSV row counts.
  Aborts the formal run when data is not ready (do not count seed time in the load window).
#>
#Requires -Version 7
param(
  [Parameter(Mandatory)][string]$MetaJson,
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$SampleUsers = 5,
  [int]$MinBuyers = 1,
  [int]$MinTokenLifetimeSec = 300
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

# Payload decoding is only an expiry guard; sampled authenticated APIs still verify signatures.
function Assert-FreshToken([string]$Token, [string]$Label, [long]$ExpectedUser) {
  try {
    $payload = $Token.Split('.')[0].Replace('-','+').Replace('_','/')
    $payload = $payload.PadRight($payload.Length + ((4-$payload.Length%4)%4),'=')
    $identity = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
    if ([long]$identity.reader -ne $ExpectedUser -or [int]$identity.version -ne 1) { Fail "$Label token identity mismatch" }
    $remaining = ([long]$identity.expiresAt - [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 1000
    if ($remaining -lt $MinTokenLifetimeSec) { Fail "$Label token expired or insufficient lifetime; refresh test login before running" }
  } catch { Fail "$Label token invalid or insufficient lifetime; refresh test login before running" }
}
Assert-FreshToken ([string]$meta.authorToken) 'author' ([long]$meta.authorUserId)
$seenUsers = [Collections.Generic.HashSet[long]]::new()
$seenKeys = [Collections.Generic.HashSet[string]]::new()
foreach ($buyer in (Import-Csv $csv)) {
  if (-not $seenUsers.Add([long]$buyer.userId) -or -not $seenKeys.Add([string]$buyer.clientKey)) { Fail 'duplicate buyer or client key' }
  Assert-FreshToken ([string]$buyer.token) 'buyer' ([long]$buyer.userId)
}

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
  if ($parts.Count -lt 3) { Fail 'bad CSV row (contents redacted)' }
  $tok = $parts[1]
  $HB = @{ Authorization = "Bearer $tok" }
  # Preview/follow/share authorization does not govern purchasing. This real
  # authenticated API validates signatures without introducing that old gate.
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
