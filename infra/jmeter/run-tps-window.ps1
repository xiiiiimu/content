<#
.SYNOPSIS
  Scenario C helper: measure settle / buyer-refund / giveaway-auto-refund TPS in a fixed window
  by counting distinct MySQL committed business rows (not HTTP retries / MQ redeliveries).
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$Buyers = 100,
  [int]$Stock = 100,
  [int]$WindowSec = 30,
  [string]$EvidenceDir = 'docs/perf-evidence/commerce/jmeter',
  [string]$MysqlContainer = 'content-mysql-1',
  [string]$MysqlUser = 'content',
  [string]$MysqlPassword = '11111',
  [string]$MysqlDb = 'content',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runId = Get-Date -Format 'yyyyMMddHHmmss'
$out = Join-Path $EvidenceDir "tps-window-$runId"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Sql([string]$q) {
  $o = & docker exec -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -N "-u$MysqlUser" $MysqlDb -e $q 2>&1
  if ($LASTEXITCODE -ne 0) { throw "mysql $o" }
  return (($o | ForEach-Object {"$_"}) | Out-String).Trim()
}
function Invoke-Json($Method,$Url,$Headers=@{},$Body=$null) {
  $p=@{Method=$Method;Uri=$Url;Headers=$Headers;TimeoutSec=60}
  if ($null -ne $Body) { $p.ContentType='application/json'; $p.Body=($Body|ConvertTo-Json -Compress) }
  return ((Invoke-WebRequest @p).Content | ConvertFrom-Json)
}

$author = Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="tps_a_$runId"; password='password-a1' }
$HA = @{ Authorization = "Bearer $($author.token)" }
$work = Join-Path $env:TEMP "tps-$runId"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') { 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' } else { 'docker' }
& $docker run --rm -v "${work}:/work" -w /work --entrypoint ffmpeg $FfmpegImage -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p v1.mp4 | Out-Null
$meta = @{ title="tps $runId"; description='tps'; tags=@('tps'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
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
  priceCents=100; stockCap=$Stock; startMs=($now-1000); endMs=($now+3600000)
}

$sessions = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$suffix = -join ((97..122) | Get-Random -Count 4 | ForEach-Object { [char]$_ })
0..($Buyers-1) | ForEach-Object -Parallel {
  $ApiBase=$using:ApiBase; $runId=$using:runId; $aid=$using:author.userId; $i=$_; $suffix=$using:suffix
  $uname = "tps$suffix$i"; if ($uname.Length -gt 32) { $uname = $uname.Substring(0,32) }
  $body=@{username=$uname;password='password-b1'}|ConvertTo-Json -Compress
  $s=((Invoke-WebRequest -Method Post -Uri "$ApiBase/api/auth/register" -ContentType 'application/json' -Body $body).Content|ConvertFrom-Json)
  $ok=$false
  for ($t=0; $t -lt 5 -and -not $ok; $t++) {
    try { Invoke-WebRequest -Method Put -Uri "$ApiBase/api/follows/$aid" -Headers @{Authorization="Bearer $($s.token)"} | Out-Null; $ok=$true }
    catch { Start-Sleep -Milliseconds (200*($t+1)) }
  }
  if (-not $ok) { throw "follow failed $($s.userId)" }
  ($using:sessions).Add($s) | Out-Null
} -ThrottleLimit 6

$t0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$sw = [Diagnostics.Stopwatch]::StartNew()
@($sessions) | ForEach-Object -Parallel {
  $ApiBase=$using:ApiBase; $cid=$using:camp.id; $s=$_
  $H=@{Authorization="Bearer $($s.token)"}
  $body=@{clientKey="tps-$($s.userId)"}|ConvertTo-Json -Compress
  try {
    Invoke-WebRequest -Method Post -Uri "$ApiBase/api/sale-campaigns/$cid/purchase" -Headers $H -ContentType 'application/json' -Body $body -TimeoutSec 30 | Out-Null
  } catch {}
} -ThrottleLimit 32
# wait window
while ($sw.Elapsed.TotalSeconds -lt $WindowSec) { Start-Sleep -Milliseconds 200 }
# drain remaining PROCESSING briefly
$drainSw=[Diagnostics.Stopwatch]::StartNew()
$dline=(Get-Date).AddSeconds(120)
while ((Get-Date) -lt $dline) {
  $p=Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($camp.id) AND state='PROCESSING'"
  if ($p -eq '0') { break }
  Start-Sleep -Milliseconds 300
}
$drainSw.Stop()
$t1=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$paid = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($camp.id) AND state='PAID' AND paid_at_ms>=$t0 AND paid_at_ms<=$t1")
$window = [Math]::Max(0.001, ($t1-$t0)/1000.0)
$settleTps = [Math]::Round($paid / $window, 2)

# Buyer refunds on paid orders (sample up to 20) — unique SUCCEEDED BUYER_REQUESTED rows in window
$orderIdText = Sql "SELECT id FROM material_orders WHERE campaign_id=$($camp.id) AND state='PAID' ORDER BY id LIMIT 20"
$orderIds = @($orderIdText -split "[\r\n]+" | Where-Object { $_ -match '^\d+$' })
$refundSw=[Diagnostics.Stopwatch]::StartNew()
$rf0=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$buyerRefunded=0
$sessionList = @($sessions)
foreach ($oid in $orderIds) {
  $uidText = Sql "SELECT user_id FROM material_orders WHERE id=$oid"
  if ($uidText -notmatch '^\d+$') { continue }
  $tok = $sessionList | Where-Object { [long]$_.userId -eq [long]$uidText } | Select-Object -First 1
  if (-not $tok) { continue }
  try {
    $rr = Invoke-Json POST "$ApiBase/api/orders/$oid/refund-requests" @{Authorization="Bearer $($tok.token)"}
    Invoke-Json POST "$ApiBase/api/refund-requests/$($rr.refundRequestId)/approve" $HA | Out-Null
    $buyerRefunded++
  } catch {}
}
$refundSw.Stop()
$rf1=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$buyerRefundRows=[int](Sql "SELECT COUNT(*) FROM material_refund_requests rr JOIN material_orders o ON rr.order_id=o.id WHERE o.campaign_id=$($camp.id) AND rr.state='SUCCEEDED' AND rr.reason_kind='BUYER_REQUESTED' AND rr.resolved_at_ms>=$rf0 AND rr.resolved_at_ms<=$rf1")
$buyerRefundTps=[Math]::Round($buyerRefundRows / [Math]::Max(0.001,$refundSw.Elapsed.TotalSeconds), 2)

$sold=[int](Sql "SELECT sold_count FROM material_sale_campaigns WHERE id=$($camp.id)")
$paidNow=[int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($camp.id) AND state='PAID'")
$ents=[int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$($camp.id) AND e.state='ACTIVE'")

$report=[ordered]@{
  runId=$runId; campaignId=$camp.id; windowSec=$WindowSec
  settle = @{ uniquePaidOrdersInWindow=$paid; wallSec=[Math]::Round($window,3); tps=$settleTps; drainSec=[Math]::Round($drainSw.Elapsed.TotalSeconds,3); measurement='COUNT DISTINCT PAID orders with paid_at_ms in window' }
  buyerRefund = @{ succeededRows=$buyerRefundRows; attempted=$buyerRefunded; wallSec=[Math]::Round($refundSw.Elapsed.TotalSeconds,3); tps=$buyerRefundTps; measurement='COUNT SUCCEEDED BUYER_REQUESTED refunds resolved in window' }
  giveawayAutoRefund = @{ note='see run-tps-giveaway-auto.ps1 / mq-demo evidence'; tps=$null }
  reconcile = @{ sold_count=$sold; paid_orders=$paidNow; active_purchase_ents=$ents }
  peakBacklog = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($camp.id) AND state='PROCESSING'")
  boundaries=@{payment='simulated';refund='simulated';ffmpegContainerCleanup='unverified'}
}
$report | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $out 'summary.json') -Encoding utf8
Write-Host ($report | ConvertTo-Json -Depth 6)
