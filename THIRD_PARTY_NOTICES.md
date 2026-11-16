# 第三方组件与许可证说明

本应用（dshMobile）自身代码按你的项目需要自由修改和使用。下列第三方组件分别遵循其原始许可证。

## AndroidX / CameraX

- 组件：`androidx.activity:activity:1.8.2`、`androidx.camera:camera-core:1.3.4`、`androidx.camera:camera-camera2:1.3.4`、`androidx.camera:camera-lifecycle:1.3.4`、`androidx.camera:camera-view:1.3.4`，以及它们的传递依赖（AndroidX Core、Lifecycle、AppCompat Resources 等）。
- 版权：Android Open Source Project / Google LLC 及贡献者。
- 许可证：Apache License 2.0。
- 项目主页：https://android.googlesource.com/platform/frameworks/support
- 许可证文本：https://www.apache.org/licenses/LICENSE-2.0

## ZXing Core

- 组件：`com.google.zxing:core:3.5.3`。
- 版权：ZXing authors。
- 许可证：Apache License 2.0。
- 项目主页：https://github.com/zxing/zxing
- 许可证文本：https://www.apache.org/licenses/LICENSE-2.0

## Kotlin Standard Library

- 组件：`org.jetbrains.kotlin:kotlin-stdlib:1.8.22`（作为 CameraX 的传递依赖打包）。
- 版权：JetBrains s.r.o. 及 Kotlin 贡献者。
- 许可证：Apache License 2.0。
- 项目主页：https://kotlinlang.org/
- 许可证文本：https://www.apache.org/licenses/LICENSE-2.0

## Android System WebView / Chromium

- 用途：应用不打包浏览器引擎，直接使用 Android 设备系统提供的 WebView。
- Android System WebView 由 Google 基于 Chromium 构建并通过系统/应用商店更新；Chromium 是开源项目。
- Chromium 项目主页：https://www.chromium.org/
- Chromium 源码：https://source.chromium.org/chromium
- Chromium 许可证：https://chromium.googlesource.com/chromium/src/+/main/LICENSE

WebView 的实现与版本由设备厂商和系统更新决定，本应用不修改也不分发 WebView 二进制文件。

## 测试依赖（不进入 APK）

以下依赖只在开发机测试时使用，不会打包进 APK：

- JUnit 4.13.2 — Eclipse Public License 1.0
- Robolectric 4.13 — MIT License
- 以及 Robolectric 测试运行时下载的 Android 框架运行时（android-all-instrumented），仅用于本机测试。

## Apache License 2.0 摘要

Apache License 2.0 允许在遵守许可证条件的前提下使用、修改和再分发软件，包括保留版权和许可证声明。完整条款请以 https://www.apache.org/licenses/LICENSE-2.0 为准。

## dsh-mobile

- 项目：dsh-mobile（作者：saya-ch）。
- 用途：本 App 的二维码配对协议（`/mobile-access/pair`、`/mobile-access/auth/native-pair`、CA 固定与配对 Cookie）参考该项目公开的 Android 实现，以兼容官方 App 的二维码。
- 许可证：Apache License 2.0。
- 项目主页：https://github.com/saya-ch/dsh-mobile
- 本 App 未打包 dsh-mobile 的 APK、图标或前端素材，也未修改其源码。

## Node.js（仅电脑端桥）

- 电脑端余额桥使用本机安装的 Node.js 运行，不随 APK 分发。
- Node.js 许可证：MIT。
- 项目主页：https://nodejs.org/
