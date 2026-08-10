# White Noise TV

一个面向 Android TV 的白噪音混音器，使用原生 Android View 和 Java 编写，不依赖 AndroidX 或第三方播放库。

## 已实现

- 最低支持 Android 6.0（API 23），`targetSdk 28`，纯 Java 代码可同时运行在 32 位和 64 位 Android TV 设备上。
- 绵柔雨声、暴雨声、海浪声三条本地实录音源。
- 每条音源独立开关、独立音量；多条 `MediaPlayer` 同时播放形成混音。
- 启动应用后自动播放上次保存的混音；播放服务使用前台服务，离开界面后继续播放。
- 每条音源整行只有一个 D-pad 焦点：上下键移动音源，左右键以 5% 步进调整当前音量，确定键开启或关闭，不需要在按钮和音量条之间切换。
- 每条音源均从提供的长视频录音中选取稳定片段，制作为 120 秒、44.1 kHz、立体声、16-bit PCM WAV。转换不做降噪、均衡、响度归一化或有损重编码；循环边界使用 5 秒线性交叉淡化，避免明显切换或叠加削波。

## 构建

用 Android Studio 打开项目根目录，安装 Android SDK 35 后运行 `app` 配置即可。项目没有提交 Gradle Wrapper 二进制，若使用命令行，请使用 Gradle 8.7 或由 Android Studio 导入生成 Wrapper。

```text
minSdk 23
compileSdk 35
targetSdk 28
```

音频 WAV 资源已经放在 `app/src/main/res/raw/`。若需要从合法持有、具备使用授权的三个原视频重新制作：

```bash
tools/prepare_source_audio.sh \
  "/path/to/soft-rain-source.mp4" \
  "/path/to/heavy-rain-source.mp4" \
  "/path/to/ocean-waves-source.mp4"
```

脚本依赖 FFmpeg，并使用当前选定的固定时间点生成相同长度和循环结构的文件。发布应用或公开仓库前，请确认原始录音及其摘录允许再分发。
