<#
.SYNOPSIS
  1) Hold settle barrier → blacklist reserved buyer → RELEASE → BLACKLISTED + second buyer takes last seat.
  2) fail-after-commit-once → MQ redelivery → one order / one entitlement.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [string]$EvidenceDir = 'docs/perf-evidence/commerce',
  [string]$MysqlContainer = 'content-mysql-1',
  [string]$MysqlUser = 'content',
  [string]$MysqlPassword = '11111',
  [string]$MysqlDb = 'content',
  [int]$PollSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runId = Get-Date -Format 'yyyyMMddHHmmss'
$ev = Join-Path $EvidenceDir "correctness-barrier-redelivery-$runId"
New-Item -ItemType Directory -Force -Path $ev | Out-Null
$log = Join-Path $ev 'run.log'

function Log([string]$m) {
  $line = '[{0}] {1}' -f (Get-Date -Format o), $m
  $line | Tee-Object -FilePath $log -Append | Write-Host
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
    try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $c = $_.ErrorDetails.Message } } catch {}
  }
  if ($ExpectStatus -notcontains $code) { Fail "HTTP $code $Method $Url body=$c" }
  [pscustomobject]@{ StatusCode=$code; Data=$(if ($c) { $c|ConvertFrom-Json } else { $null }); Raw=$c }
}

function Wait-Until([string]$Name,[scriptblock]$Probe) {
  $deadline=(Get-Date).AddSeconds($PollSeconds)
  while ((Get-Date) -lt $deadline) {
    $x=& $Probe
    if ($x) { return $x }
    Start-Sleep -Milliseconds 300
  }
  Fail "Timed out waiting for $Name"
}

function Sql([string]$q) {
  $out = & docker exec -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -N "-u$MysqlUser" $MysqlDb -e $q 2>&1
  if ($LASTEXITCODE -ne 0) { Fail "mysql: $out" }
  return (($out | ForEach-Object { "$_" }) | Out-String).Trim()
}

function New-Mp4([string]$OutFile) {
  $dir = Split-Path -Parent $OutFile; $name = Split-Path -Leaf $OutFile
  $docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') {
    'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
  } else { 'docker' }
  & $docker run --rm -v "${dir}:/work" -w /work --entrypoint ffmpeg $FfmpegImage `
    -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p $name | Out-Null
  if (-not (Test-Path $OutFile)) { Fail "ffmpeg fixture failed" }
}

function New-PublishedMaterial([hashtable]$HA,[string]$Title) {
  $work = Join-Path $env:TEMP "corr-$runId-$([guid]::NewGuid().ToString('N').Substring(0,8))"
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  $mp4 = Join-Path $work 'v1.mp4'
  New-Mp4 $mp4
  $meta = @{ title=$Title; description='corr'; tags=@('corr'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
  $mat = (Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item $mp4 } -TimeoutSec 180).Content | ConvertFrom-Json
  $postId = [long]$mat.postId
  Wait-Until "READY $postId" {
    $d = (Invoke-Json GET "$ApiBase/api/materials/$postId" $HA).Data
    if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { $d } else { $null }
  } | Out-Null
  $pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
  Invoke-Json POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion = 0 } | Out-Null
  return $postId
}

Log "START correctness barrier+redelivery runId=$runId"
Wait-Until 'readiness' {
  try { $h = Invoke-RestMethod "$ApiBase/actuator/health/readiness" -TimeoutSec 3; if ($h.status -eq 'UP') { $h } } catch { $null }
} | Out-Null

# Prove fault-hooks endpoints exist
Invoke-Json POST "$ApiBase/api/sale-debug/hold-settle" @{} @{ arm = $false } | Out-Null
Log "fault-hooks endpoints reachable"

$author = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="br_a_$runId"; password='password-a1' }).Data
$buyer1 = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="br_b1_$runId"; password='password-b1' }).Data
$buyer2 = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="br_b2_$runId"; password='password-b1' }).Data
$HA = @{ Authorization = "Bearer $($author.token)" }
$HB1 = @{ Authorization = "Bearer $($buyer1.token)" }
$HB2 = @{ Authorization = "Bearer $($buyer2.token)" }
Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" $HB1 | Out-Null
Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" $HB2 | Out-Null

# ---- A: barrier blacklist then resell last seat ----
Log "A: hold-settle barrier + blacklist + resell"
$postA = New-PublishedMaterial $HA "barrier $runId"
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campA = (Invoke-Json POST "$ApiBase/api/materials/$postA/sale-campaigns" $HA @{
  priceCents=100; stockCap=1; startMs=($now-1000); endMs=($now+3600000)
}).Data
Invoke-Json POST "$ApiBase/api/sale-debug/hold-settle" @{} @{ arm = $true } | Out-Null
$p1 = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($campA.id)/purchase" $HB1 @{ clientKey = "barrier-$($buyer1.userId)" }).Data
if ($p1.state -ne 'PROCESSING') { Fail "expected PROCESSING under hold, got $($p1.state)" }
Log "buyer1 reserved reservationId=$($p1.reservationId) state=PROCESSING (held)"
Invoke-Json PUT "$ApiBase/api/merchants/me/blacklist/$($buyer1.userId)" $HA @{ reason = 'corr-hold' } | Out-Null
Log "buyer1 blacklisted while held"
Invoke-Json POST "$ApiBase/api/sale-debug/hold-settle" @{} @{ arm = $false } | Out-Null
$term1 = Wait-Until 'buyer1 REJECTED BLACKLISTED' {
  $q = (Invoke-Json GET "$ApiBase/api/purchase-requests/$($p1.reservationId)" $HB1).Data
  if ($q.state -eq 'REJECTED' -and $q.rejectReason -eq 'BLACKLISTED') { $q } else { $null }
}
Log "buyer1 terminal=$($term1.state)/$($term1.rejectReason)"
$campView = (Invoke-Json GET "$ApiBase/api/materials/$postA/sale-campaigns/current" $HA).Data
Log "after release redis remaining=$($campView.remainingStock) sold=$($campView.soldCount)"
$p2 = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($campA.id)/purchase" $HB2 @{ clientKey = "barrier-$($buyer2.userId)" }).Data
$term2 = Wait-Until 'buyer2 SUCCEEDED' {
  $rid = if ($p2.reservationId) { $p2.reservationId } else { $null }
  if (-not $rid) { return $null }
  $q = (Invoke-Json GET "$ApiBase/api/purchase-requests/$rid" $HB2).Data
  if ($q.state -eq 'SUCCEEDED') { $q } else { $null }
}
$soldA = [int](Sql "SELECT sold_count FROM material_sale_campaigns WHERE id=$($campA.id)")
$paidA = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($campA.id) AND state='PAID'")
$entsA = [int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$($campA.id) AND e.state='ACTIVE'")
$rejA = Sql "SELECT reject_reason FROM material_purchase_requests WHERE reservation_id='$($p1.reservationId)'"
$ownerA = Sql "SELECT user_id FROM material_orders WHERE campaign_id=$($campA.id) AND state='PAID'"
$remA = (Invoke-Json GET "$ApiBase/api/materials/$postA/sale-campaigns/current" $HA).Data.remainingStock
if ($rejA -ne 'BLACKLISTED') { Fail "expected BLACKLISTED got $rejA" }
if ($soldA -ne 1 -or $paidA -ne 1 -or $entsA -ne 1) { Fail "A reconcile sold=$soldA paid=$paidA ents=$entsA" }
if ([long]$ownerA -ne [long]$buyer2.userId) { Fail "A owner=$ownerA expected $($buyer2.userId)" }
if ([int]$remA -ne 0) { Fail "A redis remaining=$remA expected 0" }
Log "A GREEN sold=$soldA paid=$paidA ents=$entsA owner=$ownerA rem=$remA"

# ---- B: fail-after-commit pre-ACK redelivery ----
Log "B: fail-after-commit-once + MQ redelivery idempotency"
$buyer3 = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="br_b3_$runId"; password='password-b1' }).Data
$HB3 = @{ Authorization = "Bearer $($buyer3.token)" }
Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" $HB3 | Out-Null
$postB = New-PublishedMaterial $HA "redelivery $runId"
$now2 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campB = (Invoke-Json POST "$ApiBase/api/materials/$postB/sale-campaigns" $HA @{
  priceCents=100; stockCap=1; startMs=($now2-1000); endMs=($now2+3600000)
}).Data
Invoke-Json POST "$ApiBase/api/sale-debug/fail-after-commit-once" @{} @{} | Out-Null
$p3 = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($campB.id)/purchase" $HB3 @{ clientKey = "redeliver-$($buyer3.userId)" }).Data
$term3 = Wait-Until 'buyer3 SUCCEEDED after redelivery' {
  $q = (Invoke-Json GET "$ApiBase/api/purchase-requests/$($p3.reservationId)" $HB3).Data
  if ($q.state -eq 'SUCCEEDED') { $q } else { $null }
}
Start-Sleep -Seconds 3  # allow any redelivery settle to finish
$soldB = [int](Sql "SELECT sold_count FROM material_sale_campaigns WHERE id=$($campB.id)")
$paidB = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($campB.id)")
$entsB = [int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$($campB.id) AND e.state='ACTIVE'")
$receipts = [int](Sql "SELECT COUNT(*) FROM material_sale_consume_receipts WHERE reservation_id='$($p3.reservationId)'")
if ($soldB -ne 1 -or $paidB -ne 1 -or $entsB -ne 1) { Fail "B oversell/dup sold=$soldB paid=$paidB ents=$entsB" }
Log "B GREEN sold=$soldB paid=$paidB ents=$entsB receipts=$receipts reservation=$($p3.reservationId) state=$($term3.state)"

$summary = [ordered]@{
  runId = $runId
  A_barrier_blacklist_resell = @{
    rejectReason = $rejA; sold = $soldA; paid = $paidA; entitlements = $entsA
    ownerUserId = [long]$ownerA; redisRemaining = [int]$remA; ok = $true
  }
  B_fail_after_commit_redelivery = @{
    sold = $soldB; paid = $paidB; entitlements = $entsB; receipts = $receipts
    reservationId = $p3.reservationId; ok = $true
  }
  boundaries = @{ payment='simulated'; refund='simulated'; ffmpegContainerCleanup='unverified' }
}
$summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $ev 'summary.json') -Encoding utf8
Log "GREEN evidence=$ev"
Write-Host ($summary | ConvertTo-Json -Depth 6)
