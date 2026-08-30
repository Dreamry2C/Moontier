# MoonTier Root manager client

This is a small control-plane client for EasyTier's official `WebClientService` RPC.
It mirrors the calls used by the Linux GUI service mode and never carries VPN data.

The source uses the v2.6.4 schema as a reproducible build baseline only. Runtime
compatibility is capability-based: no core version is checked or pinned. Cores that
provide the official `WebClientService` can be used, while older cores without that
service return their native RPC error. The client only sends the stable base fields
and reduces status responses to fields MoonTier actually reads, so added fields and
enums in newer cores do not affect the Android parser.

The Android app bundles the static `aarch64-unknown-linux-musl` release binary as
`app/src/main/assets/root/moontier-root-manager`. The separately downloaded official
`easytier-core` remains the only Root-mode network core.

Build with:

```powershell
rustup target add aarch64-unknown-linux-musl
cargo zigbuild --manifest-path tools/root-manager-client/Cargo.toml --target aarch64-unknown-linux-musl --release
```
