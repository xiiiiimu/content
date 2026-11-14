<#
.SYNOPSIS
  Start the content API with async sale MQ + fault-hooks for commerce correctness/JMeter.
  Loads infra/docker/.env into the process without printing secret values.
#>
#Requires -Version 7
param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
  [switch]$Package,
  [switch]$SkipAssistant
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

if ($Package) {
  Write-Host "mvn -DskipTests package"
  & $mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "package failed: $LASTEXITCODE" }
}

$jar = Join-Path $RepoRoot 'target/content-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw "missing $jar — re-run with -Package" }

$args = @(
  '-jar', $jar,
  '--spring.profiles.active=pipeline',
  '--content.media.enabled=true',
  "--content.media.s3-endpoint=$($env:CONTENT_S3_ENDPOINT)",
  "--content.media.access-key=$($env:CONTENT_S3_ACCESS_KEY)",
  "--content.media.secret-key=$($env:CONTENT_S3_SECRET_KEY)",
  '--content.media.ffmpeg-image=linuxserver/ffmpeg:version-7.1-cli',
  '--content.sale.sync-settle=false',
  '--content.sale.producer-enabled=true',
  '--content.sale.consumer-enabled=true',
  '--content.sale.scan-enabled=true',
  '--content.sale.manual-settle=true',
  '--content.sale.fault-hooks=true',
  '--content.sale.namesrv=127.0.0.1:29876',
  '--content.sale.topic=content-sale',
  '--content.sale.settle-delay-ms=0'
)
if (-not $SkipAssistant -and $env:CONTENT_ASSISTANT_PYTHON_URL) {
  $args += "--content.assistant.python-url=$($env:CONTENT_ASSISTANT_PYTHON_URL)"
  if ($env:CONTENT_ASSISTANT_INTERNAL_TOKEN) {
    $args += "--content.assistant.internal-token=$($env:CONTENT_ASSISTANT_INTERNAL_TOKEN)"
  }
}

$log = Join-Path $RepoRoot 'target/java-commerce-run.log'
Write-Host "Starting Java → $log (sale MQ + fault-hooks)"
$p = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $RepoRoot `
  -RedirectStandardOutput $log -RedirectStandardError (Join-Path $RepoRoot 'target/java-commerce-run.err.log') `
  -PassThru -WindowStyle Hidden
"pid=$($p.Id)" | Set-Content (Join-Path $RepoRoot 'target/java-commerce.pid') -Encoding utf8
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
