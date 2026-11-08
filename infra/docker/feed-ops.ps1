param([Parameter(Mandatory=$true)][ValidateSet('rebuild','replay')][string]$Action,[Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$Reason,[long]$TaskId=0)
$ErrorActionPreference='Stop'
if($Reason.Length -gt 512){throw 'Reason must contain at most 512 characters'}
if($Action -eq 'replay' -and $TaskId -le 0){throw 'A positive TaskId is required for replay'}
$contentRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$contentJar=Join-Path $contentRoot 'target/content-0.1.0-SNAPSHOT.jar'
if(-not(Test-Path -LiteralPath $contentJar)){throw 'Build the executable JAR before running the operator command'}
$javaExecutable=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{'java'}
$operatorArgs=if($Action -eq 'replay'){@('replay',[string]$TaskId,$Reason)}else{@('rebuild',$Reason)}
& $javaExecutable '-Dloader.main=io.github.xiiiiimu.content.worker.FeedOpsCommand' '-cp' $contentJar 'org.springframework.boot.loader.launch.PropertiesLauncher' @operatorArgs
exit $LASTEXITCODE
