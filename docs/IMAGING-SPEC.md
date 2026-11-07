# 高质量降采样模块规格（用户核心痛点）

原版问题：`SelectImageActivity` / uCrop 用 `Bitmap.createScaledBitmap(..., true)`（双线性）
把大图直接缩到 960×606，缩小倍数大时严重走样、边缘锯齿；预览也直接缩放。
**重制版必须明显更干净**，并给出可对比的证据。

## 1. 纯 JVM 模块 `:imaging`（`imaging/src/main/kotlin/.../imaging/`）

不依赖 Android，全部用 `IntArray` ARGB 像素，方便单元测试：

```kotlin
data class PixelImage(val width: Int, val height: Int, val pixels: IntArray) // ARGB_8888
object Resampler {
    fun lanczos3(src: PixelImage, dstW: Int, dstH: Int, linearLight: Boolean = true): PixelImage
    fun bilinear(src: PixelImage, dstW: Int, dstH: Int): PixelImage    // 对照组（模拟 createScaledBitmap）
    fun nearest(src: PixelImage, dstW: Int, dstH: Int): PixelImage     // 对照组
    fun boxAverage(src: PixelImage, dstW: Int, dstH: Int): PixelImage  // 预缩小
}
object ImageMath {
    fun cropAspect(src: PixelImage, ax: Int, ay: Int, centerX: Float, centerY: Float, zoom: Float): PixelImage
    fun roundCorners(src: PixelImage, radiusPx: Float): PixelImage     // 抗锯齿覆盖度
    fun compositeOver(base: PixelImage, overlay: PixelImage): PixelImage
    fun loadPng(path: String): PixelImage; fun savePng(img: PixelImage, path: String)
}
```

要求：
- **Lanczos3 必须做缩放感知（scale-aware）核**：缩小 4 倍时核半径 ×4 并按覆盖面积归一化，
  否则等于没抗锯齿。
- 线性光（sRGB 解码 → 线性 → 重采样 → 再编码）默认开启；要求可关。
- 缩小流程：先按整数倍 box 预缩小到 ≥2× 目标尺寸，再 Lanczos3 到精确尺寸（质量与速度平衡）。
- 大图保护：输入 > 目标 8 倍时先 box 预缩小；内存峰值 < 200MB（12MP 输入）。
- 圆角：半径 = 输出宽 960 时 40px，边缘用 4×4 超采样覆盖度抗锯齿；无黑边/杂色。
- 卡面标志叠加：4 张 960×606 的 PNG（`app/src/main/assets/card_logo/`）按 SRC_OVER 合成。

## 2. Android 包装层 `app/src/main/kotlin/.../core/BitmapPipeline.kt`

```kotlin
object BitmapPipeline {
    data class Options(val targetW: Int = 960, val targetH: Int = 606, val roundRadiusPx: Float = 40f,
                       val linearLight: Boolean = true, val logoAsset: String? = null)
    fun decode(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap?   // EXIF 旋转 + inSampleSize
    fun decodeFile(path: String, maxDim: Int = 4096): Bitmap?
    fun buildCard(context: Context, src: Bitmap, crop: RectF?, opts: Options): Bitmap  // 960x606 + 圆角 + 标志
    fun thumbnail(...)  // 小尺寸快速预览，用于剪辑/裁剪界面
}
```
- EXIF：`androidx.exifinterface`，JPEG 方向要转正。
- 解码用 `inSampleSize`（2 的幂），保证解码后 ≥2× 目标边长，避免先解全图。
- `buildCard` 必须走 `:imaging` 的 Resampler，不允许用 `Bitmap.createScaledBitmap`。
- 方法注释写明耗时（在 JVM 上测 12MP→960×606 的纯算法耗时，给出数字）。

## 3. 测试与证据（必须交付）

位置：`imaging/src/test/kotlin/.../`，可运行：
```
/root/bin/cardface-build.sh :imaging:test
```

- `ResamplerTest.kt`：
  - 缩小后尺寸精确、alpha 不丢失；
  - 放大不出现最近邻块状；
  - 与双线性对比：对「黑白细线 / 棋盘 / 文字边缘」测试图，Lanczos3 的边缘梯度能量/摩尔纹指标
    明显更低（把指标写法与数值写进报告）；
  - 线性光开关不改变尺寸，只影响亮度过渡。
- `CompareTool.kt`（JUnit 测试或 main）：读一张真实样张（自选：`/sdcard/DCIM` 里挑一张
  细节多的照片/二次元图，复制到 `imaging/src/test/resources/sample.png`），
  分别用 nearest / bilinear / lanczos3 缩到 960×606，输出：
  - `docs/imaging/compare-full.png`（三张并排 960×606）
  - `docs/imaging/compare-crop.png`（同一区域 3× 放大的 300×300 局部对比，最能看出锯齿）
  - `docs/imaging/report.md`：指标表 + 结论（中文）
- 报告里必须明确：原版速度/质量取舍 vs 新版；是否建议 960×606 还是更高分辨率。

## 4. 验收

1. `:imaging:test` 全绿。
2. `docs/imaging/report.md` + 对比图存在且肉眼可见差距。
3. `BitmapPipeline.buildCard` 产出严格 960×606 PNG，圆角边缘干净，标志位置正确。
4. 不使用 `Bitmap.createScaledBitmap`；所有缩放走 `:imaging`。
