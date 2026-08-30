# MoonTier

MoonTier 是一个面向 Android 的第三方 EasyTier 图形客户端，提供 VPN 与 Root 两种运行模式。

- VPN 模式通过 Android `VpnService` 和内置 FFI Core 运行，不需要 Root。
- Root 模式直接运行 EasyTier 官方发布的 `easytier-core`，适合云手机、常驻设备和需要与其他 VPN 并行使用的场景。

> [!IMPORTANT]
> MoonTier 不是 EasyTier 官方客户端，与 EasyTier 官方无隶属关系。Root、开机启动和无线 ADB 功能会修改设备运行状态，请确认理解风险后再启用。

## 主要功能

- VPN 模式与 Root Core 模式切换。
- 多份网络配置管理，支持设置默认配置。
- TOML 配置导入、导出和原始内容编辑。
- Root 模式下载、更新或从本地 ZIP 导入 `easytier-core` 与 `easytier-cli`。
- Root Core 独立于界面进程运行，普通划掉后台后可继续保持连接。
- 兼容标准 `su -c`、厂商 `su 0 sh -c` 和 BusyBox Root 调用方式。
- 开机自动恢复 Root 网络，默认关闭。
- 开机开启无线 ADB `5555`，默认关闭，兼容部分云手机的 `vdbd`。
- VPN/Root 通用的增强保活通知，默认关闭。
- 用户服务器收藏与 HTTPS/TXT 地址导入。
- 可选的 EasyTier 配置服务器/网页控制台连接。
- Core 调试日志、应用诊断信息和日志导出。
- Root 客户端默认不监听端口，可与另一份监听 `11010` 的 EasyTier 实例并行运行。

## 系统要求

- Android 7.0 或更高版本（API 24+）。
- 仅支持 `arm64-v8a`。
- VPN 模式需要用户授予 Android VPN 权限。
- Root 模式需要 App 能通过 `su` 获得 UID 0 权限。
- Root Core 需要设备内核支持 TUN。

## 安装

1. 从 GitHub Releases 下载最新 APK。
2. 在 Android 设备上允许安装未知来源应用并安装 APK。
3. 首次打开 MoonTier，按需要选择 VPN 或 Root 模式。

如果已安装的版本使用了不同签名，Android 不允许直接覆盖安装。请先导出 TOML 配置或备份应用数据，再卸载旧版本并安装新版本。

应用包名：

```text
cn.moonflow.easytier
```

## 快速开始

### VPN 模式

1. 进入“设置”，选择“VPN 模式 (FFI)”。
2. 新建配置或导入 TOML。
3. 返回“网络配置”，点击“启动”。
4. 在系统弹窗中授予 VPN 权限。

VPN 模式依赖前台 VPN 服务。Android 显示的 VPN 通知属于系统运行要求，不能由“增强保活通知”开关替代。

### Root 模式

1. 进入“设置”，选择“Root 模式 (官方核心)”。
2. 授予 MoonTier Root 权限，并点击“重新检测”确认 Root 可用。
3. 在“下载核心”中下载最新版 Core，或导入官方 aarch64 ZIP。
4. 新建配置或导入 TOML，在“网络配置”中点击“启动”。

若设备只作为客户端连接其他节点，监听地址保持为空即可。MoonTier 会明确生成：

```toml
listeners = []
```

这表示不监听 EasyTier 默认的 TCP/UDP `11010` 等端口，避免与同一设备上的其他 EasyTier 实例冲突。需要接受其他节点主动连接时，再手动填写监听地址。

## 开机与保活设置

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| 开机自动恢复 Root 网络 | 关闭 | 开机后恢复此前由 MoonTier Root manager 管理的网络，仅 Root 模式可用 |
| 开机开启无线 ADB (5555) | 关闭 | 使用 Root 设置并检查 ADB TCP 端口，仅建议在可信网络使用 |
| 增强保活通知 | 关闭 | VPN/Root 通用，启动独立前台通知服务，提高 App 界面进程的后台优先级 |

Android 对“强行停止”应用有特殊限制：应用被系统设置页或 `am force-stop` 强行停止后，开机广播可能不会再次投递，直到用户手动打开一次 App。部分云手机还会清理 App 拉起的 Root 子进程，这是云厂商策略差异，不代表配置丢失。

## 无线 ADB 安全提示

开启无线 ADB 后，可通过 MoonTier 虚拟 IP 连接设备：

```shell
adb connect <MoonTier-IP>:5555
```

ADB 具有很高的设备控制权限。请使用私有网络名和强密码，不要把 `5555` 暴露到公网，也不要在不可信 EasyTier 网络中启用该功能。

## Core 下载

Root 模式下载 EasyTier Release 资源时，会按以下顺序尝试：

1. `https://ghfast.top/`
2. `https://gh-proxy.com/`
3. `https://mirror.ghproxy.com/`
4. GitHub 原始地址

代理全部失败后才回退到 GitHub。也可以下载 EasyTier 官方的 Linux aarch64 ZIP，在 App 内使用“导入 ZIP”离线安装。

## 数据与排障

应用数据位于 Android 私有目录：

```text
/data/data/cn.moonflow.easytier/files/
```

常用文件包括：

- `network_configs.json`：网络配置。
- `settings.json`：应用设置。
- `root/core/`：Root Core、CLI 和管理客户端。
- `root/configs/`：Root manager 使用的 TOML 配置。
- `root/logs/manager.log`：Root Core/manager 日志。
- `diagnostics.log`：MoonTier 应用诊断日志。

排障时可在设置页把 Core 日志级别切换为“调试”，复现问题后导出日志。公开日志前请检查网络名、密钥、服务器地址和设备信息等敏感内容。

## 从源码构建

Android 工程位于 [`MoonTier-1.0.0源码`](./MoonTier-1.0.0源码)。

构建要求：

- JDK 17
- Android SDK 36
- Android NDK `27.2.12479018`
- CMake 3.22.1
- Gradle 9.3.1

项目暂未提供 Gradle Wrapper。准备好本地工具链后，在 Android 工程目录执行：

```powershell
gradle --no-daemon :app:assembleDebug
```

仓库根目录的 `build-apk.ps1` 可使用 `.build-tools` 中的本地工具链构建 APK，并在 `.build-tools/artifacts/` 下生成包含版本号和 Git 短哈希的文件名：

```powershell
.\build-apk.ps1
```

APK 构建产物不会提交到 Git，请通过 GitHub Releases 发布。

## 源码结构

```text
MoonTier-1.0.0源码/
├─ app/                         Android App
│  ├─ src/main/java/            Kotlin/Java 源码
│  ├─ src/main/cpp/             JNI Bridge
│  ├─ src/main/assets/root/     Root manager 与启动脚本资源
│  └─ src/main/jniLibs/         预编译 VPN FFI 库
└─ tools/root-manager-client/   Root manager 客户端源码
```

## 致谢

- 感谢 [EasyTier](https://github.com/EasyTier/EasyTier) 项目及其开发者提供核心组网能力。
- 感谢 EasyTier 支持2 QQ 群（`837676408`）的群友 `50375993` 提供原始源码。MoonTier 在该源码基础上进行修改与维护。

## 免责声明与许可

- 本项目为第三方软件，使用者应自行承担 Root、VPN、无线 ADB、网络代理和设备数据相关风险。
- 请遵守所在地法律法规、EasyTier 及其他第三方依赖的许可要求。
- 本仓库当前未声明独立开源许可证。未经明确授权，不应据此推定可以任意复制、修改或再发布全部源码与二进制文件。
