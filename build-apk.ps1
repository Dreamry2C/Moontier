[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$projectDir = $repoRoot
$javaHome = Join-Path $repoRoot '.build-tools\jdk17\jdk-17.0.16+8'
$androidSdk = Join-Path $repoRoot '.build-tools\android-sdk'
$gradle = Join-Path $repoRoot '.build-tools\gradle-9.3.1\bin\gradle.bat'
$signer = Join-Path $androidSdk 'build-tools\36.0.0\apksigner.bat'
$keyStore = Join-Path $env:USERPROFILE '.android\debug.keystore'
# This public certificate is the signing identity of the published 1.2.4 APK.
$expectedCertificate = '8e967c79287350c333095b15a088f17303b65411cea392551c2820c0d7977b4a'

foreach ($required in @((Join-Path $projectDir 'settings.gradle'), $javaHome, $androidSdk, $gradle, $signer, $keyStore)) {
    if (-not $required -or -not (Test-Path -LiteralPath $required)) {
        throw "Missing build dependency: $required"
    }
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
$env:Path = "$(Join-Path $javaHome 'bin');$(Join-Path $androidSdk 'platform-tools');$env:Path"

# Fail before Gradle can generate a different debug key. This is the standard
# Android debug password, not a release credential.
$certificate = & (Join-Path $javaHome 'bin\keytool.exe') -exportcert -rfc -alias androiddebugkey -keystore $keyStore -storepass android 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Unable to read the existing signing certificate' }
$certificateBytes = [Convert]::FromBase64String(($certificate | Where-Object { $_ -notmatch '^-----' }) -join '')
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $fingerprint = ([BitConverter]::ToString($sha256.ComputeHash($certificateBytes))).Replace('-', '').ToLowerInvariant()
} finally { $sha256.Dispose() }
if ($fingerprint -ne $expectedCertificate) {
    throw 'Signing key differs from the published APK. Restore the original key; do not uninstall the app.'
}

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
$signature = & $signer verify --print-certs $sourceApk
if ($LASTEXITCODE -ne 0 -or -not ($signature -match "certificate SHA-256 digest: $expectedCertificate")) {
    throw 'Built APK signature verification failed; artifact will not be delivered'
}
$artifactName = "moontier-v$versionName-$shortCommit$dirtySuffix-debug.apk"
$artifactPath = Join-Path $artifactDir $artifactName
$latestPath = Join-Path $repoRoot '.build-tools\moontier-debug-latest.apk'

New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $artifactPath -Force
Copy-Item -LiteralPath $sourceApk -Destination $latestPath -Force

Write-Output "APK: $artifactPath"
Write-Output "Latest alias: $latestPath"
