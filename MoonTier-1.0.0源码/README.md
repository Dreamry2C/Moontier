# MoonTier 1.0.0 Source

Native Android launcher for EasyTier.

- Package: cn.moonflow.easytier
- Stack: Kotlin + Jetpack Compose + C++ JNI bridge
- ABI: arm64-v8a
- Base commit: cfda682

## Contents

- app/: Android application source
- app/src/main/jniLibs/arm64-v8a/libeasytier_ffi.so: prebuilt VPN core
- tools/root-manager-client/: root-mode helper tool (Rust)

## Requirements

- JDK 17
- Android SDK (compileSdk 36)
- NDK 27.2.12479018
- Gradle 9.3.1
- This package has no Gradle wrapper; use a local Gradle installation

## Build

`powershell
gradle assembleDebug
gradle assembleRelease
`

The repository root also provides `build-apk.ps1`. With the local tools under
`.build-tools`, it builds a debug APK and writes a versioned artifact such as
`moontier-v1.1.0-abcdef12-debug.apk` under `.build-tools/artifacts/`, while also
refreshing `.build-tools/moontier-debug-latest.apk`.

## Signing

The release keystore (qtet-release.keystore) is intentionally not included. assembleRelease produces an unsigned APK by default; configure signingConfigs with your own keystore before distributing a release.
