<#
.SYNOPSIS
  Start the content API with async sale MQ + fault-hooks for commerce correctness/JMeter.
  Loads infra/docker/.env into the process without printing secret values.
#>
#Requires -Version 7
param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
  [switch]$Package,
  [switch]$SkipAssistant,
  [string]$DiagnosticsDir = '',
  [string]$JarPath = '',
  [switch]$SegmentTimings,
  [ValidateRange(1,200)][int]$BridgeBatchSize = 20
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Set-Location $RepoRoot

$envFile = Join-Path $RepoRoot 'infra/docker/.env'
if (-not (Test-Path $envFile)) { throw "missing $envFile" }
Get-Content $envFile | ForEach-Object {
  if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
  $k,$v = $_.Split('=',2)
  if ($k -and -not [string]::IsNullOrWhiteSpace($k)) { Set-Item -Path "Env:$k" -Value $v }
}

# Demo-local signing keys if not provided (must be ≥32 bytes). Never commit these as defaults in yml.
if (-not $env:CONTENT_CURSOR_KEY) { $env:CONTENT_CURSOR_KEY = 'cursor-local-key-32-bytes-minimum-001' }
if (-not $env:CONTENT_IDENTITY_KEY) { $env:CONTENT_IDENTITY_KEY = 'identity-local-key-32-bytes-minimum-001' }
if (-not $env:CONTENT_REDIS_URI) { $env:CONTENT_REDIS_URI = 'redis://127.0.0.1:26379' }
if (-not $env:CONTENT_S3_ENDPOINT) { $env:CONTENT_S3_ENDPOINT = 'http://127.0.0.1:29000' }
if (-not $env:CONTENT_S3_ACCESS_KEY) { $env:CONTENT_S3_ACCESS_KEY = $env:CONTENT_MINIO_USER }
if (-not $env:CONTENT_S3_SECRET_KEY) { $env:CONTENT_S3_SECRET_KEY = $env:CONTENT_MINIO_PASSWORD }

$mvn = $null
foreach ($c in @(
  (Join-Path $RepoRoot 'tools/apache-maven-3.9.6/bin/mvn.cmd'),
  'E:\apache-maven-3.8.1\bin\mvn.cmd',
  'mvn'
)) {
  if ($c -eq 'mvn') { $mvn = 'mvn'; break }
  if (Test-Path $c) { $mvn = $c; break }
}
if (-not $mvn) { throw 'mvn not found' }

if ($Package -and $JarPath) { throw 'Use either -Package or an explicit -JarPath' }
if ($Package) {
  Write-Host "mvn -DskipTests package"
  & $mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "package failed: $LASTEXITCODE" }
}

$jar = if ($JarPath) { (Resolve-Path -LiteralPath $JarPath).Path } else { Join-Path $RepoRoot 'target/content-0.1.0-SNAPSHOT.jar' }
if (-not (Test-Path $jar)) { throw "missing $jar — re-run with -Package" }

# FeedConfiguration overwrites spring.datasource.hikari.*; use env (or -D before -jar).
# Before (r4 residual 503/500): pool=80, jdbc socketTimeout hard-coded 5000 → Communications link failure ~5.4s.
# After: pool=120, jdbc socket/connect timeouts raised; pipeline scan off; Redis Lettuce io/cpu=8; MQ accept deferred.
if (-not $env:CONTENT_HIKARI_MAX_POOL) { $env:CONTENT_HIKARI_MAX_POOL = '120' }
if (-not $env:CONTENT_HIKARI_CONNECTION_TIMEOUT_MS) { $env:CONTENT_HIKARI_CONNECTION_TIMEOUT_MS = '15000' }
if (-not $env:CONTENT_HIKARI_JDBC_CONNECT_TIMEOUT_MS) { $env:CONTENT_HIKARI_JDBC_CONNECT_TIMEOUT_MS = '10000' }
if (-not $env:CONTENT_HIKARI_JDBC_SOCKET_TIMEOUT_MS) { $env:CONTENT_HIKARI_JDBC_SOCKET_TIMEOUT_MS = '60000' }
if (-not $env:CONTENT_MYBATIS_STATEMENT_TIMEOUT_SECONDS) { $env:CONTENT_MYBATIS_STATEMENT_TIMEOUT_SECONDS = '30' }
# Lettuce default 2/2 starves redis.reserve under 200-way purchase (mixed Read timed out).
if (-not $env:CONTENT_REDIS_IO_THREADS) { $env:CONTENT_REDIS_IO_THREADS = '8' }
if (-not $env:CONTENT_REDIS_COMPUTATION_THREADS) { $env:CONTENT_REDIS_COMPUTATION_THREADS = '8' }

# Historical configuration (superseded by measured Windows local acceptance below): 5k purchase-POST evidence (purchase-5k-limited-t5000-…20261116023232) showed
# peakHttpInFlight pinned at 800 (= old threads.max) with CONNECT_FAIL/503 under 5000-ready barrier.
# Windows plain backlog was measured at 200 despite accept-count=5000. Opt in to native hint.
# Bound HTTP workers to 100, leaving Hikari slots for 20 settlement workers (pool=120).
# Keep connection limits, queue target and all request/Redis/JDBC timeouts unchanged.
$args = @(
  '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
  '-jar', $jar,
  '--spring.profiles.active=pipeline',
  '--server.address=127.0.0.1',
  '--content.local.windows-backlog-hint=true',
  '--server.tomcat.threads.max=100',
  '--server.tomcat.threads.min-spare=100',
  '--server.tomcat.accept-count=5000',
  '--server.tomcat.max-connections=20000',
  # Pause delivery scan during commerce JMeter (Redis recovery DEAD storm contended JDBC).
  '--content.pipeline.scan-enabled=false',
  '--content.media.enabled=true',
  "--content.media.s3-endpoint=$($env:CONTENT_S3_ENDPOINT)",
  "--content.media.access-key=$($env:CONTENT_S3_ACCESS_KEY)",
  "--content.media.secret-key=$($env:CONTENT_S3_SECRET_KEY)",
  '--content.media.ffmpeg-image=linuxserver/ffmpeg:version-7.1-cli',
  '--content.sale.sync-settle=false',
  '--content.sale.producer-enabled=true',
  '--content.sale.consumer-enabled=true',
  '--content.sale.scan-enabled=true',
  # Fast pending-MQ drain now that purchase accept no longer sync-sends (default was 1000ms / 50).
  '--content.sale.scan-delay-millis=50',
  "--content.sale.bridge-batch-size=$BridgeBatchSize",
  '--content.sale.manual-settle=true',
  '--content.sale.fault-hooks=true',
  '--content.sale.namesrv=127.0.0.1:29876',
  '--content.sale.topic=content-sale',
  '--content.sale.settle-delay-ms=0',
  # Default 20: bumping to 48 worsened mixed TIMEOUTs (JDBC lockCampaign contention).
  '--content.sale.consume-threads=20'
)
if (-not $SkipAssistant -and $env:CONTENT_ASSISTANT_PYTHON_URL) {
  $args += "--content.assistant.python-url=$($env:CONTENT_ASSISTANT_PYTHON_URL)"
  if ($env:CONTENT_ASSISTANT_INTERNAL_TOKEN) {
    $args += "--content.assistant.internal-token=$($env:CONTENT_ASSISTANT_INTERNAL_TOKEN)"
  }
}

$log = Join-Path $RepoRoot 'target/java-commerce-run.log'
$errorLog = Join-Path $RepoRoot 'target/java-commerce-run.err.log'
$pidFile = Join-Path $RepoRoot 'target/java-commerce.pid'
if ($SegmentTimings -and -not $DiagnosticsDir) { throw 'SegmentTimings requires a new DiagnosticsDir' }
if ($DiagnosticsDir) {
  if (Test-Path $DiagnosticsDir) { throw 'DiagnosticsDir already exists' }
  New-Item -ItemType Directory -Path $DiagnosticsDir | Out-Null
  $args += @( '--server.tomcat.mbeanregistry.enabled=true',
    '--management.endpoints.web.exposure.include=health,metrics', '--spring.datasource.hikari.register-mbeans=true')
  if ($SegmentTimings) {
    $fullDiagnostics=(Resolve-Path $DiagnosticsDir).Path
    $args += @('--content.sale.latency-enabled=true','--server.tomcat.accesslog.enabled=true','--server.tomcat.accesslog.buffered=true',"--server.tomcat.accesslog.directory=$fullDiagnostics/access",'--server.tomcat.accesslog.pattern=%{begin:msec}t,%{end:msec}t,%D,%s,%U,%{X-Diag-Id}i,%I')
  }
  $log = Join-Path $DiagnosticsDir 'java.log' 
  $errorLog = Join-Path $DiagnosticsDir 'java.err.log'
  $pidFile = Join-Path $DiagnosticsDir 'java.pid'
}
Write-Host "Starting Java → $log (sale MQ + fault-hooks)"
$javaExe = 'java'
foreach ($cand in @(
  "$env:JAVA_HOME\bin\java.exe",
  'C:\Users\西木\.jdks\ms-17.0.17\bin\java.exe'
)) {
  if ($cand -and (Test-Path $cand)) { $javaExe = $cand; break }
}
$p = Start-Process -FilePath $javaExe -ArgumentList $args -WorkingDirectory $RepoRoot `
  -RedirectStandardOutput $log -RedirectStandardError $errorLog `
  -PassThru -WindowStyle Hidden
"pid=$($p.Id)" | Set-Content $pidFile -Encoding utf8
Write-Host "pid=$($p.Id)"
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
  if ($p.HasExited) { throw "Java exited early code=$($p.ExitCode) — see $log" }
  try {
    $h = Invoke-RestMethod 'http://127.0.0.1:28080/actuator/health/readiness' -TimeoutSec 2
    if ($h.status -eq 'UP') { Write-Host 'readiness UP'; return }
  } catch {}
  Start-Sleep -Seconds 2
}
throw 'readiness timeout'
