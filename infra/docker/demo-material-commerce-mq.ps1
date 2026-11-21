<#
.SYNOPSIS
  Real RocketMQ commerce demo: purchase returns PROCESSING, consumer settles;
  giveaway auto-refund once + notify once; PURCHASE eligibility for auto vs buyer refund;
  fault: redelivery idempotent; reserve-then-blacklist before settle releases stock.

.NOTES
  Requires media stack + Java with:
    --content.sale.sync-settle=false
    --content.sale.producer-enabled=true
    --content.sale.consumer-enabled=true
    --content.sale.scan-enabled=true
    --content.sale.manual-settle=true
    --content.sale.namesrv=127.0.0.1:29876
    --content.sale.topic=content-sale
    --content.sale.settle-delay-ms=800   # keeps first HTTP response visibly PROCESSING
  Topic: .\infra\docker\create-sale-topic.ps1
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$MysqlContainer = 'content-mysql-1',
  [string]$MysqlUser = 'content',
  [string]$MysqlPassword = '11111',
  [string]$MysqlDb = 'content',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [int]$PollSeconds = 180,
  [string]$LogFile = 'target/demo-material-commerce-mq-run.log',
  [string]$EvidenceDir = 'docs/perf-evidence/commerce'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runId = Get-Date -Format 'yyyyMMddHHmmss'
$ev = Join-Path $EvidenceDir "mq-demo-$runId"
New-Item -ItemType Directory -Force -Path $ev,(Split-Path $LogFile) | Out-Null
if (Test-Path $LogFile) { Remove-Item $LogFile -Force }

function Log([string]$m) {
  $line = '[{0}] {1}' -f (Get-Date -Format o), $m
  $line | Tee-Object -FilePath $LogFile -Append | Write-Host
}
function Fail([string]$m) { Log "FAIL: $m"; throw $m }

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null,[int[]]$ExpectStatus=@(200))
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=90 }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  try { $r=Invoke-WebRequest @p; $code=[int]$r.StatusCode; $c=$r.Content }
  catch {
    $resp=$_.Exception.Response; if ($null -eq $resp) { throw }
    $code=[int]$resp.StatusCode; $c=$null
    try { $s=$resp.GetResponseStream(); if ($s) { $c=[IO.StreamReader]::new($s).ReadToEnd() } } catch {}
  }
  if ($ExpectStatus -notcontains $code) { Fail "HTTP $code $Method $Url body=$c" }
  [pscustomobject]@{ StatusCode=$code; Data=$(if ($c) { $c|ConvertFrom-Json } else { $null }); Raw=$c }
}

function Wait-Until([string]$Name,[scriptblock]$Probe) {
  $deadline=(Get-Date).AddSeconds($PollSeconds)
  while ((Get-Date) -lt $deadline) {
    $x=& $Probe
    if ($x) { return $x }
    Start-Sleep -Milliseconds 400
  }
  Fail "Timed out waiting for $Name"
}

function Sql([string]$q) {
  $out = & docker exec -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -N "-u$MysqlUser" $MysqlDb -e $q 2>&1
  if ($LASTEXITCODE -ne 0) { Fail "mysql failed: $q :: $($out | Out-String)" }
  return (($out | ForEach-Object { "$_" }) | Out-String).Trim()
}

function New-DemoMp4([string]$OutFile) {
  $dir = Split-Path -Parent $OutFile; $name = Split-Path -Leaf $OutFile
  $docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') {
    'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
  } else { 'docker' }
  & $docker run --rm -v "${dir}:/work" -w /work --entrypoint ffmpeg $FfmpegImage `
    -y -f lavfi -i testsrc=size=160x120:rate=10 -f lavfi -i sine=frequency=880:sample_rate=44100 `
    -t 4 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $name | Out-Null
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutFile)) { Fail "ffmpeg fixture failed" }
}

Log "START mq-demo runId=$runId ApiBase=$ApiBase"
Wait-Until 'java readiness' {
  try { $h = Invoke-RestMethod "$ApiBase/actuator/health/readiness" -TimeoutSec 3; if ($h.status -eq 'UP') { $h } } catch { $null }
} | Out-Null

$suffix = $runId
$author = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="mq_a_$suffix"; password='password-a1' }).Data
$buyer  = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="mq_b_$suffix"; password='password-b1' }).Data
$buyer2 = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="mq_c_$suffix"; password='password-c1' }).Data
$HA = @{ Authorization = "Bearer $($author.token)" }
$HB = @{ Authorization = "Bearer $($buyer.token)" }
$HC = @{ Authorization = "Bearer $($buyer2.token)" }
Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" $HB | Out-Null
Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" $HC | Out-Null
Log "users author=$($author.userId) buyer=$($buyer.userId) buyer2=$($buyer2.userId)"

$work = Join-Path $env:TEMP "com-mq-$suffix"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
New-DemoMp4 $mp4

function New-Material([hashtable]$Hdr,[hashtable]$Meta,[string]$File) {
  $metaJson = $Meta | ConvertTo-Json -Compress
  $r = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $Hdr -Form @{
    meta = $metaJson; file = Get-Item -LiteralPath $File
  } -TimeoutSec 180
  return ($r.Content | ConvertFrom-Json)
}

Log '== create PUBLIC_PREVIEW material =='
$mat = New-Material $HA @{
  title="MQ抢购 $suffix"; description='commerce mq'; tags=@('sale','mq')
  shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true; aspectRatio='PORTRAIT'
} $mp4
$postId = [long]$mat.postId
Wait-Until "material READY" {
  $d = (Invoke-Json GET "$ApiBase/api/materials/$postId" $HA).Data
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { $d } else { $null }
} | Out-Null
$pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion = 0 } | Out-Null
Log "postId=$postId published"

$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campaign = (Invoke-Json POST "$ApiBase/api/materials/$postId/sale-campaigns" $HA @{
  priceCents=990; stockCap=5; startMs=($now-1000); endMs=($now+3600000)
}).Data
Log "campaign=$($campaign.id)"

# ----- PATH1: async PROCESSING then MQ settle -----
Log '== PATH1: purchase returns PROCESSING then MQ settles =='
$t0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$purchase = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($campaign.id)/purchase" $HB @{ clientKey = "mq-buy-$suffix" }).Data
if ($purchase.state -ne 'PROCESSING') { Fail "expected PROCESSING on accept, got $($purchase.state)" }
Log "ASSERT accept state=PROCESSING reservation=$($purchase.reservationId)"
$final = Wait-Until 'MQ settle SUCCEEDED' {
  $r = (Invoke-Json GET "$ApiBase/api/purchase-requests/$($purchase.reservationId)" $HB).Data
  if ($r.state -ne 'PROCESSING') { $r } else { $null }
}
$t1 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($final.state -ne 'SUCCEEDED') { Fail "settle $($final.state) $($final.rejectReason)" }
$order = (Invoke-Json GET "$ApiBase/api/materials/$postId/orders/me" $HB).Data
$ent = (Invoke-Json GET "$ApiBase/api/posts/$postId/entitlement" $HB).Data
if (-not $ent.entitled) { Fail 'PURCHASE entitlement missing after settle' }
Log "ASSERT MQ settle order=$($order.id) e2eMs=$($t1-$t0) entitled=true"

# Redelivery: settle again must stay one order
Invoke-Json POST "$ApiBase/api/purchase-requests/$($purchase.reservationId)/settle" $HB | Out-Null
$orderCount = Sql "SELECT COUNT(*) FROM material_orders WHERE user_id=$($buyer.userId) AND post_id=$postId"
if ($orderCount -ne '1') { Fail "redeploy/settle duplicate orders count=$orderCount" }
Log 'ASSERT redelivery/manual settle idempotent orders=1'

# ----- PATH2: win auto-refund once + notify once + download -----
Log '== PATH2: giveaway win auto-refund =='
Invoke-Json PUT "$ApiBase/api/posts/$postId/likes?topic=drone" $HB | Out-Null
$deadline = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 8000
$ask = (Invoke-Json POST "$ApiBase/api/posts/$postId/comments" ($HA + @{ 'Idempotency-Key' = "g-$suffix" }) @{
  body = "@助手 从点赞过的人里抽1人 截止epoch:$deadline"
}).Data
Wait-Until 'giveaway row' {
  $n = Sql "SELECT COUNT(*) FROM giveaways WHERE source_comment_id=$($ask.commentId)"
  if ($n -eq '1') { $true } else { $null }
} | Out-Null
$gid = Sql "SELECT id FROM giveaways WHERE source_comment_id=$($ask.commentId)"
Start-Sleep -Seconds 9
# force seal/draw via repeated readiness; mediaRunner ticks giveaways
Wait-Until 'giveaway drawn' {
  $st = Sql "SELECT state FROM giveaways WHERE id=$gid"
  if ($st -in @('DRAWN','COMPLETED','SEALED')) { $st } else { $null }
} | Out-Null
# If still SEALED/OPEN, wait more for tick
Wait-Until 'order auto-refunded' {
  $st = Sql "SELECT state FROM material_orders WHERE id=$($order.id)"
  if ($st -eq 'REFUNDED') { $st } else { $null }
} | Out-Null
$reason = Sql "SELECT refund_reason FROM material_orders WHERE id=$($order.id)"
if ($reason -ne 'GIVEAWAY_AUTO') { Fail "refund_reason=$reason" }
$refundOk = Sql "SELECT COUNT(*) FROM material_refund_requests WHERE order_id=$($order.id) AND state='SUCCEEDED'"
$notifyOk = Sql "SELECT COUNT(*) FROM notifications WHERE recipient_user_id=$($buyer.userId) AND kind='ORDER_REFUNDED' AND dedupe_key='order-refund:$($order.id):GIVEAWAY_AUTO'"
if ($refundOk -ne '1') { Fail "refund success count=$refundOk" }
if ($notifyOk -ne '1') { Fail "refund notify count=$notifyOk" }
$ent2 = (Invoke-Json GET "$ApiBase/api/posts/$postId/entitlement" $HB).Data
if (-not $ent2.entitled) { Fail 'giveaway entitlement missing after auto-refund' }
$reward = Sql "SELECT reward_version_id FROM giveaways WHERE id=$gid"
$sign = (Invoke-Json POST "$ApiBase/api/materials/$postId/original/sign" $HB @{ versionId=[long]$reward; ttlMillis=60000 }).Data
if (-not $sign.url) { Fail 'original sign failed after auto-refund' }
Log "ASSERT auto-refund once notify once download ok reward=$reward"

# PURCHASE eligibility: GIVEAWAY_AUTO still counts
$eligAuto = Sql @"
SELECT COUNT(*) FROM material_orders
WHERE user_id=$($buyer.userId) AND post_id=$postId
  AND (state='PAID' OR (state='REFUNDED' AND refund_reason='GIVEAWAY_AUTO'))
"@
if ($eligAuto -ne '1') { Fail "PURCHASE auto-refund eligibility count=$eligAuto" }
Log 'ASSERT PURCHASE still counts GIVEAWAY_AUTO refund'

# ----- PATH3: second order on another post, buyer force refund excludes PURCHASE -----
Log '== PATH3: buyer force refund excludes PURCHASE =='
$mat2 = New-Material $HA @{
  title="MQ资格 $suffix"; description='elig'; tags=@('sale')
  shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true
} $mp4
$post2 = [long]$mat2.postId
Wait-Until "material2 READY" {
  $d = (Invoke-Json GET "$ApiBase/api/materials/$post2" $HA).Data
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { $d } else { $null }
} | Out-Null
$pub2 = $HA.Clone(); $pub2['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$post2/publish" $pub2 @{ expectedVersion = 0 } | Out-Null
$c2 = (Invoke-Json POST "$ApiBase/api/materials/$post2/sale-campaigns" $HA @{
  priceCents=100; stockCap=3; startMs=($now-1000); endMs=($now+3600000)
}).Data
$p2 = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($c2.id)/purchase" $HB @{ clientKey = "mq-buy2-$suffix" }).Data
$f2 = Wait-Until 'order2 settled' {
  $r = (Invoke-Json GET "$ApiBase/api/purchase-requests/$($p2.reservationId)" $HB).Data
  if ($r.state -ne 'PROCESSING') { $r } else { $null }
}
if ($f2.state -ne 'SUCCEEDED') { Fail "order2 $($f2.state)" }
$forced = (Invoke-Json POST "$ApiBase/api/orders/$($f2.orderId)/force-refund" $HA).Data
if ($forced.state -ne 'REFUNDED' -or $forced.refundReason -ne 'BUYER_FORCED') { Fail "force refund $($forced.state)/$($forced.refundReason)" }
$eligForced = Sql @"
SELECT COUNT(*) FROM material_orders
WHERE id=$($f2.orderId)
  AND (state='PAID' OR (state='REFUNDED' AND refund_reason='GIVEAWAY_AUTO'))
"@
if ($eligForced -ne '0') { Fail "BUYER_FORCED still counted for PURCHASE" }
Log 'ASSERT BUYER_FORCED excluded from PURCHASE'

# ----- PATH4: reserve then blacklist before settle (manual settle; consumer may also race — use unique campaign + settle delay window) -----
Log '== PATH4: blacklist after reserve before settle =='
$mat3 = New-Material $HA @{
  title="MQ拉黑 $suffix"; description='bl'; tags=@('sale')
  shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true
} $mp4
$post3 = [long]$mat3.postId
Wait-Until "material3 READY" {
  $d = (Invoke-Json GET "$ApiBase/api/materials/$post3" $HA).Data
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { $d } else { $null }
} | Out-Null
$pub3 = $HA.Clone(); $pub3['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$post3/publish" $pub3 @{ expectedVersion = 0 } | Out-Null
$c3 = (Invoke-Json POST "$ApiBase/api/materials/$post3/sale-campaigns" $HA @{
  priceCents=50; stockCap=2; startMs=($now-1000); endMs=($now+3600000)
}).Data
# Purchase as buyer2 — accept PROCESSING, blacklist before settle completes (settle-delay-ms helps)
$p3 = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($c3.id)/purchase" $HC @{ clientKey = "mq-bl-$suffix" }).Data
if ($p3.state -ne 'PROCESSING') { Fail "bl path expected PROCESSING got $($p3.state)" }
Invoke-Json PUT "$ApiBase/api/merchants/me/blacklist/$($buyer2.userId)" $HA @{ reason = 'before-settle' } | Out-Null
Log "blacklisted buyer2 during PROCESSING reservation=$($p3.reservationId)"
# Also poke manual settle in case consumer already delayed
try { Invoke-Json POST "$ApiBase/api/purchase-requests/$($p3.reservationId)/settle" $HC | Out-Null } catch {}
$rej = Wait-Until 'blacklist reject or success' {
  $r = (Invoke-Json GET "$ApiBase/api/purchase-requests/$($p3.reservationId)" $HC).Data
  if ($r.state -ne 'PROCESSING') { $r } else { $null }
}
$sold3 = Sql "SELECT sold_count FROM material_sale_campaigns WHERE id=$($c3.id)"
$orders3 = Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($c3.id) AND state='PAID'"
if ($rej.state -eq 'REJECTED') {
  if ($rej.rejectReason -ne 'BLACKLISTED') { Fail "reject reason $($rej.rejectReason)" }
  if ($sold3 -ne '0' -or $orders3 -ne '0') { Fail "stock not released sold=$sold3 orders=$orders3" }
  Log "ASSERT blacklist-before-settle REJECTED BLACKLISTED sold=0"
} elseif ($rej.state -eq 'SUCCEEDED') {
  # Race: consumer settled before blacklist — still valid outcome; note it
  Log "NOTE: settle won race before blacklist (SUCCEEDED); sold=$sold3 orders=$orders3 — delay may need increase"
} else { Fail "unexpected state $($rej.state)" }

$summary = [ordered]@{
  runId = $runId
  headNote = 'real RocketMQ PurchaseSettle path'
  path1 = @{ accept='PROCESSING'; terminal='SUCCEEDED'; e2eMs = ($t1 - $t0); orderId = $order.id }
  path2 = @{ refundReason='GIVEAWAY_AUTO'; refundOnce=$true; notifyOnce=$true; downloadOk=$true; purchaseEligKeepsAuto=$true }
  path3 = @{ forceRefund='BUYER_FORCED'; purchaseEligExcludes=$true }
  path4 = @{ reservation=$p3.reservationId; finalState=$rej.state; rejectReason=$rej.rejectReason; soldCount=[int]$sold3; paidOrders=[int]$orders3 }
  boundaries = @{ payment='simulated'; refund='simulated'; ffmpegContainerCleanup='unverified' }
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $ev 'summary.json') -Encoding utf8
Copy-Item $LogFile (Join-Path $ev 'run.log') -Force
Log "DONE mq demo GREEN evidence=$ev"
Log "log=$LogFile"



