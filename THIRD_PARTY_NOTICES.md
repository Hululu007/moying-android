# 第三方研究与许可说明

## 1.4.0 当前复用代码与分发声明

本版本选择基于 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 二次开发，固定提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6`，Copyright 2025 Kyant，Apache License 2.0。

移植文件：`PageBackdrop.kt`、`NavLiquidGlass.kt`、`NavigationGlassEffect.kt`。复用上游 `Shaders.kt` 的圆角 SDF、解析梯度、圆弧折射与方向高光，参考 `LayerBackdrop.kt` / `LayerBackdropModifier.kt` / `DrawBackdropModifier.kt` 的 GPU 图层复用与投影，以及 `LiquidBottomTabs.kt` 的速度/按压形变。各源文件注明固定来源与修改；上游此提交不含额外 NOTICE 文件。

本应用改造了 Compose 1.7.6 生命周期、切页隔离、现有导航上下滑坐标、双层透镜、三次纹理采样的低强度色散、触点光学和近似环境照明；缓存 Shader 与模糊，仅参数变化时在 uniforms 更新后重新绑定 RenderEffect。没有导入上游构建脚本、Catalog 资源、Gradle 插件或第三方依赖。

完整许可证与分发声明位于 APK 的 `assets/licenses/Kyant-Backdrop-Apache-2.0.txt` 和 `assets/licenses/NOTICE-Backdrop.txt`。其他评估项目未复制；九个候选、最佳三套源码与授权核查见 `LIQUID_GLASS_RESEARCH.md`。这是范围明确的源文件与依赖核查，没有声称完成所有工具链的安全审计。

当前导航走 GPU GraphicsLayer → 导航窄条 RenderEffect → RuntimeShader，不再使用 Bitmap 回读或高速冻结策略。现有播放器的视频 PixelCopy/BitmapShader 实现保留。完整导航光学需 Android 13+，旧系统和非全屏播放页使用既有高光备用样式。

效果快照依据：[AOSP RenderEffect.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/libs/hwui/jni/RenderEffect.cpp)、[Skia SkRuntimeImageFilter.cpp](https://github.com/google/skia/blob/main/src/effects/imagefilters/SkRuntimeImageFilter.cpp)；不能只更新 RuntimeShader uniforms 而永久复用旧 ImageFilter。

## 1.3.7 及以前的玻璃研究记录

2026-11-02 通过已连接的 GitHub 工具读取以下公开仓库的 README、许可证与实现：

- [ardakazanci/LiquidGlass-JetpackCompose](https://github.com/ardakazanci/LiquidGlass-JetpackCompose)
  - 研究文件：[MainActivity.kt](https://github.com/ardakazanci/LiquidGlass-JetpackCompose/blob/main/app/src/main/java/com/ardakazanci/liquidcard/MainActivity.kt)、[liquid_glass.agsl](https://github.com/ardakazanci/LiquidGlass-JetpackCompose/blob/main/app/src/main/res/raw/liquid_glass.agsl)。
  - 授权：[MIT](https://github.com/ardakazanci/LiquidGlass-JetpackCompose/blob/main/LICENSE)，Copyright (c) 2025 Arda K.
  - 观察：从独立背景图采样并进行镜片偏移；高光受触点位置影响，文字保持在材质前方。
- [Mortd3kay/liquid-glass-android](https://github.com/Mortd3kay/liquid-glass-android)
  - 研究文件：[README](https://github.com/Mortd3kay/liquid-glass-android/blob/main/README.md)。
  - 许可核查：早期按 README 归类为 Apache-2.0；1.4.0 复核 [LICENSE](https://github.com/Mortd3kay/liquid-glass-android/blob/main/LICENSE) 发现非标准混合文本，本项目不复制其代码。
  - 观察：区分 Android 13+ 的 AGSL 模式与旧版本的透明度、边缘高光降级模式；README 将该库标记为 experimental 0.1.0。

1.3.7 及以前的 `GlassSurface.kt`、`VideoBackdrop.kt`、`VideoRefraction.kt`、`PageBackdrop.kt` 与 `NavLiquidGlass.kt` 均为当时的独立实现，没有复制上述两个仓库的代码或资源。1.4.0 的导航已按前节移植 Kyant 代码。

1.3.1 在 Android 13 / API 33 及以上的视频区通过 Surface PixelCopy 约 24 fps 捕获真实视频帧，最长边限制为 640 像素，采用三缓冲；只把单独的视频帧传入 BitmapShader 与 RuntimeShader。新编写的 AGSL 将玻璃局部坐标映射到视频的 root 坐标，计算圆角 SDF 法线、边缘折射、少量色散和薄着色，之后再原样绘制前景文字与按钮，不对前景进行 warp 或模糊。控制栏为 60 dp 圆角浮层，倍速菜单不增加其高度。

1.3.3 的首页/历史底部导航使用独立页面 GraphicsLayer，仅对导航后方窄条的脏帧节流读回；导航为录制 Host 外的兄弟，不会被采回形成反馈。1.3.5 改为高速滚动冻结旧材质、慢滚动最多 8 fps / 最长边 480 像素、停稳 120 ms 后补采最长边 720 像素清晰帧，仅采样请求录制整页，其余帧正常绘制。复用独立 AGSL 做背景折射，叠加薄双层切边、滑动凸起胶囊和轻微触摸形变，图标与文字保持清晰。`rememberGraphicsLayer`、`record` 和 `toImageBitmap` 已核对 Compose 1.7.6 精确源码，均为稳定 API。

API 33 代码单独隔离，不使用实验 API。低版本、没有可用帧、软件 Canvas 或驱动不支持时，回退原有透明底和细边高光；片源面板等普通控件仍使用常规玻璃。非全屏播放页的底部导航使用透明高光回退，避免把视频 Surface 混入页面录制。

官方接口依据：[RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)、[BitmapShader](https://developer.android.com/reference/android/graphics/BitmapShader)、[AGSL 语法参考](https://developer.android.com/develop/ui/views/graphics/agsl/agsl-quick-reference)。使用 `setInputShader` 保留视频颜色管理，显式选择双线性采样，着色器输出预乘 alpha。

上述早期版本未将第三方源码并入分发包；1.4.0 已按前节随 APK 保留移植代码的版权、完整许可证与修改说明。

## 本次 UI 接口

- `rememberHomeState(repository)` 在 `MainActivity` 的页面 `when` 之外调用，持有片源、分类、关键词、结果片单、已加载页、请求页及 `LazyGridState`。页面切换不会清除结果或重发首屏请求；只有筛选或导入修订变化主动清空并回顶部。
- `HomeScreen(repository, state, onOpenMovie)` 仅呈现上述常驻状态。
- `GlassSurface(modifier, shape, tint, lightResponse, content)` 可用于首页和播放器；默认样式来自 `Theme.Glass`，前景内容保持清晰。
- `rememberVideoBackdrop(enabled)` 管理实时视频帧；`LocalVideoBackdrop` 只在视频区域提供该状态。`GlassSurface` 在 draw 阶段观察帧，不随取帧频率重组前景控件。`VideoRefraction.kt` 的 `GLASS_REFRACTION_SHADER` 为独立编写的着色器源码。
- `rememberPageBackdrop(enabled, revealing, page)` 与 `PageBackdropHost(backdrop, modifier, content)` 只录制页面内容；共同父级通过 `LocalPageBackdrop` 提供状态，仅外部兄弟导航 `NavLiquidGlass` 消费。1.4.0 直接复用 GPU 图层，`revealing` 参数仅保留调用兼容；完全隐藏停止录制，切页撤下旧页材质。
- `SeekBar(..., modifier, bufferedPosition)` 新增可选缓冲位置，旧调用保持兼容。
- `SourceImportPanel` 调用 `TvBoxMovieRepository.importSources(text, origin)`；订阅/解析和本地文档读取均在 `Dispatchers.IO`。支持粘贴文本或订阅 URL，文件通过系统 `OpenDocument` 选择，单文件读取上限 4 MiB。收起面板不取消正在提交的导入，busy 状态禁止重复提交，异常/取消路径均恢复 busy。

1.3.3 同步加入系统边缘整次让行（顶部至少 40 dp）、通知栏失焦取消手势、播放/缓冲常亮与离页恢复；错误和重连保留确认进度，网络恢复最多重试 2 次，等待超时为 20 秒。

1.3.5 的面板透明关闭层使用记忆的交互源并取消 `indication`；独立编写的 `PosterLoader.kt` 按目标尺寸解码，合并/取消请求，限制 3 并发，使用 16 MiB 内存与 48 MiB 磁盘缩略图缓存，滚动时延后新下载与原图解码。分发使用非 debuggable 的 Release 构建，不启用 R8，并沿用已有安装证书以支持覆盖安装保留数据；没有新增第三方依赖。
