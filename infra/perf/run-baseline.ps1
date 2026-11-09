param(
  [ValidateSet('push','pull','hybrid')][string]$Mode = 'hybrid',
  [int]$DurationSec = 60,
  [int]$Authors = 100,
  [int]$PostsPerAuthor = 50,
  [int]$Readers = 200,
  [int]$FollowsPerReader = 20
)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path "$root\pom.xml")) { $root = (Resolve-Path "$PSScriptRoot\..\..").Path }
Set-Location $root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $root "target\perf-evidence\$Mode-$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$commit = (git rev-parse HEAD).Trim()
@"
mode=$Mode
durationSec=$DurationSec
authors=$Authors
postsPerAuthor=$PostsPerAuthor
readers=$Readers
followsPerReader=$FollowsPerReader
commit=$commit
javaXmx=512m
seedOrder=rebuild,modes,follow-first,drain,publish+accept+worker,warmup,steady
ops=feed_read,like_write
skippedVsPassed=PerfHarnessIT requires -Dcontent.perf.enabled=true; default CI skip is not a pass
started=$(Get-Date -Format o)
"@ | Set-Content (Join-Path $out 'config.txt')
git rev-parse HEAD | Set-Content (Join-Path $out 'commit.txt')
$env:CONTENT_PERF_MODE = $Mode
$env:CONTENT_PERF_OUT = $out
$env:CONTENT_PERF_DURATION_SEC = "$DurationSec"
$env:CONTENT_PERF_AUTHORS = "$Authors"
$env:CONTENT_PERF_POSTS = "$PostsPerAuthor"
$env:CONTENT_PERF_READERS = "$Readers"
$env:CONTENT_PERF_FOLLOWS = "$FollowsPerReader"
$env:GIT_COMMIT = $commit
$mvn = Join-Path $env:USERPROFILE '.m2\wrapper\dists\apache-maven-3.9.12\59fe215c0ad6947fea90184bf7add084544567b927287592651fda3782e0e798\bin\mvn.cmd'
if (-not (Test-Path $mvn)) { $mvn = 'mvn' }
$env:JAVA_TOOL_OPTIONS = '-Xms512m -Xmx512m'
& $mvn -B -ntp -Pintegration `
  "-Dcontent.perf.enabled=true" `
  "-Dtest=ContentApplicationTest" `
  "-Dit.test=PerfHarnessIT" `
  "-DfailIfNoTests=false" `
  verify 2>&1 | Tee-Object (Join-Path $out 'harness.log')
$exit = $LASTEXITCODE
Write-Host "PERF_OUT=$out exit=$exit"
exit $exit
