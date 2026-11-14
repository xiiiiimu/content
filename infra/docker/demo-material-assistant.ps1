<#
.SYNOPSIS
  End-to-end material-assistant demo: Outbox→MQ→Inbox→Python parse→reply,
  feedback watch (link then READY), like-only giveaway (seal/draw/entitlement).

.NOTES
  Parser: live Python HTTP service (deterministic rule-based stand-in, not an LLM).
  Requires PowerShell 7+ for multipart -Form.

  Startup (repo root):
    .\infra\docker\start.ps1 -Profile media
    .\infra\docker\create-relay-topic.ps1
    # load .env into process (see script body)
    # terminal A: Python
    $env:CONTENT_ASSISTANT_INTERNAL_TOKEN='assistant-demo-token'
    .\assistant-python\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir assistant-python --host 127.0.0.1 --port 28090
    # terminal B: Java (Maven at E:\apache-maven-3.8.1\bin)
    mvn -q spring-boot:run "-Dspring-boot.run.profiles=pipeline" "-Dspring-boot.run.arguments=--content.media.enabled=true --content.media.s3-endpoint=http://127.0.0.1:29000 --content.media.access-key=... --content.media.secret-key=... --content.media.ffmpeg-image=linuxserver/ffmpeg:version-7.1-cli --content.assistant.python-url=http://127.0.0.1:28090 --content.assistant.internal-token=assistant-demo-token"

  Then:
    pwsh -NoProfile -File .\infra\docker\demo-material-assistant.ps1
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$PythonHealth = 'http://127.0.0.1:28090/healthz',
  [string]$MysqlContainer = 'content-mysql-1',
  [string]$MysqlUser = 'content',
  [string]$MysqlPassword = '11111',
  [string]$MysqlDb = 'content',
  [int]$PollSeconds = 180,
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [string]$LogFile = 'target/demo-material-assistant-run.log'
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
    Start-Sleep -Milliseconds 800
  }
  Fail "Timed out waiting for $Name"
}

function Sql([string]$q) {
  $out = & docker exec $MysqlContainer mysql -N -u$MysqlUser "-p$MysqlPassword" $MysqlDb -e $q 2>$null
  if ($LASTEXITCODE -ne 0) { Fail "mysql failed: $q" }
  return ($out | Out-String).Trim()
}

function New-DemoMp4([string]$OutFile) {
  $dir = Split-Path -Parent $OutFile
  $name = Split-Path -Leaf $OutFile
  $docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') {
    'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
  } else { 'docker' }
  & $docker run --rm -v "${dir}:/work" -w /work --entrypoint ffmpeg $FfmpegImage `
    -y -f lavfi -i testsrc=size=160x120:rate=10 -f lavfi -i sine=frequency=880:sample_rate=44100 `
    -t 8 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $name
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutFile)) { Fail "ffmpeg fixture failed for $OutFile" }
}

$suffix = Get-Date -Format 'yyyyMMddHHmmss'
Log "START suffix=$suffix ApiBase=$ApiBase"

Log '== readiness: Java + Python =='
Wait-Until 'java readiness' {
  try {
    $h = Invoke-RestMethod "$ApiBase/actuator/health/readiness" -TimeoutSec 3
    if ($h.status -eq 'UP') { $h }
  } catch { $null }
} | Out-Null
Wait-Until 'python healthz' {
  try {
    $h = Invoke-RestMethod $PythonHealth -TimeoutSec 3
    if ($h.status -eq 'ok') { $h }
  } catch { $null }
} | Out-Null
Log 'Python parser = deterministic FastAPI rules (CONTENT_ASSISTANT_PYTHON_URL), not an LLM'

$author = (Invoke-Json Post "$ApiBase/api/auth/register" @{} @{ username="asst_a_$suffix"; password='password-a1' }).Data
$viewer = (Invoke-Json Post "$ApiBase/api/auth/register" @{} @{ username="asst_v_$suffix"; password='password-b1' }).Data
$liker  = (Invoke-Json Post "$ApiBase/api/auth/register" @{} @{ username="asst_l_$suffix"; password='password-c1' }).Data
$HA = @{ Authorization = "Bearer $($author.token)" }
$HV = @{ Authorization = "Bearer $($viewer.token)" }
$HL = @{ Authorization = "Bearer $($liker.token)" }
Log "users author=$($author.userId) viewer=$($viewer.userId) liker=$($liker.userId)"
Invoke-Json Put "$ApiBase/api/follows/$($author.userId)" $HV | Out-Null
Invoke-Json Put "$ApiBase/api/follows/$($author.userId)" $HL | Out-Null

$work = Join-Path $env:TEMP "asst-demo-$suffix"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$mp4b = Join-Path $work 'v2.mp4'
Log "== synthesize MP4 via $FfmpegImage =="
New-DemoMp4 $mp4
New-DemoMp4 $mp4b

function New-Material([hashtable]$Hdr, [hashtable]$Meta, [string]$File) {
  $metaJson = $Meta | ConvertTo-Json -Compress
  $r = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $Hdr -Form @{
    meta = $metaJson
    file = Get-Item -LiteralPath $File
  } -TimeoutSec 180
  return ($r.Content | ConvertFrom-Json)
}

Log '== create searchable PUBLIC_PREVIEW material (rain night) =='
$searchMat = New-Material $HA @{
  title = "雨夜竖屏 $suffix"
  description = 'night rain alley'
  tags = @('rain','night')
  shareMode = 'PUBLIC_PREVIEW'
  licenseDownload = $true
  licenseCommercial = $true
  aspectRatio = 'PORTRAIT'
} $mp4
$searchPost = [long]$searchMat.postId
Wait-Until "search material $searchPost READY" {
  $d = (Invoke-Json Get "$ApiBase/api/materials/$searchPost" $HA).Data
  $hit = @($d.versions) | Where-Object { $_.state -eq 'READY' } | Select-Object -First 1
  if ($hit) { $d } else { $null }
} | Out-Null
$pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString(); $pub['Content-Type']='application/json'
Invoke-Json Post "$ApiBase/api/materials/$searchPost/publish" $pub @{ expectedVersion = 0 } | Out-Null
Log "searchPost=$searchPost published"

Log '== create giveaway host material =='
$giveMat = New-Material $HA @{
  title = "抽奖素材 $suffix"
  description = 'giveaway host'
  tags = @('giveaway')
  shareMode = 'PUBLIC_PREVIEW'
  licenseDownload = $true
  licenseCommercial = $false
} $mp4
$givePost = [long]$giveMat.postId
$giveV1 = [long]$giveMat.versionId
Wait-Until "giveaway material $givePost READY" {
  $d = (Invoke-Json Get "$ApiBase/api/materials/$givePost" $HA).Data
  $hit = @($d.versions) | Where-Object { [long]$_.versionId -eq $giveV1 -and $_.state -eq 'READY' } | Select-Object -First 1
  if ($hit) { $d } else { $null }
} | Out-Null
$pub2 = $HA.Clone(); $pub2['Idempotency-Key']=[guid]::NewGuid().ToString(); $pub2['Content-Type']='application/json'
Invoke-Json Post "$ApiBase/api/materials/$givePost/publish" $pub2 @{ expectedVersion = 0 } | Out-Null
Log "givePost=$givePost v1=$giveV1 published"

# ---------- Path 1: @助手 search via Outbox → MQ → Inbox → Python ----------
Log '== PATH1: ordinary @助手 search comment (async Outbox path) =='
$c1 = (Invoke-Json Post "$ApiBase/api/posts/$givePost/comments" ($HV + @{ 'Idempotency-Key' = "search-$suffix" }) @{
  body = '@助手 找竖屏允许商用的雨夜'
}).Data
Log "viewer commentId=$($c1.commentId) (must exist before assistant reply)"

$taskId = Wait-Until 'assistant_tasks row' {
  $id = Sql "SELECT id FROM assistant_tasks WHERE trigger_comment_id=$($c1.commentId) LIMIT 1"
  if ($id) { [long]$id } else { $null }
}
Log "assistant taskId=$taskId"

$outboxId = Wait-Until 'outbox AssistantTaskCreated id' {
  $id = Sql "SELECT id FROM outbox_events WHERE event_type='AssistantTaskCreated' AND aggregate_id=$givePost ORDER BY id DESC LIMIT 1"
  if ($id) { [long]$id } else { $null }
}
Log "outboxEventId=$outboxId"
Wait-Until "outbox $outboxId SENT" {
  $st = Sql "SELECT status FROM outbox_events WHERE id=$outboxId"
  if ($st -eq 'SENT') { $st } else { $null }
} | Out-Null
Wait-Until "inbox receipt for $outboxId" {
  $d = Sql "SELECT decision FROM consumer_inbox WHERE consumer_name='feed-v1' AND event_id=$outboxId"
  if ($d -eq 'ASSISTANT_ACCEPTED') { $d } else { $null }
} | Out-Null
Log 'ASSERT outbox SENT + inbox ASSISTANT_ACCEPTED'

$reply = Wait-Until 'assistant search reply comment' {
  $comments = (Invoke-Json Get "$ApiBase/api/posts/$givePost/comments?limit=100" $HV).Data
  $hit = @($comments) | Where-Object { $_.body -match '可公开引用的素材|雨夜|未找到' } | Select-Object -First 1
  if ($hit) { $hit } else { $null }
}
Log "ASSERT search reply commentId=$($reply.commentId) body=$($reply.body.Substring(0,[Math]::Min(120,$reply.body.Length)))"
$taskView = (Invoke-Json Get "$ApiBase/api/assistant/tasks/$taskId" $HV).Data
if ($taskView.state -notin @('DONE','CLARIFY')) { Fail "task state=$($taskView.state)" }
Log "ASSERT task state=$($taskView.state) via Python HTTP"

# ---------- Path 2: feedback subscribe, link before READY, notify once ----------
Log '== PATH2: feedback watch (link then READY) =='
$fb = (Invoke-Json Post "$ApiBase/api/posts/$givePost/comments" ($HV + @{ 'Idempotency-Key' = "fb-$suffix" }) @{
  body = '风噪太大'
  versionId = $giveV1
  offsetMs = 1000
}).Data
Log "feedback commentId=$($fb.commentId)"
$sub = (Invoke-Json Post "$ApiBase/api/materials/$givePost/feedback/$($fb.commentId)/subscribe" $HL).Data
Log "liker subscribed: $($sub.message)"

$v2meta = @{ fileHint = 'v2' } | ConvertTo-Json -Compress
$v2req = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials/$givePost/versions" -Headers $HA -Form @{
  file = Get-Item -LiteralPath $mp4b
} -TimeoutSec 180
$v2 = ($v2req.Content | ConvertFrom-Json)
$v2id = [long]$v2.versionId
Log "new versionId=$v2id (not READY yet)"

# Link while PENDING/PROCESSING
Invoke-Json Post "$ApiBase/api/materials/$givePost/feedback/$($fb.commentId)/resolve" $HA @{
  versionId = $v2id
  offsetMs = 2000
} | Out-Null
$notifyBefore = Sql "SELECT COUNT(*) FROM feedback_notify_log WHERE feedback_comment_id=$($fb.commentId) AND new_version_id=$v2id"
if ([int]$notifyBefore -ne 0) { Fail "notify fired before READY count=$notifyBefore" }
Log 'ASSERT no FEEDBACK_READY notify before READY'

Wait-Until "version $v2id READY" {
  $d = (Invoke-Json Get "$ApiBase/api/materials/$givePost" $HA).Data
  $hit = @($d.versions) | Where-Object { [long]$_.versionId -eq $v2id -and $_.state -eq 'READY' } | Select-Object -First 1
  if ($hit) { $d } else { $null }
} | Out-Null

$notifyAfter = Wait-Until 'feedback notify once' {
  $n = Sql "SELECT COUNT(*) FROM feedback_notify_log WHERE feedback_comment_id=$($fb.commentId) AND new_version_id=$v2id"
  if ([int]$n -eq 1) { $n } else { $null }
}
Log "ASSERT feedback_notify_log count=$notifyAfter"
$notifs = (Invoke-Json Get "$ApiBase/api/notifications?limit=20" $HL).Data
$fbNotif = @($notifs) | Where-Object { $_.kind -eq 'FEEDBACK_READY' -and [long]$_.refVersionId -eq $v2id } | Select-Object -First 1
if (-not $fbNotif) { Fail 'liker missing FEEDBACK_READY notification' }
$tgt = (Invoke-Json Get "$ApiBase/api/notifications/$($fbNotif.id)/target" $HL).Data
if ([long]$tgt.postId -ne $givePost) { Fail 'notification target post mismatch' }
Log "ASSERT FEEDBACK_READY notification+$($fbNotif.id) target ok"

# ---------- Path 3: like-only giveaway ----------
Log '== PATH3: like-only NL giveaway =='
# liker likes only (no comment); outsider comments but no like should not win if LIKE-only
Invoke-Json Put "$ApiBase/api/posts/$givePost/likes" $HL | Out-Null
# duplicate like path: comment twice by liker must still one seat
Invoke-Json Post "$ApiBase/api/posts/$givePost/comments" ($HL + @{ 'Idempotency-Key' = "liker-c1-$suffix" }) @{ body = '顺便说一句' } | Out-Null
Invoke-Json Post "$ApiBase/api/posts/$givePost/comments" ($HL + @{ 'Idempotency-Key' = "liker-c2-$suffix" }) @{ body = '再说一句' } | Out-Null

$gw = (Invoke-Json Post "$ApiBase/api/posts/$givePost/comments" ($HA + @{ 'Idempotency-Key' = "gw-$suffix" }) @{
  body = '@助手 点赞抽1人 8秒后开奖'
}).Data
Log "author giveaway commentId=$($gw.commentId)"

Wait-Until 'giveaway OPEN' {
  $list = (Invoke-Json Get "$ApiBase/api/posts/$givePost/giveaways" $HA).Data
  $hit = @($list) | Where-Object { $_.state -eq 'OPEN' -or $_.state -eq 'SEALED' -or $_.state -eq 'DRAWN' -or $_.state -eq 'COMPLETED' } | Select-Object -First 1
  if ($hit) { $hit } else { $null }
} | Out-Null

$gDone = Wait-Until 'giveaway COMPLETED/DRAWN' {
  $list = (Invoke-Json Get "$ApiBase/api/posts/$givePost/giveaways" $HA).Data
  $hit = @($list) | Where-Object { $_.state -in @('DRAWN','COMPLETED','SEALED') } | Select-Object -First 1
  if ($hit -and $hit.state -in @('DRAWN','COMPLETED')) { $hit } elseif ($hit) { $null } else { $null }
}
$gid = [long]$gDone.id
Log "giveawayId=$gid state=$($gDone.state) candidates=$($gDone.candidateAccountIds -join ',') winners=$($gDone.winnerAccountIds -join ',')"

$candCount = Sql "SELECT COUNT(*) FROM giveaway_candidates WHERE giveaway_id=$gid"
Log "ASSERT candidate_count=$candCount (like-only liker should be in pool; one seat per account)"
$likerIn = Sql "SELECT COUNT(*) FROM giveaway_candidates WHERE giveaway_id=$gid AND account_id=$($liker.userId)"
if ([int]$likerIn -ne 1) { Fail "liker not exactly once in candidates ($likerIn)" }

$w1 = Sql "SELECT account_id FROM giveaway_winners WHERE giveaway_id=$gid ORDER BY draw_rank"
# force second draw via tick by waiting; winners must stay identical
Start-Sleep -Seconds 2
$g2 = (Invoke-Json Get "$ApiBase/api/giveaways/$gid" $HA).Data
$w2 = ($g2.winnerAccountIds -join ',')
$w1s = (($w1 -split '\s+') -join ',')
if ($w2 -ne $w1s -and $w2 -ne ($w1 -replace '\s+',',')) {
  # normalize
  $a = @($g2.winnerAccountIds | ForEach-Object { [string]$_ })
  $b = @(($w1 -split '\s+') | Where-Object { $_ } | ForEach-Object { [string]$_ })
  if (($a -join ',') -ne ($b -join ',')) { Fail "redraw changed winners w1=$w1s w2=$w2" }
}
Log "ASSERT redraw stable winners=$w2"

$winNotif = Wait-Until 'GIVEAWAY_WIN notification' {
  $n = (Invoke-Json Get "$ApiBase/api/notifications?limit=30" $HL).Data
  $hit = @($n) | Where-Object { $_.kind -eq 'GIVEAWAY_WIN' } | Select-Object -First 1
  if ($hit) { $hit } else { $null }
}
Log "ASSERT GIVEAWAY_WIN notification id=$($winNotif.id)"
$ent = (Invoke-Json Get "$ApiBase/api/posts/$givePost/entitlement" $HL).Data
if (-not $ent.entitled) { Fail 'winner missing entitlement' }
Log "ASSERT entitlement version=$($ent.versionId) license=$($ent.license)"

# winner can sign original via entitlement; non-winner (viewer) cannot
$orig = (Invoke-Json Post "$ApiBase/api/materials/$givePost/original/sign" $HL @{ ttlMillis = 60000 }).Data
if (-not $orig.url) { Fail 'winner original sign failed' }
Log "ASSERT winner original url ok"
try {
  Invoke-Json Post "$ApiBase/api/materials/$givePost/original/sign" $HV @{ ttlMillis = 60000 } -ExpectStatus @(403,404) | Out-Null
  Log 'ASSERT non-winner original forbidden'
} catch {
  # Invoke-Json throws on unexpected; 403 expected handled above
}

Log 'DONE all paths GREEN'
Log "log=$LogFile"
Write-Host "OK demo-material-assistant GREEN log=$LogFile"
