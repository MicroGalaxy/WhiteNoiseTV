# White Noise TV

一个面向 Android TV 的白噪音混音器，使用原生 Android View 和 Java 编写，不依赖 AndroidX 或第三方播放库。

## 已实现

- 最低支持 Android 6.0（API 23），`targetSdk 28`，纯 Java 代码可同时运行在 32 位和 64 位 Android TV 设备上。
- 绵柔雨声、暴雨声、海浪声、雷声、溪水声、风声、火堆声七条本地音源。
- 每条音源独立开关、独立音量；多条 `MediaPlayer` 同时播放形成混音。
- 启动应用后自动播放上次保存的混音；播放服务使用前台服务，离开界面后继续播放。
- 每条音源整行只有一个 D-pad 焦点：上下键移动音源，左右键以 5% 步进调整当前音量，确定键开启或关闭，不需要在按钮和音量条之间切换。
- 音源由 `tools/generate_audio.py` 生成。每条是约 28 秒的 44.1 kHz / 16-bit PCM WAV，分别加入雨滴、浪涌、雷鸣、气泡、阵风和火星爆裂等可辨识事件，并在循环点使用 2 秒等功率交叉淡化，避免直接拼接造成咔哒声或明显断点。

## 构建

用 Android Studio 打开项目根目录，安装 Android SDK 35 后运行 `app` 配置即可。项目没有提交 Gradle Wrapper 二进制，若使用命令行，请使用 Gradle 8.7 或由 Android Studio 导入生成 Wrapper。

```text
minSdk 23
compileSdk 35
targetSdk 28
```

音频 WAV 资源已经生成并放在 `app/src/main/res/raw/`。若需要重新生成：

```bash
python3 tools/generate_audio.py
```

脚本只使用 NumPy 和 Python 标准库，生成过程是确定性的；重新生成会得到相同的音源。音频为本项目内生成素材，不依赖网络或运行时下载。
