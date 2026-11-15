<#
.SYNOPSIS
  Fixed window: count unique GIVEAWAY_AUTO refunds in MySQL after purchase→giveaway win.
  Payment/refund simulated. Uses real MySQL + giveaway award path.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$Buyers = 8,
  [string]$EvidenceDir = 'docs/perf-evidence/commerce/jmeter',
  [string]$MysqlContainer = 'content-mysql-1',
  [string]$MysqlUser = 'content',
  [string]$MysqlPassword = '11111',
  [string]$MysqlDb = 'content',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [int]$PollSeconds = 120
)

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
  $p=@{Method=$Method;Uri=$Url;Headers=$Headers;TimeoutSec=90}
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

# Do not name this $buyers — StrictMode collides with [int]$Buyers (case-insensitive).
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
  $buyerList.Add($b) | Out-Null
}

# Create like-only giveaway with PURCHASE eligibility if API supports; else document IT-only.
# Prefer POST giveaways via materials assistant-compatible path used in demos.
$t0=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$sw=[Diagnostics.Stopwatch]::StartNew()
$autoCount = 0
$note = $null
try {
  # Force-refund path as GIVEAWAY_AUTO is only created by award lock — use demo-compatible giveaway create.
  $deadlineMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 5000
  $gv = Invoke-Json POST "$ApiBase/api/materials/$postId/giveaways" $HA @{
    winnerCount = $Buyers
    deadlineMs = $deadlineMs
    license = 'DOWNLOAD'
    eligibility = @{ op='ALL'; children=@(@{ type='PURCHASE' }) }
  }
  $gid = $gv.id
  foreach ($b in $buyerList) {
    Invoke-Json PUT "$ApiBase/api/posts/$postId/likes" @{Authorization="Bearer $($b.token)"} | Out-Null
  }
  Start-Sleep -Seconds 6
  # Seal/draw via ops if exposed; otherwise poll refunds
  $dl=(Get-Date).AddSeconds($PollSeconds)
  while ((Get-Date) -lt $dl) {
    $autoCount = [int](Sql "SELECT COUNT(*) FROM material_refund_requests WHERE reason_kind='GIVEAWAY_AUTO' AND state='SUCCEEDED' AND resolved_at_ms>=$t0")
    if ($autoCount -ge 1) { break }
    Start-Sleep -Milliseconds 500
  }
} catch {
  $note = "giveaway API path unavailable or failed: $($_.Exception.Message); use MaterialCommerceRefundBlacklistIT + mq-demo as correctness evidence"
  $autoCount = [int](Sql "SELECT COUNT(*) FROM material_refund_requests WHERE reason_kind='GIVEAWAY_AUTO' AND state='SUCCEEDED' AND resolved_at_ms>=$t0")
}
$sw.Stop()
$wall=[Math]::Max(0.001,$sw.Elapsed.TotalSeconds)
$tps=[Math]::Round($autoCount / $wall, 2)
$notifies=[int](Sql "SELECT COUNT(*) FROM notifications WHERE kind='ORDER_REFUNDED' AND created_at_ms>=$t0")

$report=[ordered]@{
  runId=$runId; postId=$postId; campaignId=$camp.id
  giveawayAutoRefund = @{
    uniqueSucceededRows=$autoCount
    wallSec=[Math]::Round($wall,3)
    tps=$tps
    measurement='COUNT SUCCEEDED GIVEAWAY_AUTO refunds resolved in window (MySQL)'
    notificationsInWindow=$notifies
    note=$note
  }
  boundaries=@{payment='simulated';refund='simulated';ffmpegContainerCleanup='unverified'}
}
$report | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $out 'summary.json') -Encoding utf8
Write-Host ($report | ConvertTo-Json -Depth 6)
if ($autoCount -lt 1) { Write-Warning 'giveaway auto-refund sample insufficient' }
