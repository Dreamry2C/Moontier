[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$projectDir = $repoRoot
$javaHome = Join-Path $repoRoot '.build-tools\jdk17\jdk-17.0.16+8'
$androidSdk = Join-Path $repoRoot '.build-tools\android-sdk'
$gradle = Join-Path $repoRoot '.build-tools\gradle-9.3.1\bin\gradle.bat'

foreach ($required in @((Join-Path $projectDir 'settings.gradle'), $javaHome, $androidSdk, $gradle)) {
    if (-not $required -or -not (Test-Path -LiteralPath $required)) {
        throw "Missing build dependency: $required"
    }
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
$env:Path = "$(Join-Path $javaHome 'bin');$(Join-Path $androidSdk 'platform-tools');$env:Path"

Push-Location $projectDir
try {
    & $gradle --no-daemon :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$buildFile = Join-Path $projectDir 'app\build.gradle'
$versionLine = Select-String -LiteralPath $buildFile -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionLine) {
    throw 'Unable to read versionName from app/build.gradle'
}
$versionName = $versionLine.Matches[0].Groups[1].Value
$shortCommit = (& git -C $repoRoot rev-parse --short=8 HEAD).Trim()
$dirtySuffix = if (& git -C $repoRoot status --porcelain) { '-dirty' } else { '' }
$artifactDir = Join-Path $repoRoot '.build-tools\artifacts'
$sourceApk = Join-Path $projectDir 'app\build\outputs\apk\debug\app-debug.apk'
$artifactName = "moontier-v$versionName-$shortCommit$dirtySuffix-debug.apk"
$artifactPath = Join-Path $artifactDir $artifactName
$latestPath = Join-Path $repoRoot '.build-tools\moontier-debug-latest.apk'

New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $artifactPath -Force
Copy-Item -LiteralPath $sourceApk -Destination $latestPath -Force

Write-Output "APK: $artifactPath"
Write-Output "Latest alias: $latestPath"
