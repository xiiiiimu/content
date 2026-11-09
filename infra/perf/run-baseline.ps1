param(
  [ValidateSet('push','pull','hybrid')][string]$Mode = 'hybrid',
  [ValidateSet('baseline','ladder','stability','capacity')][string]$Phase = 'capacity',
  [ValidateSet('single','reuse')][string]$Suite = 'reuse',
  [int]$DurationSec = 60,
  [int]$Authors = 100,
  [int]$PostsPerAuthor = 50,
  [int]$Readers = 200,
  [int]$FollowsPerReader = 20,
  [int]$Clients = 8,
  [int]$VisibilityEvery = 25,
  [string]$LoadPoints = '',
  [switch]$ArchiveToDocs
)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path "$root\pom.xml")) { $root = (Resolve-Path "$PSScriptRoot\..\..").Path }
Set-Location $root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $root "target\perf-evidence\$Mode-$Phase-$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$commit = (git rev-parse HEAD).Trim()
@"
mode=$Mode
phase=$Phase
suite=$Suite
durationSec=$DurationSec
authors=$Authors
postsPerAuthor=$PostsPerAuthor
readers=$Readers
followsPerReader=$FollowsPerReader
clients=$Clients
visibilityEvery=$VisibilityEvery
loadPoints=$LoadPoints
commit=$commit
javaXmx=512m
seedOrder=rebuild,modes,follow-first,backfill-drain,publish+accept+worker,coverage,reuse-load-points
ops=feed_read,like_write
capacityCeilingClaim=false
plan=docs/m8-capacity-plan.md
queue=paused-see-docs/perf-evidence/capacity/QUEUE-PAUSED.md
skippedVsPassed=PerfHarnessIT requires -Dcontent.perf.enabled=true; default CI skip is not a pass
started=$(Get-Date -Format o)
"@ | Set-Content (Join-Path $out 'config.txt')
git rev-parse HEAD | Set-Content (Join-Path $out 'commit.txt')
$env:CONTENT_PERF_MODE = $Mode
$env:CONTENT_PERF_PHASE = $Phase
$env:CONTENT_PERF_SUITE = $Suite
$env:CONTENT_PERF_OUT = $out
$env:CONTENT_PERF_DURATION_SEC = "$DurationSec"
$env:CONTENT_PERF_AUTHORS = "$Authors"
$env:CONTENT_PERF_POSTS = "$PostsPerAuthor"
$env:CONTENT_PERF_READERS = "$Readers"
$env:CONTENT_PERF_FOLLOWS = "$FollowsPerReader"
$env:CONTENT_PERF_CLIENTS = "$Clients"
$env:CONTENT_PERF_VISIBILITY_EVERY = "$VisibilityEvery"
if ($LoadPoints) { $env:CONTENT_PERF_LOAD_POINTS = $LoadPoints } else { Remove-Item Env:CONTENT_PERF_LOAD_POINTS -ErrorAction SilentlyContinue }
$env:GIT_COMMIT = $commit
$mvn = Join-Path $env:USERPROFILE '.m2\wrapper\dists\apache-maven-3.9.12\59fe215c0ad6947fea90184bf7add084544567b927287592651fda3782e0e798\bin\mvn.cmd'
if (-not (Test-Path $mvn)) { $mvn = 'mvn' }
$env:JAVA_HOME = if (Test-Path 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot') {
  'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
} else { $env:JAVA_HOME }
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;" + $env:Path }
$env:JAVA_TOOL_OPTIONS = '-Xms512m -Xmx512m'
& $mvn -B -ntp -Pintegration `
  "-Dcontent.perf.enabled=true" `
  "-Dtest=ContentApplicationTest" `
  "-Dit.test=PerfHarnessIT" `
  "-DfailIfNoTests=false" `
  verify 2>&1 | Tee-Object (Join-Path $out 'harness.log')
$exit = $LASTEXITCODE
if ($exit -eq 0 -and $ArchiveToDocs) {
  $destRoot = Join-Path $root 'docs\perf-evidence\capacity'
  New-Item -ItemType Directory -Force -Path $destRoot | Out-Null
  $name = Split-Path $out -Leaf
  Copy-Item -Recurse -Force $out (Join-Path $destRoot $name)
  Write-Host "ARCHIVED=$destRoot\$name"
}
Write-Host "PERF_OUT=$out exit=$exit"
exit $exit
