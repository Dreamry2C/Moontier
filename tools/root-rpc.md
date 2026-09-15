# Root control plane

Root mode downloads the official Linux Core and CLI for the ELF architecture of
Android's native linker. ARM translation advertised by an emulator is not a native
Linux ABI. ZIP imports and downloads are checked by ELF header and `--version`
before either installed executable is replaced.

The app talks to Core's loopback `WebClientService` directly. `RootRpc` implements
the framing in EasyTier v2.6.4 `tunnel/packet_def.rs`, `common.proto`, and
`easytier-rpc-build`. Method indexes on the wire start at 1. `RootRpcManager`
projects only the fields the UI consumes. Unknown protobuf fields are skipped;
responses are fragmented and bounded to 8 MiB, with a per-call deadline. The
connection stays open across polls and is closed on Core restart or app release.
Failed mutations are not replayed automatically. No native manager helper or
additional web listener is installed.

Run normal tests with `:app:testDebugUnitTest`. For the optional real-Core test,
start an isolated official Core with an empty config directory, `--daemon`, and a
loopback RPC port, then forward that port with an explicit ADB serial. Set
`MOONTIER_TEST_RPC_PORT` to the forwarded host port before Gradle. Never point this
test at a user's running Core. It validates a fragmented response containing an
ACL and exercises create/list/snapshot/delete on a no-TUN, no-peer test network.
