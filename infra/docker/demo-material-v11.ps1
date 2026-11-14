<#
.SYNOPSIS
  Repeatable V1.1 material-post demo (Batch 1) against real MinIO + FFmpeg image.

.NOTES
  Coverage vs MaterialFlowIT:
  - MaterialFlowIT: Testcontainers (S3Mock + empty ffmpeg placeholder path OK for logic).
  - This script: compose MinIO profile + linuxserver/ffmpeg Docker image + scheduled mediaRunner.

  Startup (repo root, PowerShell 7):
    .\infra\docker\start.ps1 -Profile media
    .\infra\docker\create-relay-topic.ps1   # optional if also exercising feed notify
    $env:CONTENT_MYSQL_PASSWORD = (Get-Content infra\docker\.env | ? { $_ -match '^CONTENT_MYSQL_PASSWORD=' } | % { ($_ -split '=',2)[1] })
    $env:CONTENT_CURSOR_KEY   = 'cursor-local-key-32-bytes-minimum-0001'
    $env:CONTENT_IDENTITY_KEY = 'identity-local-key-32-bytes-minimum-001'
    $env:CONTENT_REDIS_URI    = 'redis://127.0.0.1:26379'
    $env:CONTENT_S3_ENDPOINT  = 'http://127.0.0.1:29000'
    $env:CONTENT_S3_ACCESS_KEY = (Get-Content infra\docker\.env | ? { $_ -match '^CONTENT_MINIO_USER=' } | % { ($_ -split '=',2)[1] })
    if (-not $env:CONTENT_S3_ACCESS_KEY) { $env:CONTENT_S3_ACCESS_KEY = 'contentadmin' }
    $env:CONTENT_S3_SECRET_KEY = (Get-Content infra\docker\.env | ? { $_ -match '^CONTENT_MINIO_PASSWORD=' } | % { ($_ -split '=',2)[1] })
    mvn -q spring-boot:run "-Dspring-boot.run.profiles=pipeline" `
      "-Dspring-boot.run.arguments=--content.media.enabled=true --content.media.s3-endpoint=$($env:CONTENT_S3_ENDPOINT) --content.media.access-key=$($env:CONTENT_S3_ACCESS_KEY) --content.media.secret-key=$($env:CONTENT_S3_SECRET_KEY) --content.media.ffmpeg-image=linuxserver/ffmpeg:version-7.1-cli"

  Then (PowerShell 7 required for -Form multipart):
    pwsh -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\demo-material-v11.ps1 -PollSeconds 180
#>
param(
  [string] $Base = 'http://127.0.0.1:28080',
  [int] $PollSeconds = 180,
  [string] $UserSuffix = (Get-Date -Format 'yyyyMMddHHmmss'),
  [string] $FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null,[int[]]$ExpectStatus=@(200))
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=60; UseBasicParsing=$true }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  try { $r=Invoke-WebRequest @p; $code=[int]$r.StatusCode; $c=$r.Content }
  catch {
    $resp=$_.Exception.Response; if ($null -eq $resp) { throw }
    $code=[int]$resp.StatusCode; $c=$null
    try { $s=$resp.GetResponseStream(); if ($s) { $c=[IO.StreamReader]::new($s).ReadToEnd() } } catch {}
  }
  if ($ExpectStatus -notcontains $code) { throw "HTTP $code $Method $Url body=$c" }
  [pscustomobject]@{ StatusCode=$code; Data= $(if ($c) { $c|ConvertFrom-Json } else { $null }) }
}

function Wait-Until([string]$Name,[scriptblock]$Probe) {
  $deadline=(Get-Date).AddSeconds($PollSeconds)
  while ((Get-Date) -lt $deadline) { $x=& $Probe; if ($x) { return $x }; Start-Sleep -Milliseconds 1000 }
  throw "Timed out waiting for $Name"
}

function New-DemoMp4([string]$OutFile) {
  $dir = Split-Path -Parent $OutFile
  $name = Split-Path -Leaf $OutFile
  $docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') {
    'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
  } else { 'docker' }
  & $docker run --rm -v "${dir}:/work" -w /work --entrypoint ffmpeg $FfmpegImage `
    -y -f lavfi -i testsrc=size=160x120:rate=10 -f lavfi -i sine=frequency=880:sample_rate=44100 `
    -t 10 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $name
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutFile)) { throw "ffmpeg fixture failed for $OutFile" }
}

Write-Host '== readiness =='
Wait-Until 'readiness' { try { $h=Invoke-RestMethod "$Base/actuator/health/readiness" -TimeoutSec 3; if ($h.status -eq 'UP') { $h } } catch { $null } } | Out-Null

$alice="malice_$UserSuffix"; $bob="mbob_$UserSuffix"
$H=@{ 'Content-Type'='application/json' }
$A=(Invoke-Json Post "$Base/api/auth/register" $H @{ username=$alice; password='password-a1' }).Data
$B=(Invoke-Json Post "$Base/api/auth/register" $H @{ username=$bob; password='password-b1' }).Data
$HA=@{ Authorization="Bearer $($A.token)" }
$HB=@{ Authorization="Bearer $($B.token)" }
Invoke-Json Put "$Base/api/follows/$($A.userId)" $HB | Out-Null

$work = Join-Path $env:TEMP "mat-demo-$UserSuffix"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$v1file = Join-Path $work 'v1.mp4'
$v2file = Join-Path $work 'v2.mp4'
$badfile = Join-Path $work 'bad.bin'
Write-Host "== synthesize real MP4 via $FfmpegImage =="
New-DemoMp4 $v1file
New-DemoMp4 $v2file
[IO.File]::WriteAllBytes($badfile,[Text.Encoding]::UTF8.GetBytes('not-a-container'))

$meta=@{
  title="Sunset drone $UserSuffix"
  description='coastal wind'
  tags=@('drone','sunset')
  shareMode='PASSWORD_ORIGINAL'
  password='share-secret'
  licenseDownload=$true
  licenseCommercial=$false
} | ConvertTo-Json -Compress

Write-Host '== create material draft (multipart) =='
$create=Invoke-WebRequest -Method Post -Uri "$Base/api/materials" -Headers $HA -Form @{
  meta = $meta
  file = Get-Item -LiteralPath $v1file
} -TimeoutSec 180
$created=$create.Content | ConvertFrom-Json
$postId=[long]$created.postId
$v1=[long]$created.versionId
Write-Host "postId=$postId v1=$v1"

Write-Host '== poll version READY (MinIO + FFmpeg worker) =='
$ready1=Wait-Until "version $v1 READY" {
  $d=(Invoke-Json Get "$Base/api/materials/$postId" $HA).Data
  $hit=@($d.versions)|Where-Object { [long]$_.versionId -eq $v1 -and $_.state -eq 'READY' }|Select-Object -First 1
  if ($hit) { $d } else { $null }
}
if ([long]$ready1.defaultVersionId -ne $v1) { throw "default not v1" }

$pubHdr=$HA.Clone(); $pubHdr['Idempotency-Key']=[guid]::NewGuid().ToString(); $pubHdr['Content-Type']='application/json'
Invoke-Json Post "$Base/api/materials/$postId/publish" $pubHdr @{ expectedVersion = 0 } | Out-Null

Write-Host '== discover by title+tag =='
$needle="Sunset drone $UserSuffix"
$disc=Wait-Until 'discover hit' {
  $page=(Invoke-Json Get "$Base/api/materials/discover?q=Sunset&tag=drone&limit=20" $HB).Data
  $hit=@($page.items)|Where-Object { $_.title -eq $needle }|Select-Object -First 1
  if ($hit) { $hit } else { $null }
}
Write-Host "discovered postId=$($disc.postId) cover=$($disc.coverAvailable)"

$preview=(Invoke-Json Post "$Base/api/materials/$postId/preview/sign" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ versionId=$v1; ttlMillis=60000 }).Data
Write-Host "preview purpose=$($preview.purpose)"

Write-Host '== comment at 8000ms on v1 =='
$cHdr=$HB.Clone(); $cHdr['Idempotency-Key']="mat-c-$UserSuffix"; $cHdr['Content-Type']='application/json'
$comment=(Invoke-Json Post "$Base/api/posts/$postId/comments" $cHdr @{ body='cut wind at 8s'; versionId=$v1; offsetMs=8000 }).Data
if ([long]$comment.materialVersionId -ne $v1 -or [long]$comment.offsetMs -ne 8000) { throw "timestamp comment fields missing" }
Write-Host "commentId=$($comment.commentId) versionId=$($comment.materialVersionId) offsetMs=$($comment.offsetMs)"

Write-Host '== failed new version must keep default=v1 =='
$failResp=Invoke-WebRequest -Method Post -Uri "$Base/api/materials/$postId/versions" -Headers $HA -Form @{ file = Get-Item -LiteralPath $badfile } -TimeoutSec 120
$failed=($failResp.Content | ConvertFrom-Json)
$failedId=[long]$failed.versionId
Wait-Until "version $failedId FAILED" {
  $d=(Invoke-Json Get "$Base/api/materials/$postId" $HA).Data
  $hit=@($d.versions)|Where-Object { [long]$_.versionId -eq $failedId -and $_.state -eq 'FAILED' }|Select-Object -First 1
  if ($hit -and [long]$d.defaultVersionId -eq $v1) { $d } else { $null }
} | Out-Null
Write-Host "failedVersion=$failedId defaultStill=$v1"

Write-Host '== successful v2 becomes default; old comment still on v1@8s =='
$v2resp=Invoke-WebRequest -Method Post -Uri "$Base/api/materials/$postId/versions" -Headers $HA -Form @{ file = Get-Item -LiteralPath $v2file } -TimeoutSec 180
$v2=($v2resp.Content | ConvertFrom-Json)
$v2id=[long]$v2.versionId
Wait-Until "version $v2id default" {
  $d=(Invoke-Json Get "$Base/api/materials/$postId" $HA).Data
  if ([long]$d.defaultVersionId -eq $v2id) { $d } else { $null }
} | Out-Null

Invoke-Json Post "$Base/api/posts/$postId/comments/$($comment.commentId)/accept" $HA | Out-Null
$still=(Invoke-Json Post "$Base/api/materials/$postId/preview/sign" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ versionId=$v1; ttlMillis=60000 }).Data
if ($still.purpose -ne 'PREVIEW') { throw 'old version preview failed' }
Write-Host "oldCommentStill versionId=$($comment.materialVersionId) offsetMs=$($comment.offsetMs); v1 preview OK"

Write-Host '== password download =='
Invoke-Json Post "$Base/api/materials/$postId/unlock" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ password='wrong' } -ExpectStatus @(403) | Out-Null
$unlock=(Invoke-Json Post "$Base/api/materials/$postId/unlock" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ password='share-secret' }).Data
$orig=(Invoke-Json Post "$Base/api/materials/$postId/original/sign" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ grant=$unlock.grant; versionId=$v1; ttlMillis=60000 }).Data
Write-Host "original purpose=$($orig.purpose)"

Write-Host '== offline denies discover detail preview =='
$detail=(Invoke-Json Get "$Base/api/posts/$postId" $HA).Data
Invoke-Json Post "$Base/api/posts/$postId/offline" (@{ Authorization=$HA.Authorization; 'Content-Type'='application/json' }) @{ expectedVersion=[long]$detail.version } | Out-Null
$after=(Invoke-Json Get "$Base/api/materials/discover?q=Sunset&tag=drone&limit=50" $HB).Data
$leak=@($after.items)|Where-Object { [long]$_.postId -eq $postId }
if ($leak) { throw 'offline material still in discover' }
Invoke-Json Get "$Base/api/materials/$postId" $HB -ExpectStatus @(404) | Out-Null
Invoke-Json Post "$Base/api/materials/$postId/preview/sign" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ versionId=$v1; ttlMillis=60000 } -ExpectStatus @(404) | Out-Null

Write-Host "OK material demo UserSuffix=$UserSuffix postId=$postId v1=$v1 failed=$failedId v2=$v2id"
exit 0
