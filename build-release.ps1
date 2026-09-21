[CmdletBinding()]
param(
    [string]$SourceDir = $PSScriptRoot,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '.build-tools\artifacts')
)

$ErrorActionPreference = 'Stop'
$projectDir = (Resolve-Path -LiteralPath $SourceDir).Path
$javaDir = Join-Path $PSScriptRoot '.build-tools\jdk17\jdk-17.0.16+8'
$sdk = Join-Path $PSScriptRoot '.build-tools\android-sdk'
$gradle = Join-Path $PSScriptRoot '.build-tools\gradle-9.3.1\bin\gradle.bat'
$buildTools = Join-Path $sdk 'build-tools\36.0.0'
$keyStore = Join-Path $env:USERPROFILE '.android\debug.keystore'
# Preserve the signing identity used by existing public APKs, including v1.2.4.
# Release/debug is a build variant; it does not require changing the certificate.
$expectedCertificate = '8e967c79287350c333095b15a088f17303b65411cea392551c2820c0d7977b4a'

foreach ($required in @((Join-Path $projectDir 'settings.gradle'), $javaDir, $sdk, $gradle, $buildTools, $keyStore)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build dependency: $required" }
}
$env:JAVA_HOME = $javaDir
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$certificate = & (Join-Path $javaDir 'bin\keytool.exe') -exportcert -rfc -alias androiddebugkey -keystore $keyStore -storepass android 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Unable to read the existing signing certificate' }
$bytes = [Convert]::FromBase64String(($certificate | Where-Object { $_ -notmatch '^-----' }) -join '')
$digest = [Security.Cryptography.SHA256]::Create()
try { $fingerprint = ([BitConverter]::ToString($digest.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
finally { $digest.Dispose() }
if ($fingerprint -ne $expectedCertificate) { throw 'Signing certificate differs from the published APK' }

$revision = (& git -C $projectDir rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Release source must be a Git checkout' }
$sourcePaths = @('app', 'build.gradle', 'settings.gradle', 'gradle.properties')
& git -C $projectDir diff --quiet HEAD -- @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Commit application/build changes before creating a release APK' }
$untracked = & git -C $projectDir ls-files --others --exclude-standard -- app
if ($untracked) { throw 'Untracked application files would make the release differ from its commit' }

Push-Location $projectDir
try {
    & $gradle --no-daemon :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
} finally { Pop-Location }

$source = Get-Content -Raw -LiteralPath (Join-Path $projectDir 'app\build.gradle')
$version = [regex]::Match($source, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($source, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $version -or -not $versionCode) { throw 'Unable to read the release version' }
$unsigned = Join-Path $projectDir 'app\build\outputs\apk\release\app-release-unsigned.apk'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$outputDir = (Resolve-Path -LiteralPath $OutputDirectory).Path
$aligned = Join-Path $outputDir "moontier-v$version-aligned.tmp.apk"
$artifact = Join-Path $outputDir "moontier-v$version-arm64.apk"
try {
    & (Join-Path $buildTools 'zipalign.exe') -P 16 -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
    & (Join-Path $buildTools 'apksigner.bat') sign --ks $keyStore --ks-key-alias androiddebugkey --ks-pass pass:android --out $artifact $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    $signature = & (Join-Path $buildTools 'apksigner.bat') verify --print-certs $artifact
    if ($LASTEXITCODE -ne 0 -or -not ($signature -match "certificate SHA-256 digest: $expectedCertificate")) {
        throw 'Release signature verification failed'
    }
    & (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $artifact
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK is not correctly aligned' }
    $badging = & (Join-Path $buildTools 'aapt.exe') dump badging $artifact
    if ($LASTEXITCODE -ne 0 -or $badging -match '^application-debuggable') { throw 'APK is not a non-debuggable Release build' }
    if (-not ($badging -match "^package: name='cn\.moonflow\.easytier'")) { throw 'Unexpected application package' }
    if (-not ($badging -match "versionCode='$versionCode'.*versionName='$([regex]::Escape($version))'")) {
        throw 'APK version differs from the source version'
    }
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $outputDir "SHA256SUMS-v$version.txt"), "$hash  $([IO.Path]::GetFileName($artifact))`n", $utf8)
    $metadata = [ordered]@{ version = $version; versionCode = [int]$versionCode; commit = $revision; variant = 'release'; debuggable = $false; certificateSha256 = $fingerprint; sha256 = $hash; apk = [IO.Path]::GetFileName($artifact) }
    [IO.File]::WriteAllText((Join-Path $outputDir "moontier-v$version-release.json"), ($metadata | ConvertTo-Json) + "`n", $utf8)
    Write-Output "APK: $artifact"
    Write-Output "SHA256: $hash"
    Write-Output "Commit: $revision"
} finally {
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned -Force }
}
