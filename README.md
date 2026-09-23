# simple_android_player — 最小 Android 视频播放器（硬解 + 无限循环）

一个刻意保持最小的视频播放器示例，适合作为 Android 原生视频播放的入门参考：

- **MediaExtractor + MediaCodec** 系统原生解码管线，**零第三方依赖**（无 ExoPlayer、无 FFmpeg）
- **优先选用厂商 Codec2 硬解**：自动枚举解码器，优先 `c2.qti.*` / `c2.mtk.*` 等厂商组件，
  排除 `c2.android.*` / `OMX.google.*` 软解；无硬解时回退厂商 OMX 硬解，再回退系统默认
- **无限循环播放**：EOS 后 seek 回开头 + flush 继续，循环接缝做了时间戳单调递增处理，避免卡顿
- **不丢帧**：每一帧都渲染（正常帧按时间戳同步，迟到帧立即上屏）
- **不含音频**（刻意裁剪，保持最简）
- **内置示例视频**（res/raw/sample_video.mp4，10 秒 H.264），点"内置示例"即可直接验证播放链路
- 界面实时显示**实际使用的解码器名字**，方便确认是否走了硬解

## 环境要求

| 项目 | 要求 |
|---|---|
| 手机系统 | Android 10+（API 29）。Android 12+ 上厂商硬解基本都已是 c2.* 组件 |
| 视频格式 | H.264 / H.265 的 MP4（其他 MediaExtractor 支持的容器也可尝试） |
| 构建 JDK | 17 |

## 构建方式一：Android Studio

1. 下载安装 Android Studio（约 3~4 GB），首次启动向导会自动下载 SDK 与 Gradle 依赖
2. `File → Open` 打开本目录，等 Gradle Sync 完成，`Run ▶` 即可安装到手机

## 构建方式二：命令行（不需要 Android Studio）

只需 JDK 17 + Android 命令行工具，合计约 2~3 GB 磁盘：

```bash
# 1) 安装 JDK 17（Windows 推荐 Temurin：https://adoptium.net）
# 2) 下载 commandline tools：
#    https://developer.android.com/studio#command-line-tools-only
#    解压到例如 C:\android-sdk\cmdline-tools\latest
# 3) 安装 SDK 组件并接受许可
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses

# 4) 在项目根目录写 local.properties（按实际路径调整）
#    sdk.dir=C\:\\android-sdk

# 5) 构建
gradlew.bat assembleDebug        # Windows
./gradlew assembleDebug          # Linux/Mac
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

传到手机点击安装（需允许"安装未知来源应用"），或 `adb install app-debug.apk`。

## 命令行启动（adb，支持传入视频文件路径）

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push test.mp4 /sdcard/Download/

# 首次使用先授予读权限（一次性操作，无弹窗）：
#   Android 13+：
adb shell pm grant com.example.simpleplayer android.permission.READ_MEDIA_VIDEO
#   Android 10~12：
adb shell pm grant com.example.simpleplayer android.permission.READ_EXTERNAL_STORAGE

# 方式一：--es 传路径（推荐，裸路径即可）
adb shell am start -n com.example.simpleplayer/.MainActivity \
    --es videoPath /sdcard/Download/test.mp4

# 方式二：-d 传 URI（file://、content://、或无 scheme 的路径都行）
adb shell am start -n com.example.simpleplayer/.MainActivity \
    -a android.intent.action.VIEW -d "file:///sdcard/Download/test.mp4"
```

说明：

- App 是 `singleTask` 启动模式，**播放中再次执行 am start 换路径，会直接切到新视频**（不重装不重启）
- 支持带容器的视频文件（MP4/MKV/TS 等，H.264/H.265 编码）
- 也可以在系统文件管理器里点开视频，通过 VIEW intent 直接触发本 App 播放

## 如何确认走的是硬解

播放时看两个地方：

1. **App 界面底部**会显示实际解码器名字和判定结果，例如：
   `解码器：c2.qti.avc.decoder（Codec2 硬解）`
2. **logcat**（`adb logcat -s SimplePlayer`）会打印本机全部解码器清单和实际选择。

命名规则速查：

| 解码器名字前缀 | 含义 |
|---|---|
| `c2.qti.*` / `c2.mtk.*` / `c2.security.*` 等厂商名 | **Codec2 硬解** |
| `c2.android.*` | Google 软解 |
| `OMX.qcom.*` / `OMX.MTK.*` 等厂商名 | OMX 时代硬解（旧设备兜底路径） |
| `OMX.google.*` | Google 软解 |

## 代码结构

```
app/src/main/java/com/example/simpleplayer/
├── MainActivity.kt        # UI：选视频/播放/停止/循环开关/解码器信息显示
├── VideoDecodeThread.kt   # 解码线程：Extractor→Codec→Surface，EOS 后回绕循环
└── CodecSelector.kt       # 解码器筛选：优先厂商 c2.* 硬解
```

## 已知限制（刻意保持最简的取舍）

- 无音轨播放；无进度条/拖动；无倍速
- 循环回绕从第一个关键帧开始（SEEK_TO_CLOSEST_SYNC），若视频开头不是关键帧会有少量内容差异
- 单个访问单元大于解码器输入缓冲区容量的极端码流未做拆包处理
- 停止采用 interrupt + 标志位，最长约 10ms（一个 dequeue 超时周期）后退出
