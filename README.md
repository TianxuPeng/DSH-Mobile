# dshMobile 1.1.2

基于扫码浏览器重新打包的独立 App，并为 DSH 页面增加了**主动余额查询标题栏**。




![dshMobile Logo](docs/Whale.png)

## 直接安装

- APK：[dshMobile-1.1.2.apk](dist/dshMobile-1.1.2.apk)
- 工作区根目录副本：`..\dshMobile-1.1.2.apk`
- 大小：1,180,926 字节（约 1.13 MB）
- 版本：`1.1.2`（versionCode 4，可直接覆盖升级 1.0.0/1.1.0/1.1.1）
- 系统要求：Android 6.0+（minSdk 23），targetSdk 34
- 包名：`com.dshmobile.app`

APK SHA-256：

```text
b0588a1bc7652178e3700d2eac66b6deab0bcccc7646379127a251e93f67f22d
```

签名证书 SHA-256：

```text
AC:A1:F5:57:A1:17:0A:C5:2C:45:CC:EB:18:39:E6:87:29:A4:0F:90:67:6D:B9:F6:05:39:D0:F9:DC:7D:CA:5F
```

## 主动查询余额（新增）

App 会在 DSH 页面连接成功后**立即主动向 DSH Desktop 发一次查询指令**，之后**每 5 分钟**查询一次：

1. 在已认证的 DSH 页面内执行 `POST /dsh-pet-7340/balance`，让电脑端 dsh-pet 立即查询 DeepSeek 真实余额；
2. 随后读取 `/dsh-pet-7340/state` 的余额快照；
3. 将余额和峰/谷更新到原生标题栏。

关键点是：这个查询**运行在当前 DSH 页面内**，走页面已有的登录连接，而不是单独连接电脑的局域网端口。因此只要页面本身能打开，Wi-Fi 或移动数据（远程/隧道）都能发起查询。

### 1.1.2 互联网余额修复

1.1.1 及以前，页面内查询直接访问 dsh-pet 的 `/dsh-pet-7340/...`。在互联网远程页面上，这个插件路径可能没有被 dsh-mobile 网关转发，因此标题栏一直是 `--`。

1.1.2 增加了 dsh-mobile 本地扩展 **`balance-title`**：

```text
GET /mobile-access/extensions/balance-title/routes/refresh
```

- 该路由由 dsh-mobile 网关**自己**提供，和手机页面同源，因此 LAN / 互联网远程都能访问。
- 它由 dsh-mobile 的配对会话认证，不需要单独的令牌，也不依赖 43130 端口。
- 扩展在电脑上运行，调用本机 dsh-pet 的 `/balance` 和 `/state`，再把余额返回给 App。
- App 查询顺序：**balance-title 扩展 → dsh-pet 页面路由 → 局域网 43130 桥**。
- 扩展已安装到：

```text
%APPDATA%\dsh-desktop\harness\mobile-access\extensions\balance-title\
```

- 扩展状态：`in-process / ready`。

> 该修复要求手机页面来自 **dsh-mobile 完整手机页**（包括互联网远程入口）。DSH 内置的 `43127` 简易手机页没有 dsh-mobile 扩展路由，互联网下仍然无法显示余额；用内置页时只能走局域网回退。

### 1.1.1 的“连接成功立即刷新”

- DSH 页面加载完成时立即查询一次，不等 5 分钟。
- 配对页 URL 切换为已连接页面时（包括 SPA 历史记录变化）立即补一次查询。
- 如果首次查询失败（例如刚配对完成、连接尚未就绪），每 **10 秒**重试一次，最多 6 次；拿到第一笔可用余额后，以这次成功为起点重新计算 5 分钟周期。
- 之后正常按 5 分钟周期刷新；查询只在 App 前台、DSH 页面内运行。

如果 DSH 页面内无法访问 dsh-pet 路由（典型是 DSH 内置的 43127 简易手机页），App 会自动回退到电脑端局域网桥：

- 请求 `http://<电脑IP>:43130/refresh`；
- 桥仅在收到该请求时刷新，不再自行轮询；
- 回退模式只适用于局域网。

### 页面内查询的实测与边界

- 1.1.2 的扩展路由已安装，dsh-mobile 本地管理接口返回 `balance-title / ready`。
- 扩展的 `host.mjs` 对真实 dsh-pet 执行成功，返回 `CNY 26.38 / idle`。
- App 的最终 JavaScript 已用同源扩展路由返回的 JSON 验证，标题栏数据路径为 `CNY 26.48 / idle`。
- 已在模拟 Android 环境中验证：页面查询不可用时，App 会自动回退到局域网桥的 `/refresh`，并把标题栏更新为同一余额。
- 尚无 Android 真机，且没有可用的 dsh-mobile 已认证测试会话来代替手机验证“页面内查询 → dsh-mobile 网关 → dsh-pet”这一段；需要你在手机上扫码配对后确认。
- 使用移动数据时，必须使用 **dsh-mobile 完整手机页** 的配对二维码；DSH 内置的 43127 简易页没有 dsh-pet 路由，移动数据下无法回退局域网桥。
- 主动查询只在 App 位于 DSH 页面且在前台时运行；退出页面或 App 进入后台会停止，不会在后台持续发请求。

## 余额标题栏

```text
[完整 logo]  余额  ¥28.91  [谷]
```

- 只在从 DSH 配对二维码打开的页面显示。
- 普通网页不显示；没有展开/收起和上下滑手势，没有统计表。
- 查询失败、电脑端不可达或页面查询不可用时显示：

```text
[logo]  余额  --  [--]
```

- 无 URL 栏、前进/后退、刷新、首页、标签页或菜单。
- 原生条位于网页上方，DSH 页面自身标题栏仍在下方；未修改 DSH Desktop。

### 峰 / 谷

按北京时间（Asia/Shanghai）：

| 时间 | 显示 |
| --- | --- |
| 周一至周五 09:00–12:00、14:00–18:00 | **峰** |
| 其余时间以及周六、周日 | **谷** |

## DSH 配对与 HTTPS CA

dsh-mobile 的局域网网关使用一份安装时生成的私有 CA。为了让 App 的 WebView 能打开 `https://<电脑IP>:3443` 的配对页，当前 CA 已随 APK 打包：

```text
res/raw/dshm_ca.pem
```

- 这让你在 Wi-Fi 下也能用本 App 打开 dsh-mobile 的 HTTPS 页面并完成配对。
- 如果 dsh-mobile 重新生成 TLS CA，需要重新获取 `%APPDATA%\dsh-desktop\harness\mobile-access\tls\ca.pem` 并重新打包 App。
- App 的网络安全配置只在 Android 7.0+ 完整生效；Android 6 上仍可使用 DSH 内置 HTTP 手机页 + 局域网桥回退。
- 公网远程入口使用公共可信证书，仍由系统信任。

> 新 App 需要自己扫一次 dsh-mobile 配对二维码并完成配对；它**不会复用**官方 DeepSeek Harness Android App 已保存的设备令牌。

## 数据链路

```text
DeepSeek 余额 API
    ↑  dsh-pet（使用电脑端已有凭据）
手机 DSH 页面内  GET /mobile-access/extensions/balance-title/routes/refresh
    ↑  同一页面连接：dsh-mobile 网关（Wi-Fi / 互联网远程）
dshMobile 原生标题栏

如果扩展路由不可用：
App 回退到  POST /dsh-pet-7340/balance + GET /state
            ↑ 同一页面连接
再回退到   http://<电脑IP>:43130/refresh
            ↑ 电脑端局域网桥
dsh-pet
```

- 电脑端余额桥已安装并登录自启：
  - `%LOCALAPPDATA%\dshMobile\balance-bridge`
  - 启动文件夹：`DSH Balance Bridge.lnk`
  - 日志：安装目录下 `bridge.log`
  - 源码：[../dsh-balance-bridge/README.md](../dsh-balance-bridge/README.md)
- 桥不再每 5 分钟自行轮询，只响应手机的主动刷新请求。

### DSH 插件版余额桥

电脑端桥也已经整理为可上传 GitHub 的 DSH bundle 插件：

- 源码与说明：[../dsh-balance-bridge-plugin/README.md](../dsh-balance-bridge-plugin/README.md)
- 可安装包：[dsh-balance-bridge-1.0.0.tgz](../dsh-balance-bridge-1.0.0.tgz) · 11,710 字节 · SHA-256 `a185b68657dc58b918b3b44adb7530848c13873990c9cd8cf30181025cebd30e`
- 插件测试：6 个通过（余额、令牌、被动刷新、dsh-pet 不可用、DSH 生命周期）
- 插件默认不自行轮询；手机 `/refresh` 才刷新
- 插件需要配置 `DSH_BALANCE_TOKEN`；若要服务本 APK 的局域网回退，令牌需与 APK 中的值一致
- 插件和当前独立桥不能同时占用 43130；二选一
- **互联网修复扩展**也在这个源码仓库中：[balance-title](../dsh-balance-bridge-plugin/mobile-extension/README.md)，已安装并处于 `ready`

当前电脑仍运行独立桥，DSH 插件尚未安装（避免热装影响当前会话）。dsh-mobile 的 `balance-title` 扩展已安装，是当前互联网余额的主路径。

## 手机显示 `--` 时

1. 确认 App 已通过 dsh-mobile 配对二维码完成配对，并停留在 DSH 页面。
2. 确认电脑端 DSH 与 dsh-pet 正常（dsh-pet 的余额查询能返回数据）。
3. Wi-Fi 回退时确认手机与电脑同网、桥进程监听 43130、防火墙放行。
   右键 `/dsh-balance-bridge/allow-firewall.cmd` 选择“以管理员身份运行”一次。
4. 互联网下如果仍显示 `--`：
   - 确认手机页面是 **dsh-mobile 完整网关页**，不是 DSH 内置 43127 简易页；
   - 在电脑上检查 `balance-title` 扩展是否为 `ready`；
   - 重新加载手机页面，让 dsh-mobile 重新拉取扩展清单。

## 新 App 的变化

| 项目 | 1.1.1 | 本版 1.1.2 |
| --- | --- | --- |
| 余额主路径 | 页面内 dsh-pet 路由 | dsh-mobile `balance-title` 同源路由 |
| 互联网余额 | 路由可能不被网关转发，显示 `--` | 网关自己提供扩展路由，可用 |
| 首次刷新 | 页面加载/配对切换立即，失败 10 秒重试 | 不变 |
| 5 分钟周期 | 第一笔成功余额后 | 不变 |
| 连接方式 | 页面连接 + 局域网回退 | 不变 |
| CA 信任 | 打包 dsh-mobile 局域网 CA | 不变 |
| 用量表/展开手势 | 无 | 仍无 |

## 验证

- 13 个自动化测试通过（扫码/URL/DSH 页面识别/无工具栏等）。
- **1.1.2 互联网余额主路径**：dsh-mobile 扩展 `balance-title` 已加载（`ready`），路由由网关同源提供；扩展 handler 对真实 dsh-pet 返回 `CNY 26.38 / idle`，App 最终 JS 用扩展 JSON 返回 `CNY 26.48 / idle`。
- **主动查询回退联调**：1.1.1 在模拟 Android 环境加载 DSH 页面后，页面 JS 超时自动回退到桥 `/refresh`，标题栏更新为 `¥27.43 / 谷`；1.1.2 回退路径未改动。
- Android Lint：**0 errors，10 warnings**（cleartext/自定义 CA 的已知安全提示，以及不裁切全图图标和布局优化提示）。
- Release APK 已通过 v1 + v2 签名和 `zipalign` 校验。
- DSH 插件版余额桥 6 个单元测试通过，已生成 `dsh-balance-bridge-1.0.0.tgz`；包内同时包含互联网修复扩展 `mobile-extension/balance-title`。

完整记录：[docs/verification.txt](docs/verification.txt)；Lint 原文：[docs/lint-results.txt](docs/lint-results.txt)。

> 尚未在真实 Android 手机上验证 dsh-mobile 网关认证后的页面内查询，也没有摄像头真机测试。请先用 App 扫描 dsh-mobile 配对二维码完成配对，再观察 5 分钟刷新；若移动数据下始终为 `--`，请把当时的 DSH 页面地址/连接方式告知我，以便进一步适配。

## 构建

要求 JDK 17、Android SDK Platform 34、Build-Tools 34.0.0。签名配置见 `keystore.properties` 和 `keystore/dshmobile-release.jks`，请备份；升级必须使用同一密钥。

```bash
./gradlew assembleRelease
./gradlew testDebugUnitTest
./gradlew lintRelease
```

## 限制

- 主动查询仅在前台、DSH 页面内运行。
- 不自带后台定时/WorkManager；离开 DSH 页面即停止。
- 首次失败后的快速重试最多 6 次（约 1 分钟）；之后按 5 分钟周期，重新连接成功可能在下一个周期才被捕获。
- 互联网余额要求已安装 dsh-mobile `balance-title` 扩展，并使用 dsh-mobile 完整手机页；未在真机上实测认证后的网关请求。
- 内置 43127 页没有 dsh-mobile 扩展路由，互联网下仍无法显示余额，只能局域网回退。
- 私有 CA 被 dsh-mobile 重新生成后需更新 APK 中的 CA。
- 与上一版一致：无下载、外部 App 协议拦截、URL 栏隐藏、无标签页。

## 校验和文件

除本 README 中的 APK、证书和插件包哈希外，工作区根目录还有：

- [`SHA256SUMS.txt`](../SHA256SUMS.txt)：当前 APK 与插件包
- [`dshMobile-1.1.2.sha256`](../dshMobile-1.1.2.sha256)
- [`dsh-balance-bridge-1.0.0.sha256`](../dsh-balance-bridge-1.0.0.sha256)
- 插件源码目录内的 [`SHA256SUMS`](../dsh-balance-bridge-plugin/SHA256SUMS)

第三方组件与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
