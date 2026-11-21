param([Parameter(Mandatory)][string]$MetaJson,[Parameter(Mandatory)][int]$Threads,[Parameter(Mandatory)][string]$RunId,[string]$InjectorJfrSettings='',[switch]$DisableInjectorInlineCaches)
$ErrorActionPreference='Stop'
$env:JAVA_HOME='C:/Users/西木/.jdks/ms-17.0.17'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:JM_LAUNCH="$env:JAVA_HOME/bin/java.exe"
# setenv.bat overrides HEAP; later JVM_ARGS pin the ACTUAL successful 1k settings.
# Retain JMeter standard Java-17 opens and GC options. Do not edit setenv.bat.
$env:JMETER_COMPLETE_ARGS=$null
$env:JVM_ARGS='-Xms512m -Xmx3072m -Xss256k -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom -Dgroovy.target.indy=true'
# Diagnostic experiment only: removes ICBufferFull in this JVM, but has NOT
# validated 5000-way start synchrony. Keep opt-in; do not present it as a capacity fix.
if($DisableInjectorInlineCaches){$env:JVM_ARGS+=' -XX:-UseInlineCaches'}
$fresh = & python "$PSScriptRoot/refresh_commerce_tokens.py" $MetaJson
if ($LASTEXITCODE -ne 0) { throw 'Test identity refresh failed' }
$MetaJson = [string]($fresh | Select-Object -Last 1)
$m=Get-Content $MetaJson -Raw | ConvertFrom-Json
& python -c "import sys;sys.path.insert(0,'infra/jmeter');from flash_acceptance import snapshot;s=snapshot(int(sys.argv[1]));assert not s['requests'] and s['sold']==0 and s['orders'][0]==0 and s['remaining']==100 and s['entitlements'][0]==0,'Fresh stock-100 campaign required'" ([string]$m.campaignId)
if ($LASTEXITCODE -ne 0) { throw 'Campaign is not pristine; create a new isolated campaign' }
$obsDir="target/flash-observer-$RunId"
New-Item -ItemType Directory $obsDir | Out-Null
$env:JVM_ARGS += " -XX:HeapDumpPath=$obsDir"
if($InjectorJfrSettings){
 $settings=(Resolve-Path -LiteralPath $InjectorJfrSettings).Path
 $recording=Join-Path (Resolve-Path $obsDir).Path 'injector.jfr'
 $env:JVM_ARGS += " -XX:StartFlightRecording=name=injector,settings=$settings,filename=$recording,maxsize=128m,dumponexit=true"
}
$memory=Get-CimInstance Win32_OperatingSystem | Select-Object TotalVisibleMemorySize,FreePhysicalMemory,FreeVirtualMemory,TotalVirtualMemorySize
$memory | ConvertTo-Json | Set-Content "$obsDir/memory-before.json"
if ($Threads -ge 5000 -and ($memory.FreeVirtualMemory -lt 3145728 -or $memory.FreePhysicalMemory -lt 1048576)) { throw 'Insufficient local memory headroom for 5000 threads; no burst started' }
$observer=Start-Process -FilePath (Get-Command python).Source -ArgumentList @("$PSScriptRoot/purchase_capacity.py",'observe',$obsDir,[string]$m.campaignId,'--interval','2') -PassThru -WindowStyle Hidden -RedirectStandardOutput "$obsDir/observer.out.log" -RedirectStandardError "$obsDir/observer.err.log"
$owner = Get-NetTCPConnection -LocalPort 28080 -State Listen | Select-Object -First 1 -ExpandProperty OwningProcess
$backend = Get-Process -Id $owner
$backendOldMask = $backend.ProcessorAffinity; $backendOldPriority = $backend.PriorityClass
if ([Environment]::ProcessorCount -lt 8) { throw 'CPU isolation requires at least 8 logical processors' }
$backend.ProcessorAffinity = [IntPtr]15
$backend.PriorityClass = 'AboveNormal'
$injectorMask = ([long]$backendOldMask) -band (-bnot 15L)
@{ backendLogicalCpuMask=15; injectorLogicalCpuMask=$injectorMask; backendPriority='AboveNormal'; injectorPriority='BelowNormal'; paced=$false; jmeterStack='256k' } | ConvertTo-Json | Set-Content "$obsDir/cpu-isolation.json"
try {
$root='docs/perf-evidence/commerce/jmeter'
$band="flash-$Threads"
$dir=Join-Path $root "$band-purchase-t$Threads-$RunId"
# The runner creates this unique directory and enforces preflight; no SkipPreflight.
$runnerStatus=0
try {
& "$PSScriptRoot/run-commerce-jmeter.ps1" -MetaJson $MetaJson -Scenario purchase -Threads $Threads -RampSec 1 -Loops 1 -TimeoutMs 30000 -WarmupThreads 0 -BarrierEnabled -BarrierGroupSize $Threads -BarrierTimeoutMs 120000 -CapacityEvidence -SkipHtml -Band $band -RunId $RunId -InjectorCpuMask $injectorMask -LowPriorityInjector
} catch { $runnerStatus=1; Write-Warning 'Runner failed; checking retained evidence' }
python "$PSScriptRoot/flash_acceptance.py" $dir $MetaJson --threads $Threads --observations "$obsDir/observations.ndjson" --runner-exit $runnerStatus
if ($LASTEXITCODE -ne 0) { throw "Flash acceptance failed; evidence retained: $dir" }
$dir
} finally {
  try { if (-not $backend.HasExited) { $backend.ProcessorAffinity = $backendOldMask; $backend.PriorityClass = $backendOldPriority } } catch { Write-Warning 'Backend process settings could not be restored' }
  New-Item -ItemType File "$obsDir/observer.stop" | Out-Null
  if (-not $observer.WaitForExit(15000)) { Write-Warning 'Observer is still finishing its bounded probes' }
}
