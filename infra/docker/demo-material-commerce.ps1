<#
.SYNOPSIS
  Limited-sale commerce demo: purchase→entitlement, win auto-refund, PURCHASE eligibility, blacklist.

.NOTES
  Same stack as demo-material-assistant.ps1. Prefer Java args:
    --content.sale.sync-settle=true
  For async RocketMQ settle: create topic content-sale and enable producer/consumer.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [int]$PollSeconds = 180,
  [string]$LogFile = 'target/demo-material-commerce-run.log'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
if (Test-Path $LogFile) { Remove-Item $LogFile -Force }

function Log([string]$m) {
  $line = '[{0}] {1}' -f (Get-Date -Format o), $m
  $line | Tee-Object -FilePath $LogFile -Append | Write-Host
}
function Fail([string]$m) { Log "FAIL: $m"; throw $m }

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null,[int[]]$ExpectStatus=@(200))
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=60 }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  try { $r=Invoke-WebRequest @p; $code=[int]$r.StatusCode; $c=$r.Content }
  catch {
    $resp=$_.Exception.Response; if ($null -eq $resp) { throw }
    $code=[int]$resp.StatusCode; $c=$null
    try { $s=$resp.GetResponseStream(); if ($s) { $c=[IO.StreamReader]::new($s).ReadToEnd() } } catch {}
  }
  if ($ExpectStatus -notcontains $code) { Fail "HTTP $code $Method $Url body=$c" }
  [pscustomobject]@{ StatusCode=$code; Data=$(if ($c) { $c|ConvertFrom-Json } else { $null }) }
}

function Wait-Until([string]$Name,[scriptblock]$Probe) {
  $deadline=(Get-Date).AddSeconds($PollSeconds)
  while ((Get-Date) -lt $deadline) {
    $x=& $Probe
    if ($x) { return $x }
    Start-Sleep -Milliseconds 500
  }
  Fail "Timed out waiting for $Name"
}

function New-DemoMp4([string]$OutFile) {
  $dir = Split-Path -Parent $OutFile
  $name = Split-Path -Leaf $OutFile
  $docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') {
    'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
  } else { 'docker' }
  & $docker run --rm -v "${dir}:/work" -w /work --entrypoint ffmpeg $FfmpegImage `
    -y -f lavfi -i testsrc=size=160x120:rate=10 -f lavfi -i sine=frequency=880:sample_rate=44100 `
    -t 4 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $name
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutFile)) { Fail "ffmpeg fixture failed" }
}

$suffix = Get-Date -Format 'yyyyMMddHHmmss'
Log "START suffix=$suffix ApiBase=$ApiBase"
Wait-Until 'java readiness' {
  try { $h = Invoke-RestMethod "$ApiBase/actuator/health/readiness" -TimeoutSec 3; if ($h.status -eq 'UP') { $h } } catch { $null }
} | Out-Null

$author = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="com_a_$suffix"; password='password-a1' }).Data
$buyer  = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="com_b_$suffix"; password='password-b1' }).Data
$HA = @{ Authorization = "Bearer $($author.token)" }
$HB = @{ Authorization = "Bearer $($buyer.token)" }
Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" $HB | Out-Null
Log "users author=$($author.userId) buyer=$($buyer.userId)"

$work = Join-Path $env:TEMP "com-demo-$suffix"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
New-DemoMp4 $mp4

$meta = @{ title="限量抢购 $suffix"; description='commerce'; tags=@('sale'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$matResp = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item -LiteralPath $mp4 } -TimeoutSec 180
$mat = $matResp.Content | ConvertFrom-Json
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
  priceCents = 990; stockCap = 5; startMs = ($now - 1000); endMs = ($now + 3600000)
}).Data
Log "campaign=$($campaign.id) token=$($campaign.linkToken)"

Log '== PATH1: purchase + poll =='
$purchase = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($campaign.id)/purchase" $HB @{ clientKey = "demo-$suffix" }).Data
$final = Wait-Until 'purchase terminal' {
  $r = (Invoke-Json GET "$ApiBase/api/purchase-requests/$($purchase.reservationId)" $HB).Data
  if ($r.state -ne 'PROCESSING') { $r } else { $null }
}
if ($final.state -ne 'SUCCEEDED') { Fail "purchase $($final.state) $($final.rejectReason)" }
$order = (Invoke-Json GET "$ApiBase/api/materials/$postId/orders/me" $HB).Data
$ent = (Invoke-Json GET "$ApiBase/api/posts/$postId/entitlement" $HB).Data
Log "ASSERT order=$($order.id) PAID entitled=$($ent.entitled)"

Log '== PATH2: like + giveaway win auto-refund =='
Invoke-Json POST "$ApiBase/api/posts/$postId/like" ($HB + @{ 'Idempotency-Key' = "like-$suffix" }) @{ topic = 'drone' } | Out-Null
$deadline = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 5000
$ask = (Invoke-Json POST "$ApiBase/api/posts/$postId/comments" ($HA + @{ 'Idempotency-Key' = "g-$suffix" }) @{
  body = "@助手 从点赞过的人里抽1人 截止epoch:$deadline"
}).Data
Wait-Until 'giveaway created' {
  $list = (Invoke-Json GET "$ApiBase/api/posts/$postId/giveaways" $HA).Data
  if (@($list).Count -gt 0) { $list[0] } else { $null }
} | Out-Null
Start-Sleep -Seconds 6
# mediaRunner / giveaway tick seals; poll order refunded
$refunded = Wait-Until 'auto refund' {
  $o = (Invoke-Json GET "$ApiBase/api/orders/$($order.id)" $HB).Data
  if ($o.state -eq 'REFUNDED') { $o } else { $null }
}
if ($refunded.refundReason -ne 'GIVEAWAY_AUTO') { Fail "refund reason $($refunded.refundReason)" }
$ent2 = (Invoke-Json GET "$ApiBase/api/posts/$postId/entitlement" $HB).Data
if (-not $ent2.entitled) { Fail 'giveaway entitlement missing after auto-refund' }
Log 'ASSERT GIVEAWAY_AUTO refund once + entitlement kept'

Log '== PATH3: second material blacklist =='
$meta2 = @{ title="黑名单素材 $suffix"; description='bl'; tags=@('sale'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$mat2Resp = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta2; file=Get-Item -LiteralPath $mp4 } -TimeoutSec 180
$mat2 = $mat2Resp.Content | ConvertFrom-Json
$post2 = [long]$mat2.postId
Wait-Until "material2 READY" {
  $d = (Invoke-Json GET "$ApiBase/api/materials/$post2" $HA).Data
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { $d } else { $null }
} | Out-Null
$pub3 = $HA.Clone(); $pub3['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$post2/publish" $pub3 @{ expectedVersion = 0 } | Out-Null
$c2 = (Invoke-Json POST "$ApiBase/api/materials/$post2/sale-campaigns" $HA @{
  priceCents = 100; stockCap = 2; startMs = ($now - 1000); endMs = ($now + 3600000)
}).Data
Invoke-Json PUT "$ApiBase/api/merchants/me/blacklist/$($buyer.userId)" $HA @{ reason = 'demo' } | Out-Null
$blocked = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($c2.id)/purchase" $HB @{ clientKey = "bl-$suffix" } -ExpectStatus @(200,409)).Data
if ($null -ne $blocked -and $blocked.state -eq 'SUCCEEDED') { Fail 'blacklist should block purchase' }
Invoke-Json DELETE "$ApiBase/api/merchants/me/blacklist/$($buyer.userId)" $HA | Out-Null
$ok2 = Wait-Until 'unblocked purchase' {
  $p = (Invoke-Json POST "$ApiBase/api/sale-campaigns/$($c2.id)/purchase" $HB @{ clientKey = "bl2-$suffix" }).Data
  $r = (Invoke-Json GET "$ApiBase/api/purchase-requests/$($p.reservationId)" $HB).Data
  if ($r.state -ne 'PROCESSING') { $r } else { $null }
}
if ($ok2.state -ne 'SUCCEEDED') { Fail "unblock purchase $($ok2.state)" }
Log 'ASSERT blacklist then unblock OK'

Log 'DONE all commerce paths GREEN'
Log "log=$LogFile"
