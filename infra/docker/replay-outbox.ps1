param(
    [Parameter(Mandatory=$true)][ValidateRange(1,9223372036854775807)][long]$EventId,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$Reason,
    [string]$Jar = (Join-Path $PSScriptRoot '../../target/content-0.1.0-SNAPSHOT.jar')
)
$ErrorActionPreference='Stop'
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw 'Build the application JAR before replay.' }
if ($Reason.Length -gt 1024 -or [string]::IsNullOrWhiteSpace($Reason)) { throw 'A nonblank reason of at most 1024 characters is required.' }
$relayJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
& $relayJava '-Dloader.main=io.github.xiiiiimu.content.worker.OutboxReplayCommand' -cp $Jar org.springframework.boot.loader.launch.PropertiesLauncher "$EventId" $Reason
if ($LASTEXITCODE -ne 0) { throw "Replay failed with exit code $LASTEXITCODE. No success is implied." }
