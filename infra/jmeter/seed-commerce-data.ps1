<#
.SYNOPSIS
  Pre-generate buyers CSV (userId,token,clientKey), author token, campaign + optional order rows.
  Writes under target/jmeter-data/ (not committed). No real credentials in repo.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$Buyers = 100,
  [int]$Stock = 50,
  [string]$OutDir = 'target/jmeter-data',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [string]$Prefix = '',
  [switch]$CreatePaidOrders,
  [int]$PaidOrders = 0,
  # Reuse prior jmeter buyer CSVs (re-login + follow new author). Outside formal window.
  [string]$ReuseCsvGlob = '',
  [string]$BuyerPassword = 'password-b1',
  [int]$ThrottleLimit = 4,
  [string]$ReuseAuthorMeta = '',
  [switch]$ReturnMetaPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Local MySQL password comes only from the environment, never from this file or the docker command line.
function Invoke-BenchMysql([string]$q) {
  if (-not $env:CONTENT_BENCH_DB_PASSWORD) { throw 'CONTENT_BENCH_DB_PASSWORD is not set: set it to the local MySQL password before running this script.' }
  $previous = $env:MYSQL_PWD; $env:MYSQL_PWD = $env:CONTENT_BENCH_DB_PASSWORD
  try { & docker exec -e MYSQL_PWD content-mysql-1 mysql -N -ucontent content -e $q 2>&1 } finally { $env:MYSQL_PWD = $previous }
}
# Auth usernames: [a-zA-Z0-9_]{3,32} — strip hyphens, keep short, add entropy to avoid collisions.
$base = if ($Prefix) { ($Prefix -replace '[^a-zA-Z0-9_]', '') } else { Get-Date -Format 'yyyyMMddHHmmss' }
$entropy = -join ((48..57 + 97..122) | Get-Random -Count 4 | ForEach-Object { [char]$_ })
$runId = ($base + $entropy)
if ($runId.Length -gt 14) { $runId = $runId.Substring(0,14) }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
if (Test-Path (Join-Path $OutDir "meta-$runId.json")) { throw 'Seed run already exists' }

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null)
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=90 }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  $r = Invoke-WebRequest @p
  return ($r.Content | ConvertFrom-Json)
}

$swReg = [Diagnostics.Stopwatch]::StartNew()
if ($ReuseAuthorMeta) {
  $prior = Get-Content -LiteralPath $ReuseAuthorMeta -Raw | ConvertFrom-Json
  $priorAuthor = [long]$prior.authorUserId
  $authorName = (Invoke-BenchMysql "SELECT username FROM users WHERE id=$priorAuthor")
  if ($LASTEXITCODE -ne 0 -or "$authorName" -notmatch '^jma[a-zA-Z0-9_]+$') { throw 'Only an isolated jmeter author can be reused' }
  $author = Invoke-Json POST "$ApiBase/api/auth/login" @{} @{ username=[string]$authorName; password='password-a1' }
  if ([long]$author.userId -ne $priorAuthor) { throw 'Reused author identity mismatch' }
} else {
  $author = Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="jma$runId"; password='password-a1' }
}
$HA = @{ Authorization = "Bearer $($author.token)" }
$swReg.Stop()

$work = Join-Path $env:TEMP "jmeter-seed-$runId"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') { 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' } else { 'docker' }
$swMedia = [Diagnostics.Stopwatch]::StartNew()
& $docker run --rm -v "${work}:/work" -w /work --entrypoint ffmpeg $FfmpegImage -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p v1.mp4 | Out-Null
$meta = @{ title="jm $runId"; description='jmeter'; tags=@('jm'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$mat = (Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item $mp4 } -TimeoutSec 180).Content | ConvertFrom-Json
$postId = [long]$mat.postId
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
  $d = Invoke-Json GET "$ApiBase/api/materials/$postId" $HA
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { break }
  Start-Sleep -Milliseconds 400
}
$pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion = 0 } | Out-Null
$swMedia.Stop()

$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campaign = Invoke-Json POST "$ApiBase/api/materials/$postId/sale-campaigns" $HA @{
  priceCents=100; stockCap=$Stock; startMs=($now-1000); endMs=($now+7200000)
}

$swBuyers = [Diagnostics.Stopwatch]::StartNew()
$buyersPath = Join-Path $OutDir "buyers-$runId.csv"
"userId,token,clientKey" | Set-Content $buyersPath -Encoding utf8
$authorUserId = $author.userId
$bag = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$reused = 0
$registered = 0

function Get-ReusableBuyerIds([string]$Glob, [int]$Need) {
  $seen = [System.Collections.Generic.HashSet[long]]::new()
  $ids = [System.Collections.Generic.List[long]]::new()
  if (-not $Glob) { return $ids }
  $files = @(Get-ChildItem -Path $Glob -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
  :files foreach ($f in $files) {
    foreach ($line in @(Get-Content $f.FullName | Select-Object -Skip 1)) {
      if (-not $line) { continue }
      $uid = 0L
      if ([long]::TryParse((($line -split ',', 2)[0]), [ref]$uid) -and $uid -gt 0 -and $seen.Add($uid)) {
        $ids.Add($uid) | Out-Null
        if ($ids.Count -ge $Need) { break files }
      }
    }
  }
  return $ids
}

$reuseGlob = if ($ReuseCsvGlob) { $ReuseCsvGlob } else { Join-Path $OutDir 'buyers-*.csv' }
$reuseIds = @(Get-ReusableBuyerIds $reuseGlob $Buyers)
if ($reuseIds.Count -gt 0) {
  Write-Host "reuse candidate ids=$($reuseIds.Count) (need $Buyers); refreshing login+follow with ThrottleLimit=$ThrottleLimit"
  # Resolve usernames in one SQL round-trip (outside formal window).
  $idCsv = ($reuseIds | Select-Object -First $Buyers) -join ','
  $nameMap = @{}
  $followMap = @{}
  $sqlOut = Invoke-BenchMysql "SELECT id,username,EXISTS(SELECT 1 FROM follows f WHERE f.follower_id=users.id AND f.followee_id=$authorUserId AND f.status='FOLLOWING') FROM users WHERE id IN ($idCsv)" 2>&1
  if ($LASTEXITCODE -ne 0) { throw "mysql username lookup failed: $sqlOut" }
  foreach ($line in @($sqlOut)) {
    $s = "$line".Trim()
    if (-not $s) { continue }
    $p = $s -split '\t'
    if ($p.Count -ge 3) { $nameMap[[long]$p[0]] = $p[1]; $followMap[[long]$p[0]] = ($p[2] -eq '1') }
  }
  $loginJobs = @($reuseIds | Select-Object -First $Buyers | ForEach-Object {
    [pscustomobject]@{ userId = [long]$_; username = $nameMap[[long]$_]; alreadyFollows = $followMap[[long]$_] }
  } | Where-Object { $_.username })
  $loginJobs | ForEach-Object -Parallel {
    $ApiBase = $using:ApiBase
    $authorUserId = $using:authorUserId
    $pwd = $using:BuyerPassword
    $uname = $_.username
    $uidHint = $_.userId
    $alreadyFollows = [bool]$_.alreadyFollows
    $body = @{ username = $uname; password = $pwd } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 90
    $s = $r.Content | ConvertFrom-Json
    if ([long]$s.userId -ne $uidHint) { throw "login userId mismatch hint=$uidHint got=$($s.userId)" }
    $ok = $alreadyFollows
    for ($t = 0; $t -lt 5 -and -not $ok; $t++) {
      try {
        Invoke-WebRequest -Method Put -Uri "$ApiBase/api/follows/$authorUserId" -Headers @{ Authorization = "Bearer $($s.token)" } -TimeoutSec 60 | Out-Null
        $ok = $true
      } catch { Start-Sleep -Milliseconds (200 * ($t + 1)) }
    }
    if (-not $ok) { throw "follow failed for reused $($s.userId)" }
    ($using:bag).Add([pscustomobject]@{ userId = $s.userId; token = $s.token; clientKey = "jm-$($s.userId)" }) | Out-Null
  } -ThrottleLimit $ThrottleLimit
  $reused = @($bag).Count
}

$needNew = [Math]::Max(0, $Buyers - $reused)
if ($needNew -gt 0) {
  Write-Host "registering $needNew new buyers (reused=$reused) ThrottleLimit=$ThrottleLimit"
  0..($needNew - 1) | ForEach-Object -Parallel {
    $ApiBase = $using:ApiBase
    $runId = $using:runId
    $authorUserId = $using:authorUserId
    $pwd = $using:BuyerPassword
    $i = $_
    # jmb + runId(<=12) + i keeps under 32 chars for i < 1e6
    $uname = "jmb${runId}$i"
    if ($uname.Length -gt 32) { $uname = $uname.Substring(0, 32) }
    $body = @{ username = $uname; password = $pwd } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/auth/register" -ContentType 'application/json' -Body $body -TimeoutSec 90
    $s = $r.Content | ConvertFrom-Json
    $ok = $false
    for ($t = 0; $t -lt 5 -and -not $ok; $t++) {
      try {
        Invoke-WebRequest -Method Put -Uri "$ApiBase/api/follows/$authorUserId" -Headers @{ Authorization = "Bearer $($s.token)" } -TimeoutSec 60 | Out-Null
        $ok = $true
      } catch { Start-Sleep -Milliseconds (200 * ($t + 1)) }
    }
    if (-not $ok) { throw "follow failed for $($s.userId)" }
    ($using:bag).Add([pscustomobject]@{ userId = $s.userId; token = $s.token; clientKey = "jm-$($s.userId)" }) | Out-Null
  } -ThrottleLimit $ThrottleLimit
  $registered = $needNew
}

$buyerObjs = @($bag)
if ($buyerObjs.Count -lt $Buyers) { throw "seed buyers incomplete got=$($buyerObjs.Count) want=$Buyers reused=$reused registered=$registered" }
# Deduplicate by userId (reuse+register should not overlap, but guard).
$uniq = [System.Collections.Generic.List[object]]::new()
$seenUid = [System.Collections.Generic.HashSet[long]]::new()
foreach ($b in ($buyerObjs | Sort-Object userId)) {
  if ($seenUid.Add([long]$b.userId)) { $uniq.Add($b) | Out-Null }
}
if ($uniq.Count -lt $Buyers) { throw "seed unique buyers incomplete got=$($uniq.Count) want=$Buyers" }
$buyerObjs = @($uniq | Select-Object -First $Buyers)
foreach ($b in $buyerObjs) {
  Add-Content $buyersPath -Value "$($b.userId),$($b.token),jm-c$($campaign.id)-u$($b.userId)"
}
$swBuyers.Stop()
Write-Host "buyers ready path=$buyersPath reused=$reused registered=$registered total=$($buyerObjs.Count) sec=$([Math]::Round($swBuyers.Elapsed.TotalSeconds,1))"

$ordersPath = Join-Path $OutDir "orders-$runId.csv"
$reservationsPath = Join-Path $OutDir "reservations-$runId.csv"
"orderId,userId,token,postId,reservationId" | Set-Content $ordersPath -Encoding utf8
"reservationId,userId,token,postId,state" | Set-Content $reservationsPath -Encoding utf8
$swOrders = [Diagnostics.Stopwatch]::StartNew()
# Paid/reservation seed is OUTSIDE the formal JMeter window (caller must not include this in load timing).
$nPaid = 0
if ($CreatePaidOrders -or $PaidOrders -gt 0) {
  $nPaid = if ($PaidOrders -gt 0) { $PaidOrders } else { [Math]::Min(10, $Buyers, $Stock) }
}
for ($i=0; $i -lt $nPaid; $i++) {
  $b = $buyerObjs[$i]
  $H = @{ Authorization = "Bearer $($b.token)" }
  $p = Invoke-Json POST "$ApiBase/api/sale-campaigns/$($campaign.id)/purchase" $H @{ clientKey = "jm-c$($campaign.id)-u$($b.userId)" }
  $deadline = (Get-Date).AddSeconds(90)
  while ($p.state -eq 'PROCESSING' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 200
    $p = Invoke-Json GET "$ApiBase/api/purchase-requests/$($p.reservationId)" $H
  }
  Add-Content $reservationsPath -Value "$($p.reservationId),$($b.userId),$($b.token),$postId,$($p.state)"
  if ($p.state -eq 'SUCCEEDED' -and $p.orderId) {
    Add-Content $ordersPath -Value "$($p.orderId),$($b.userId),$($b.token),$postId,$($p.reservationId)"
  }
}
$swOrders.Stop()

$metaOut = [ordered]@{
  runId = $runId
  apiBase = $ApiBase
  authorReused = [bool]$ReuseAuthorMeta
  authorUserId = $author.userId
  authorToken = $author.token
  postId = $postId
  campaignId = $campaign.id
  stock = $Stock
  buyers = $Buyers
  buyersReused = $reused
  buyersRegistered = $registered
  paidSeeded = $nPaid
  buyersCsv = (Resolve-Path $buyersPath).Path
  ordersCsv = (Resolve-Path $ordersPath).Path
  reservationsCsv = (Resolve-Path $reservationsPath).Path
  timingsSec = @{
    registerAuthor = [Math]::Round($swReg.Elapsed.TotalSeconds,3)
    mediaPublish = [Math]::Round($swMedia.Elapsed.TotalSeconds,3)
    registerBuyers = [Math]::Round($swBuyers.Elapsed.TotalSeconds,3)
    seedOrders = [Math]::Round($swOrders.Elapsed.TotalSeconds,3)
    note = 'seed timings excluded from formal JMeter window'
  }
}
$metaPath = Join-Path $OutDir "meta-$runId.json"
$metaOut | ConvertTo-Json -Depth 5 | Set-Content $metaPath -Encoding utf8
# sanitize copy without tokens for archival
$safe = [ordered]@{}
foreach ($k in $metaOut.Keys) { $safe[$k] = $metaOut[$k] }
$safe.authorToken = '<redacted>'
$safe | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir "meta-$runId.public.json") -Encoding utf8
Write-Host "seeded buyers=$buyersPath meta=$metaPath"
if ($ReturnMetaPath) { (Resolve-Path $metaPath).Path }
