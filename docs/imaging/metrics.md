# 图像指标（自动生成，勿手改）

由 `:imaging:test` 的 `CompareTool` 生成；`report.md` 引用这里的数据。

- 样张：`imaging/src/test/resources/sample.png`，4000x2525（真实相机照片原分辨率裁剪，非整数倍缩小 4.17x / 4.17x）
- 目标尺寸：960x606
- 理想低通参考：`Resampler.boxAverage(sample, 960, 606, linearLight = true)`（面积平均：无振铃、无走样的近似上限）
- 耗时：JVM（ARM64，JDK 17），预热后 5 次取最优；`lanczos3` 含 box 预缩小全流程
- 平均梯度能量：内部像素 Sobel 幅度均值
- 平均亮度偏差：`meanLuma(结果) - meanLuma(参考)`，越接近 0 说明亮度越不失真

| 方法 | 纯算法耗时 (ms) | PSNR vs 理想低通 (dB) | SSIM | 平均梯度能量 | 平均亮度偏差 | 说明 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| nearest | 1 | 25.55 | 0.9323 | 53.00 | -1.92 | 点采样（最差对照） |
| bilinear | 5 | 27.69 | 0.9587 | 51.05 | -1.90 | 原版等效：`Bitmap.createScaledBitmap(..., true)` 的 2x2 采样 |
| lanczos3 | 90 | 32.92 | 0.9891 | 48.40 | -0.81 | **新版默认**：缩放感知 Lanczos3 + 线性光 |
| lanczos3-gamma | 91 | 33.99 | 0.9918 | 47.13 | -1.88 | 对照：Lanczos3 但在 sRGB 空间重采样（关闭线性光） |


## 对比图

- `compare-full.png`：左→右 = nearest | bilinear（原版等效）| lanczos3（新版），每张 960x606，共 2880x606
- `compare-crop.png`：同一 100x100 区域（x=25, y=125）3x 放大（最近邻放大以便看清像素级差异），左→右同上，共 900x300

## 一句话结论

- 与理想低通参考的 PSNR：lanczos3 = 32.92 dB，bilinear = 27.69 dB，nearest = 25.55 dB；
  新版比原版等效高 **5.23 dB**，比点采样高 7.37 dB。
- SSIM：lanczos3 = 0.9891，bilinear = 0.9587。
- 耗时：lanczos3 ≈ 90 ms（JVM 纯算法，单次），bilinear ≈ 5 ms。
  Android 端先用 `inSampleSize` 解码到约 3MP，lanczos3 纯算法约 40~80ms。