# 卡面工坊重制版 · 图像核心质量报告

日期：2026-11-07 · 模块：`:imaging`（纯 JVM）+ `BitmapPipeline`（Android 包装层）

---

## 0. 结论速览

| 问题 | 结果 |
| --- | --- |
| 原版降采样锯齿（用户最大抱怨） | **消除**：细线/棋盘图案的走样标准差从 **73.75 / 42.84 → 0.00**（新版接近理想低通） |
| 线稿类卡面（二次元/文字） | 与理想低通参考的 PSNR：原版等效 **14.87 dB → 新版 32.34 dB，+17.47 dB** |
| 真实 4.17x 缩小照片 | PSNR **27.69 → 32.92 dB（+5.23 dB）**，SSIM **0.9587 → 0.9891** |
| 线性光 | 黑白高频区域平均亮度 **128 → 188**（不再发灰/发暗），与参考的亮度偏差 -1.90 → **-0.81** |
| 耗时（JVM 纯算法，10~12MP → 960×606） | Lanczos3 约 **90 ms**（原版双线性 5~7 ms）；Android 端解码到 3MP 后约 40~80 ms，后台线程执行 |
| 卡面输出 | 严格 **960×606**、圆角 **40px（4×4 覆盖度抗锯齿）**、标志 PNG 整幅 SRC_OVER，均有自动化测试断言 |

证据图（全部由 `:imaging:test` 自动生成）：

- [compare-full.png](compare-full.png) — 真实照片三张并排：nearest / bilinear（原版等效）/ lanczos3（新版）
- [compare-crop.png](compare-crop.png) — 同一 100×100 区域 3x 放大（锯齿最直观）
- [compare-lineart.png](compare-lineart.png) — **线稿/细线对比**：细线网格 + 斜排线，同一区域 4x 放大，两行 × 三列
- [compare-lineart-full.png](compare-lineart-full.png) — 线稿测试图完整三张并排
- [card-preview.png](card-preview.png) — 最终卡面成品：960×606 + 40px 圆角 + 京津冀标志
- 自动指标：[metrics.md](metrics.md)（照片）、[metrics-lineart.md](metrics-lineart.md)（线稿）

---

## 1. 原版问题与新版方案

**原版**：`SelectImageActivity` / uCrop 用 `Bitmap.createScaledBitmap(src, 960, 606, true)`（双线性、2×2 采样）把大图直接压缩。12MP 图缩到 960 宽约 4.2 倍，每个输出像素只参考了源图 2×2=4 个像素（而该区域实际有约 17 个源像素，约 77% 的信息被直接丢弃）—— 高频细节折叠成锯齿、摩尔纹、断线，就是用户看到的“全是锯齿”。

**新版管线**（`BitmapPipeline.buildCard`）：

```
Bitmap ──EXIF 转正──► inSampleSize 解码(≥2× 目标) ──裁剪──►
   :imaging Resampler.lanczos3（缩放感知 + 线性光）──►
   圆角 40px（4×4 覆盖度抗锯齿）──► 标志 PNG SRC_OVER ──► 严格 960×606 Bitmap
```

关键点：

1. **缩放感知 Lanczos3 核**：缩小 N 倍时核支撑半径同时放大 N 倍（`support = 3·max(1, N)`），
   缩小 4.17 倍时每个输出像素汇总周边约 **±12 个源像素**（而不是 2×2），等效于先做带限低通再采样。
   `Resampler.kt: buildLanczosTaps`。
2. **先整数倍 box 预缩小**到目标尺寸 2 倍以上，再做 Lanczos（`Resampler.lanczos3`）：
   质量接近直接大核 Lanczos，同时把中间缓冲和核半径都压下来，12MP 内存峰值约 90MB（< 200MB 规格）。
3. **线性光重采样**（默认开启，可关）：sRGB 解码 → 线性光加权 → 再编码。
   sRGB 是 gamma 编码，直接平均会让高频区域整体变暗（黑白平均 gamma=127，线性光=188）。
   LUT 实现（256 项解码 + 4096 项编码插值），避免逐像素 `pow`。
4. **透明边缘正确**：alpha 预乘后再重采样，输出还原直通 alpha，不会出现黑边。
5. **圆角**：4×4 超采样计算每个像素被圆覆盖的比例，alpha 按覆盖度缩放，RGB 不变（无黑边/杂色）。
6. **不做任何 `Bitmap.createScaledBitmap`**：所有缩放都在 `:imaging` 里完成。
   已 grep 确认 `app/` 内没有该调用（只在注释里说明“禁止使用”）。

---

## 2. 交付内容

### `:imaging`（纯 JVM，可单测）

| 文件 | 内容 |
| --- | --- |
| `PixelImage.kt` | ARGB_8888 像素图容器；alpha/opaque 判定；通道工具 |
| `Srgb.kt` | sRGB ⇄ 线性光 LUT |
| `Resampler.kt` | `lanczos3`（缩放感知 + 线性光 + box 预缩小）、`bilinear`（原版等效对照）、`nearest`（对照）、`boxAverage`（面积平均/参考） |
| `ImageMath.kt` | `cropAspect` / `cropRect` / `roundCorners`（4×4 超采样）/ `compositeOver`（SRC_OVER）/ `loadPng` / `savePng` |

### Android 包装层

`app/src/main/kotlin/com/dsh/miuix/cardface/core/BitmapPipeline.kt`：

- EXIF 方向转正（`androidx.exifinterface`，旋转用 `Matrix`，不缩放）；
- `inSampleSize` 解码：2 的幂，解码后长边 ≥ 2× 目标（1920）且 ≤ `maxDim`（默认 4096）；
  12MP 图 → `inSampleSize=2`（约 3MP，12MB），108MP 图 → 8；
- 裁剪（像素坐标 `RectF`，null = 居中按 960:606 裁剪）；
- 输出严格 960×606（`Options.targetW/H` 可配，默认 960/606）；
- 圆角 40px；标志 asset（`card_logo/*.png`，960×606 透明 PNG）整幅 SRC_OVER 叠加；
- `thumbnail()` 快速预览（boxAverage，不走 Lanczos）。

**最终公开 API 签名**：

```kotlin
object BitmapPipeline {
    data class Options(
        val targetW: Int = 960,
        val targetH: Int = 606,
        val roundRadiusPx: Float = 40f,
        val linearLight: Boolean = true,
        val logoAsset: String? = null,   // "jingjinji" 或 "card_logo/jingjinji.png" 均可
    )

    fun decode(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap?
    fun decodeFile(path: String, maxDim: Int = 4096): Bitmap?
    fun inSampleSizeFor(width: Int, height: Int, maxDim: Int = 4096): Int

    fun buildCard(context: Context, src: Bitmap, crop: RectF? = null, opts: Options = Options()): Bitmap

    fun thumbnail(src: Bitmap, maxDim: Int = 480, linearLight: Boolean = true): Bitmap
    fun thumbnail(context: Context, uri: Uri, maxDim: Int = 480, linearLight: Boolean = true): Bitmap?

    fun savePng(bitmap: Bitmap, file: File)
}

// 文件级扩展（同文件，package com.dsh.miuix.cardface.core）
fun Bitmap.toPixelImage(): PixelImage
fun PixelImage.toBitmap(): Bitmap
```

`crop: RectF` 语义：**源图像素坐标**的裁剪矩形（left/top/right/bottom），内部会夹紧到图内；
UI 裁剪页锁定 160:101，直接把裁剪框映射到源图像素坐标传入即可。

### 测试（`:imaging:test`，21 个全部通过）

`ResamplerTest`（10）、`ImageMathTest`（7）、`CardBuildTest`（2）、`CompareTool`（1）、`LineArtCompareTest`（1）。

---

## 3. 证据 A：真实照片 A/B

**样张**：`imaging/src/test/resources/sample.png`，**4000×2525（10.1MP）**，PNG 28MB。
来源：手机相册 `/sdcard/DCIM/自然风景/IMG_20220503_144149.jpg`（小米 MIX 4，4512×6016，2022-05-03），
取原分辨率裁剪区域 `x=200, y=2200, 4000×2525`（无损存 PNG，不缩放，保留全部原生细节）。
选它的原因：贴天空的细树枝 + 密集树叶 + 建筑窗格，高频细节最多，是降采样最苛刻的场景；
缩小倍率 4000/960 = 2525/606 = **4.17x**，与真实 10~12MP 照片出 960 卡面的倍率一致。

指标（`docs/imaging/metrics.md`，JVM ARM64 / JDK 17，预热后 5 次取最优）：

| 方法 | 纯算法耗时 | PSNR vs 理想低通 | SSIM | 平均梯度能量 | 平均亮度偏差 |
| --- | ---: | ---: | ---: | ---: | ---: |
| nearest（点采样） | 1 ms | 25.55 dB | 0.9323 | 53.00 | -1.92 |
| bilinear（原版等效） | 5 ms | 27.69 dB | 0.9587 | 51.05 | -1.90 |
| **lanczos3（新版）** | **90 ms** | **32.92 dB** | **0.9891** | 48.40 | **-0.81** |
| lanczos3-gamma（关闭线性光，对照） | 91 ms | 33.99 dB | 0.9918 | 47.13 | -1.88 |

- “理想低通参考” = `boxAverage(sample, 960, 606, linearLight=true)`（面积平均，无振铃无走样的近似上限）。
- **新版比原版等效高 +5.23 dB**，比点采样高 +7.37 dB。
- 平均亮度偏差：线性光 **-0.81**，原版等效 **-1.90**（原版高频区域明显偏暗，这正是 sRGB 空间平均的代价）。
- 看图：`compare-full.png`（左→右 nearest | bilinear | lanczos3），`compare-crop.png`（同一 100×100 区域 3x 放大）。

> 关于 lanczos3-gamma：关闭线性光后编码字节域 PSNR 略高（+1.07 dB），是因为 sRGB 空间的振铃在
> 字节域幅度更小；但它会让高频区域整体变暗（亮度偏差 -1.88，接近原版的 -1.90）。
> 真实照片亮度正确性更重要，且规格要求线性光默认开启，故 `Options.linearLight` 默认 `true`，需要时可关。

---

## 4. 证据 B：线稿/文字类卡面（用户卡面多为二次元线稿）

`LineArtCompareTest` 合成 **3000×1894 黑白线稿测试图**（细线网 / 网格纸 / 多角度细斜线 / 密集斜排线 /
1px 棋盘 / 圆点阵 / 大小字号中文），3.125x 缩到 960×606：

| 方法 | PSNR vs 理想低通 | 1px 棋盘块亮度 std（应接近 0） |
| --- | ---: | ---: |
| nearest | 12.34 dB | 127.50 |
| bilinear（原版等效） | 14.87 dB | 42.29 |
| **lanczos3（新版）** | **32.34 dB** | **0.00** |

- **新版比原版等效 +17.47 dB**（线稿是点采样/2×2 采样损失最大的内容类型，也是用户抱怨最集中的）。
- 看 [compare-lineart.png](compare-lineart.png)：两行 × 三列，同一区域 4x 最近邻放大（左→右 = nearest | bilinear | lanczos3）
  - 第 1 行（输出坐标 x=30,y=388，200×120）：1px 细线网格。原版线条**忽粗忽细、断成虚线段、带黑斑**；
    新版线条**均匀、连续、细而清晰**（没有让线变淡，只是不再随机变粗）。
  - 第 2 行（x=385,y=15，200×120）：密集斜排线。原版斜线**断成一片片黑斑/锯齿**；新版保持**均匀连续的细斜线**。

---

## 5. 证据 C：算法级图案测试（量化抗锯齿）

`ResamplerTest` 中直接对“本该均匀”的图案测走样能量（越低越好）：

| 测试图案（缩小倍率） | lanczos3 | bilinear（原版等效） | nearest |
| --- | ---: | ---: | ---: |
| 1px 竖细线 1600×600 → 477×179，亮度 std | **0.00** | 73.75 | 127.50 |
| 1px 棋盘同尺寸，亮度 std | **0.00** | 42.84 | 127.50 |
| 1px 棋盘 8x 缩小（1920×1212→240×151），std | **0.00** | — | 127.50 |
| 细线图案平均梯度能量 | **0.01** | 116.52 | — |
| 文字边缘图案平均梯度能量 | **11.97** | 121.75 | — |
| 1px 棋盘 8×8 块均值 std（摩尔纹） | **0.00** | 1.30 | — |

其他断言（全部通过）：

- 缩小后尺寸精确、alpha 通道不丢失（半透明边缘无黑边，纯色 alpha 128→128）；
- 放大不出现最近邻块状：台阶边缘过渡列数 lanczos=7、nearest=0；亮度种类 >6 vs ≤2；
- 线性光开关不改变尺寸，只影响亮度过渡：黑白细线平均亮度 **线性光 188.0 vs gamma 128.0**，纯色图两种模式差 ≤1；
- boxAverage 与手工面积平均逐像素一致；8 倍缩小经 box 预缩小 + Lanczos 后接近均匀灰。

---

## 6. 证据 D：卡面成品与 BitmapPipeline 行为

`CardBuildTest` 用真实样张完整模拟 `buildCard`（裁剪 → Lanczos3 线性光 → 40px 圆角 → 京津冀标志 SRC_OVER）：

- 输出**严格 960×606**（宽、高、像素数三处断言）；
- 四角 alpha=0，中心/四边中点 alpha=255；圆角对角线存在中间覆盖度像素（48/255 → 连续过渡，不是硬切），
  半透明像素 RGB 保持原色（无黑边）；
- 标志叠加位置精确：资源中 alpha=255 的像素与成品**逐像素相等**（整幅对齐在 0,0）；
  资源 alpha=0 的像素处卡面**逐像素不变**（抽样 2000+ 点验证）；
- 成品见 [card-preview.png](card-preview.png)（960×606，圆角 40px，左上“市政交通一卡通”、右上“交通联合”标志）。

> 说明：以上为 JVM 等价管线验证（`:imaging` 测试）。Android 包装层 `BitmapPipeline` 已通过
> `:app:compileDebugKotlin` 编译校验；本环境没有可用的 Android 设备/模拟器，无法在此跑真机
> `buildCard` 的运行时验证，UI 接线后需在真机上看一次圆角/标志/960×606 输出。

---

## 7. 性能与内存（原版 vs 新版取舍）

| 场景 | bilinear（原版等效） | lanczos3（新版） |
| --- | ---: | ---: |
| 12MP 4000×3000 → 960×606（JVM 纯算法，含 box 预缩小） | 7 ms | **90 ms** |
| 10.1MP 4000×2525 真实样张 → 960×606（同上） | 5 ms | **90 ms** |
| 内存峰值（12MP 输入） | < 20MB | 约 **90MB**（规格要求 < 200MB） |

- 新算法比双线性慢约 **10~18 倍**，但绝对耗时仍在百毫秒级，且是在**后台线程**做的；
  Android 端先用 `inSampleSize` 解码到约 3MP，纯算法可按像素数折算到 **约 40~80 ms**，
  加上 PNG 编码 30~60ms，一次保存总耗时约 100~170ms（用户可接受，保存页有 ProgressIndicator）。
- 内存：12MP 输入峰值约 90MB（源 48MB + 预缩小 12MB + 中间浮点缓冲 ~17MB + 其他），
  测试 JVM 以 512MB 堆稳定通过；模块内部按 48MB 浮点缓冲上限分块处理，不会一次性放大图。
- 若未来要更省时间，可考虑：`thumbnail()` 已用 boxAverage（3~6ms 级）；保存时 `linearLight=false`
  约省 10~20%（代价见上文亮度偏差）。

### 是否建议 960×606 还是更高分辨率？

**建议保持 960×606 作为最终输出**，理由：

1. 卡包/桌面展示的就是 160:101 比例的小卡面，960×606 已经按 6x 冗余覆盖；
   输出更高分辨率也只会被卡包再次缩小，而卡包自己的缩放不可控，反而会把我们辛苦保住的细节再踩一遍锯齿。
2. 质量瓶颈不在“最终分辨率”，而在“大图 → 最终分辨率”这一步的滤波器。新版已经用缩放感知
   Lanczos + 线性光解决；提高输出分辨率不会带来肉眼可见提升，只会增加 PNG 体积和合成耗时。
3. `Options.targetW/H` 已参数化，未来若卡包原生支持更高分辨率（例如 1440×909），
   直接改默认值即可，管线无需改动；解码端 `inSampleSize` 预留了 2× 目标边长，仍有足够余量。

---

## 8. 复现方式

```bash
# 图像核心测试 + 重新生成全部证据图（docs/imaging/）
/root/bin/cardface-build.sh :imaging:test

# Android 包装层编译检查
/root/bin/cardface-build.sh :app:compileDebugKotlin
```

生成物：

```
docs/imaging/
├── report.md               # 本报告
├── metrics.md              # 照片 A/B 自动指标
├── metrics-lineart.md      # 线稿 A/B 自动指标
├── compare-full.png        # 照片：nearest | bilinear | lanczos3（2880x606）
├── compare-crop.png        # 照片：同一区域 3x 放大（900x300）
├── compare-lineart.png     # 线稿：两行 × 三列，同一区域 4x 放大（2408x964）
├── compare-lineart-full.png# 线稿完整三张（2880x606）
└── card-preview.png        # 最终卡面成品（960x606，圆角 40px + 标志）
```
