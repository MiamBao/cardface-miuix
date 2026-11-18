<p align="center">
  <img src="docs/icon.png" alt="卡面工坊" width="112"/>
</p>

<h1 align="center">卡面工坊</h1>

<p align="center">
  给小米智能卡换一张喜欢的卡面<br/>
  <sub>LSPosed 模块 · Miuix（HyperOS）界面 · Lanczos3 图像管线</sub>
</p>

<p align="center">
  <a href="https://github.com/Xposed-Modules-Repo/io.github.miambao.cardface"><img src="https://img.shields.io/badge/LSPosed-Module%20Repo-blue.svg" alt="LSPosed Repo"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Android-13%2B-green.svg" alt="Android"/>
  <img src="https://img.shields.io/badge/LSPosed-API%20101%2B-orange.svg" alt="LSPosed"/>
</p>

---

<p align="center">
  <img src="docs/promo/00-cover.jpg" alt="卡面工坊 2.0" width="820"/>
</p>

## 这是什么

「卡面工坊」是一个给小米智能卡换卡面的 LSPosed 模块。不用改系统，把你喜欢的画、角色、照片，变成卡包里每天都会看到的那一张卡。

这个项目最早是「DIY NFC 卡面图片」的界面与体验重构：原版功能没问题，但 UI 比较粗糙，风格也旧了。这次按 Miuix 的 HyperOS 风格把整套界面重做了一遍，图片缩放、裁剪这些细节也重新写了一次。

如果你没有 root / LSPosed，这个 App 无法工作；如果你只是想让卡面好看一点，它大概是最省事的方式之一。

## 2.1 更新

- 全新澎湃 4 风格底栏，三个主界面支持左右滑动切换，底栏选中态实时跟随。
- 页签进入子页面增加滑入滑出转场。
- 关于页改版，新增作者栏；精简设置，移除不常用入口。
- 修复云端卡面下载后图库重复文件；修复备份恢复灰图、未启用与损坏图片导出失败。
- 本版最低 Android 13（API 33），Android 13 以下请继续使用 2.0.1。

## 2.0 更新

- **在线卡面库**：银行卡面、交通卡面、IP 联动、二次元、特摄；支持分类、搜索、收藏、最近使用、A-Z 索引、IP 跳转。
- **海报分享**：把卡面生成一张明信片海报，横版 2880×2160 / 竖版 2160×2880，三种版式，四套配色，可保存相册或直接分享。
- **本地图库**：自己的图导入、裁剪、保存，全程在本机处理，不上传。
- **更干净的设置**：按功能重新分组，删掉冗余开关；隐藏桌面图标、黑名单、主题色、重启卡包、诊断信息都在对应位置。
- **稳定性修复**：改一张卡面不再影响其他卡面；交通卡图标不再串图；屏蔽银行卡广告示意图；修复 DexKit 失效导致卡面恢复默认。

## 界面

| 卡面主页 | 本地图库 |
|---|---|
| ![卡面主页](docs/promo/01-home.jpg) | ![本地图库](docs/promo/02-gallery.jpg) |

| 设置 | 在线卡面库 |
|---|---|
| ![设置](docs/promo/03-settings.jpg) | ![在线卡面库](docs/promo/04-cloud.jpg) |

| 海报分享 | 2.0 更新 |
|---|---|
| ![海报分享](docs/promo/05-poster.jpg) | ![2.0 更新](docs/promo/06-updates.jpg) |

## 功能

- **替换卡面**：把图片合成为 960×606 PNG，复制到卡包私有目录后以 `file://` 交给卡包加载，兼容卡包 Glide 加载链路。
- **高质量缩放**：缩放感知 Lanczos3 + sRGB 线性光重采样，细线和文字不再有明显锯齿。
- **图库 / 裁剪 / 卡面标志**：3 列图库、160:101 固定比例裁剪（双指缩放、拖动）、京津冀 / 上海 / 泉城通 / 银川标志、圆角裁切。
- **在线卡面库**：Hibank 图片源，分类浏览、搜索、收藏、最近使用、下载后直接裁剪应用。
- **海报分享**：横竖版、三种版式、四套配色，生成 4:3 高清海报。
- **Miuix（HyperOS）界面**：大圆角卡片、分组列表、澎湃蓝 / 动态取色 / 多套主题色、深色模式、过渡动画、底部悬浮导航。
- **维护功能**：一键重启卡包、数据备份 / 恢复、清除 hook 缓存、隐藏桌面图标、卡面重命名、黑名单。

## 环境要求

- Android 13+（minSdk 33，targetSdk 36，compileSdk 37）
- 已 root，并安装 **LSPosed**（libxposed API 101+）
- 卡包：小米智能卡（`com.miui.tsmclient`）
- 参考验证环境：Xiaomi 15 Pro / HyperOS 4 / LSPosed 2.2.1 + 小米智能卡

## 安装与使用

1. 下载 Release 里的 `CardFace-v2.1.0.apk` 并安装。
2. 在 LSPosed 里启用「卡面工坊」，作用域勾选：小米智能卡。
3. 打开卡包，把卡面浏览一遍，让 hook 记录到卡面地址。
4. 回到「卡面工坊」→ 点一张卡面 → 从相册 / 在线卡面库选图 → 裁剪 / 加标志 → 保存并应用。
5. 在设置里点「一键重启卡包」，卡面即生效。

> **隐藏 / 寄生模式的 LSPosed**：部分设备的管理器没有桌面图标，也无法被第三方 App 直接打开。此时从通知栏的「Xposed 模块已更新 / LSPosed」通知进入管理器，长按模块 →「模块设置」即可打开本应用。

## 下载

> 2.1.0 需要 Android 13 及以上；Android 13 以下请使用 2.0.1。

- GitHub Releases：<https://github.com/MiamBao/cardface-miuix/releases/latest>
- LSPosed 模块仓库：<https://github.com/Xposed-Modules-Repo/io.github.miambao.cardface>

## 构建

需要：JDK 17、Android SDK Platform 37、Build Tools 37.0.0。

```bash
# 1. 准备签名（可选：不配也能 build，release 产物为未签名 APK）
cp keystore.properties.example keystore.properties
# 把自己的 keystore 放到 keystore/ 下，并改 keystore.properties

# 2. 构建
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

仓库不包含签名文件与 `keystore.properties`（已在 `.gitignore` 中）。

## 项目结构

```
app/       Android 应用（Compose + Miuix），含 Xposed 模块入口
imaging/   纯 JVM 图像管线（Lanczos3 降采样、缩放、合成），可单测
docs/      规格、截图与宣传图
```

## 免责声明与兼容性

- **小米智能卡**：只在上述参考环境验证通过，其他机型 / 系统 / 卡包版本可能存在差异。
- 替换图片需要把图复制进卡包私有目录（`file://`），**复制步骤需要 root**；没有 root 时会退回 `content://`，但部分卡包版本不认。
- 本应用只修改卡包展示图，不涉及支付、余额与刷卡逻辑；因使用本应用产生的任何问题由使用者自行承担。
- 请勿用于伪造证件、支付欺诈等任何违法用途。

## 许可证

[GPL-3.0](LICENSE)。本项目包含 / 链接了以下开源项目：

- 原版项目：[DIY NFC卡面图片](https://github.com/Xposed-Modules-Repo/com.zhizi42.diymiuicard) v2.4（zhizi42，GPL-3.0）
- UI 组件：[Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)（Yukonga，Apache-2.0）
- 模块框架：[LSPosed](https://github.com/LSPosed/LSPosed)（GPL-3.0）、[libxposed API](https://github.com/LibXposed/libxposed-api)（Apache-2.0）
- Dex 定位：[DexKit](https://github.com/LuckyPray/DexKit)（LuckyPray，Apache-2.0）
- AndroidX / Jetpack Compose / Coil 3（Apache-2.0）

## 致谢

- **作者**：MiamBao
- **重构与实现**：DeepSeek（AI 编程助手）—— Miuix 界面重构、卡面 hook 链路、图像管线与性能优化
- 原版作者 zhizi42，以及上述开源项目的作者们
