<#
.SYNOPSIS
  RETIRED: historical automatic-refund TPS scenario, preserved for interpreting old evidence.
  POST /api/sale-debug/giveaway-auto-refund — not the non-existent /api/materials/{post}/giveaways.
  Smoke: 1 refund + 1 notification, winners keep GIVEAWAY entitlement.
  Formal: MySQL SUCCEEDED GIVEAWAY_AUTO commits; steady = commits / (max-min resolved_at); full = commits / wall.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$Buyers = 20,
  [string]$EvidenceDir = 'docs/perf-evidence/commerce/jmeter',
  [string]$MysqlContainer = 'content-mysql-1',
  [string]$MysqlUser = 'content',
  [string]$MysqlPassword = '11111',
  [string]$MysqlDb = 'content',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [switch]$SmokeOnly
)

throw 'Retired scenario: awards no longer refund automatically. Use buyer-requested GIVEAWAY_REFUND plus the existing merchant approval flow; historical evidence is preserved.'

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runId = Get-Date -Format 'yyyyMMddHHmmss'
$out = Join-Path $EvidenceDir "tps-giveaway-auto-$runId"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Sql([string]$q) {
  $o = & docker exec -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -N "-u$MysqlUser" $MysqlDb -e $q 2>&1
  if ($LASTEXITCODE -ne 0) { throw "mysql $o" }
  return (($o | ForEach-Object {"$_"}) | Out-String).Trim()
}
function Invoke-Json($Method,$Url,$Headers=@{},$Body=$null) {
  $p=@{Method=$Method;Uri=$Url;Headers=$Headers;TimeoutSec=180}
  if ($null -ne $Body) { $p.ContentType='application/json'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  return ((Invoke-WebRequest @p).Content | ConvertFrom-Json)
}

$suf = -join ((97..122)|Get-Random -Count 4|%{ [char]$_ })
$author = Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="gwa$suf"; password='password-a1' }
$HA = @{ Authorization = "Bearer $($author.token)" }
$work = Join-Path $env:TEMP "gwa-$runId"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') { 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' } else { 'docker' }
& $docker run --rm -v "${work}:/work" -w /work --entrypoint ffmpeg $FfmpegImage -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p v1.mp4 | Out-Null
$meta = @{ title="gwa $runId"; description='gwa'; tags=@('gwa'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$mat = (Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item $mp4 } -TimeoutSec 180).Content | ConvertFrom-Json
$postId = [long]$mat.postId
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
  $d = Invoke-Json GET "$ApiBase/api/materials/$postId" $HA
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { break }
  Start-Sleep -Milliseconds 400
}
$pub=$HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion=0 } | Out-Null
$now=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$camp = Invoke-Json POST "$ApiBase/api/materials/$postId/sale-campaigns" $HA @{
  priceCents=100; stockCap=$Buyers; startMs=($now-1000); endMs=($now+3600000)
}

$buyerList = [System.Collections.Generic.List[object]]::new()
for ($i=0; $i -lt $Buyers; $i++) {
  $b = Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="gwb$suf$i"; password='password-b1' }
  $ok=$false
  for ($t=0; $t -lt 5 -and -not $ok; $t++) {
    try { Invoke-WebRequest -Method Put -Uri "$ApiBase/api/follows/$($author.userId)" -Headers @{Authorization="Bearer $($b.token)"} | Out-Null; $ok=$true }
    catch { Start-Sleep -Milliseconds (200*($t+1)) }
  }
  if (-not $ok) { throw "follow failed" }
  $H=@{Authorization="Bearer $($b.token)"}
  $p = Invoke-Json POST "$ApiBase/api/sale-campaigns/$($camp.id)/purchase" $H @{ clientKey="gwa-$($b.userId)" }
  $dl=(Get-Date).AddSeconds(90)
  while ($p.state -eq 'PROCESSING' -and (Get-Date) -lt $dl) {
    Start-Sleep -Milliseconds 200
    $p = Invoke-Json GET "$ApiBase/api/purchase-requests/$($p.reservationId)" $H
  }
  if ($p.state -ne 'SUCCEEDED') { throw "purchase failed $($p.state)/$($p.rejectReason)" }
  try { Invoke-Json PUT "$ApiBase/api/posts/$postId/likes" $H | Out-Null } catch {}
  $buyerList.Add($b) | Out-Null
}

# --- Smoke: winnerCount=1 → one GIVEAWAY_AUTO refund + notification; winner keeps GIVEAWAY entitlement ---
$smokeT0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$smoke = Invoke-Json POST "$ApiBase/api/sale-debug/giveaway-auto-refund" $HA @{
  postId = $postId
  winnerCount = 1
  deadlineMs = ($smokeT0 + 2000)
  eligibility = 'PURCHASE'
}
$smokeT1 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$smokeRefunds = [int](Sql "SELECT COUNT(*) FROM material_refund_requests WHERE reason_kind='GIVEAWAY_AUTO' AND state='SUCCEEDED' AND resolved_at_ms>=$smokeT0 AND resolved_at_ms<=$smokeT1")
$smokeNotify = [int](Sql "SELECT COUNT(*) FROM notifications WHERE kind='ORDER_REFUNDED' AND created_at_ms>=$smokeT0 AND created_at_ms<=$smokeT1")
$smokeWinEnt = [int](Sql "SELECT COUNT(*) FROM material_entitlements WHERE source_kind='GIVEAWAY' AND source_id=$($smoke.giveawayId) AND state='ACTIVE'")
if ($smokeRefunds -lt 1 -or $smokeNotify -lt 1 -or $smokeWinEnt -lt 1) {
  throw "smoke failed refunds=$smokeRefunds notify=$smokeNotify winEnt=$smokeWinEnt"
}
Write-Host "SMOKE_OK refunds=$smokeRefunds notify=$smokeNotify winEnt=$smokeWinEnt"
if ($SmokeOnly) {
  $report=[ordered]@{ runId=$runId; smokeOnly=$true; smoke=@{refunds=$smokeRefunds;notifications=$smokeNotify;giveawayEntitlements=$smokeWinEnt;giveawayId=$smoke.giveawayId} }
  $report | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $out 'summary.json') -Encoding utf8
  Write-Host ($report | ConvertTo-Json -Depth 6)
  Write-Host "GIVEAWAY_TPS_OUT=$out"
  return
}

# Remaining PAID orders for formal window (smoke already refunded 1)
$remainingPaid = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($camp.id) AND state='PAID'")
$winners = [Math]::Max(1, $remainingPaid)
$tWall0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$gv = Invoke-Json POST "$ApiBase/api/sale-debug/giveaway-auto-refund" $HA @{
  postId = $postId
  winnerCount = $winners
  deadlineMs = ($tWall0 + 2500)
  eligibility = 'PURCHASE'
}
$tWall1 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$wallSec = [Math]::Max(0.001, ($tWall1 - $tWall0) / 1000.0)

$autoCount = [int](Sql "SELECT COUNT(*) FROM material_refund_requests WHERE reason_kind='GIVEAWAY_AUTO' AND state='SUCCEEDED' AND resolved_at_ms>=$tWall0 AND resolved_at_ms<=$tWall1")
$spanRow = Sql "SELECT MIN(resolved_at_ms),MAX(resolved_at_ms) FROM material_refund_requests WHERE reason_kind='GIVEAWAY_AUTO' AND state='SUCCEEDED' AND resolved_at_ms>=$tWall0 AND resolved_at_ms<=$tWall1"
$parts = @($spanRow -split '\t| ')
$minR = if ($parts.Count -ge 2 -and $parts[0] -match '^\d+$') { [long]$parts[0] } else { $tWall0 }
$maxR = if ($parts.Count -ge 2 -and $parts[1] -match '^\d+$') { [long]$parts[1] } else { $tWall1 }
$commitSpanSec = [Math]::Max(0.001, ($maxR - $minR) / 1000.0)
if ($autoCount -le 1) { $commitSpanSec = [Math]::Max(0.001, $wallSec) } # single commit: use wall for rate floor note
$steadyTps = [Math]::Round($autoCount / $commitSpanSec, 2)
$fullTps = [Math]::Round($autoCount / $wallSec, 2)
$notifies = [int](Sql "SELECT COUNT(*) FROM notifications WHERE kind='ORDER_REFUNDED' AND created_at_ms>=$tWall0 AND created_at_ms<=$tWall1")
$winEnts = [int](Sql "SELECT COUNT(*) FROM material_entitlements WHERE source_kind='GIVEAWAY' AND source_id=$($gv.giveawayId) AND state='ACTIVE'")
$purchaseEntsLeft = [int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$($camp.id) AND e.state='ACTIVE'")
$refundedOrders = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($camp.id) AND state='REFUNDED' AND refund_reason='GIVEAWAY_AUTO'")

$report=[ordered]@{
  runId=$runId; postId=$postId; campaignId=$camp.id; giveawayId=$gv.giveawayId
  route='POST /api/sale-debug/giveaway-auto-refund (fault-hooks); replaces 404 /api/materials/{post}/giveaways'
  smoke = [ordered]@{
    refunds=$smokeRefunds; notifications=$smokeNotify; giveawayEntitlementsActive=$smokeWinEnt
    giveawayId=$smoke.giveawayId; ok=$true
  }
  giveawayAutoRefund = [ordered]@{
    measurement='COUNT SUCCEEDED GIVEAWAY_AUTO refunds with resolved_at_ms in wall [tWall0,tWall1]; steady uses min..max resolved_at span'
    t0Ms=$tWall0; t1Ms=$tWall1
    windowExactSec=[Math]::Round($wallSec,3)
    commitSpanSec=[Math]::Round($commitSpanSec,3)
    minResolvedAtMs=$minR; maxResolvedAtMs=$maxR
    commitsInWindow=$autoCount
    steadyTps=$steadyTps
    fullTpsIncludingWait=$fullTps
    sampleSize=$autoCount
    sampleInsufficient=($autoCount -lt 5)
    notificationsInWindow=$notifies
    giveawayEntitlementsActive=$winEnts
    purchaseEntitlementsRemaining=$purchaseEntsLeft
    ordersRefundedGiveawayAuto=$refundedOrders
    note='Winners keep GIVEAWAY entitlement; PURCHASE entitlement revoked on auto-refund (IT contract). Wall includes deadline sleep inside debug controller.'
  }
  boundaries=@{payment='simulated';refund='simulated';ffmpegContainerCleanup='unverified'}
}
$report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $out 'summary.json') -Encoding utf8
Write-Host ($report | ConvertTo-Json -Depth 8)
if ($autoCount -lt 5) { Write-Warning "giveaway auto-refund sample small: $autoCount" }
Write-Host "GIVEAWAY_TPS_OUT=$out"
