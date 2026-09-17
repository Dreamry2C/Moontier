[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$adb=Join-Path $repoRoot '.build-tools\android-sdk\platform-tools\adb.exe'
$fixture=Join-Path $repoRoot 'tmp\root-log-test'
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$utf8=New-Object System.Text.UTF8Encoding($false)
$script=[IO.File]::ReadAllText((Join-Path $repoRoot 'app\src\main\assets\root\moontier_root.sh')).Replace("`r`n","`n")
[IO.File]::WriteAllText((Join-Path $fixture 'production.sh'),$script,$utf8)
[IO.File]::WriteAllText((Join-Path $fixture 'fast.sh'),$script.Replace('CHECK_INTERVAL_SECONDS=3600','CHECK_INTERVAL_SECONDS=2'),$utf8)
foreach($pair in @(@('root-logs.sh','test.sh'),@('root-log-writer.sh','writer.sh'))){
    $source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot ('tests\'+$pair[0]))).Replace("`r`n","`n")
    [IO.File]::WriteAllText((Join-Path $fixture $pair[1]),$source,$utf8)
}
& $adb -s $Serial shell mkdir -p /data/local/tmp/moontier-log-test
if($LASTEXITCODE -ne 0){throw 'Cannot prepare test directory'}
& $adb -s $Serial push "$fixture\." /data/local/tmp/moontier-log-test/
if($LASTEXITCODE -ne 0){throw 'Cannot push test fixtures'}
& $adb -s $Serial shell su -c 'sh /data/local/tmp/moontier-log-test/test.sh'
if($LASTEXITCODE -ne 0){throw 'Root log tests failed'}
