# Traffic Killer Android

原生 Android 版流量消耗与上限控制工具（Kotlin + OkHttp + Foreground Service）。由于运行在原生客户端而非浏览器页面，不存在浏览器同源/CORS 跨域校验限制，可直接拉取 `cloudphoneh5.buy.139.com`、`cloud.139.com` 等返回 HTTP 200 的直链资源。

## 核心能力

- **精准流量统计**：以系统内核级 `TrafficStats.getUidRxBytes` + `TrafficStats.getUidTxBytes` 增量为准，反映实际网络传输字节；当部分 ROM 返回 `TrafficStats.UNSUPPORTED` 时自动降级为应用层流字节统计。
- **流量上限自动刹停**：支持自定义 GB 上限（如 `1`、`5`、`10` 或小数 `0.5`，`0` 或留空为不限）。监控协程每 250ms 检查且各下载线程每次 `read` 后即时检查，达到上限立即中断全部连接并停止服务。
- **咪咕直播动态取址**：预设中的「咪咕视频直播（动态取址·临时）」不写死时效链接，而是每次开始前从取址接口获取最新的 HLS 播放地址，逐层解析到 `.ts` 分片后轮转下载，并把解析结果缓存 15 秒以减少重复解析。
- **前台服务保活**：使用 `dataSync` 前台服务 + `PARTIAL_WAKE_LOCK` + 常驻进度通知栏（含快捷停止按钮）。

## 目录结构

- `app/src/main/java/com/traffickiller/android/MainActivity.kt`：主界面交互与参数持久化
- `app/src/main/java/com/traffickiller/android/TrafficForegroundService.kt`：后台多线程下载、网速采样与上限刹停
- `app/src/main/java/com/traffickiller/android/TrafficStatsTracker.kt`：UID 底层字节统计 + 流计数兜底
- `app/src/main/java/com/traffickiller/android/PresetNodes.kt`：预设节点与单位换算工具
- `app/src/main/java/com/traffickiller/android/TrafficState.kt`：UI 与服务共享状态

## 编译运行

1. 使用 **Android Studio**（Ladybug 或更新版本，JDK 17，Android SDK 35）直接打开 `traffic-killer-android` 目录。
2. 等待 Gradle 自动同步并下载依赖（包含 `gradle-8.9` 与 `okhttp:4.12.0`）。
3. 连接设备或模拟器后点击 **Run** 安装运行；或执行：
   ```bash
   gradle assembleDebug
   ```

## GitHub Actions 自动编译

仓库已配置工作流 `.github/workflows/build-apk.yml`：每次推送到 `main`、手动触发（`workflow_dispatch`）或推送 `v*` 标签时，都会自动运行单元测试并构建 `traffic-killer-debug.apk`，可在仓库的 **Actions -> Artifacts** 中直接下载安装（`v*` 标签还会自动附带到 GitHub Release）。
