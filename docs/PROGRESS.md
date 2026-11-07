# 卡面工坊 · 交付进度（v1.0.0，2026-11-07）

## 结论

重制版已完成并在本机做了**完整真机验证**：UI 全量重做（Miuix，10 个页面，SukiSU 风格的大绿卡 +
灰底白卡分组 + 澎湃蓝）、降采样修好（Lanczos3）、过渡动画、动态取色/主题色/深色模式、
**卡面替换打通并在真机上看到了换卡效果**（见 `docs/screens/01-home.jpg`）。

- APK：`/sdcard/Download/DSH-Go/build/卡面工坊-v1.0.0.apk`（11.25 MB）
- md5：`0a4adaf22fcb1c2d768169c664144de6`
- 包名：`com.dsh.miuix.cardface`，v1.0.0（versionCode 1），minSdk 26 / targetSdk 36
- 已安装到本机（release 签名，覆盖升级正常）

## 本版本做了什么

### UI（Miuix 0.9.4，SukiSU 风格）
- 首页大绿卡按 SukiSU 状态卡复刻并压缩到同一观感：`#DFFAE4` 浅绿底（深色 `#1A3825`）/
  `#36D167` 圆环对勾、16dp 圆角、24sp Bold 系统字体标题 + 13sp 两行小字、
  右下角 84dp 圆环对号（右边缘略微裁切）、高度约 112dp。
- 灰底页面 + 白色圆角分组卡片（20dp）+ 行首图标，设置页观感对齐 SukiSU。
- 底栏保持早期版本（Miuix `FloatingNavigationBar` + 实心蓝色选中胶囊，图标+文字）。

- 首页：大标题 + hero 状态卡（已生效/上次生效/未生效/演示模式，颜色过渡）+ 2 列卡面网格
  （160:101 缩略图、已替换/未替换胶囊）+ 下拉刷新 + 空状态三步引导。
- 自定义卡面：预览、从相册选、粘贴网址、从图库选、卡面标志 chips（京津冀/上海/泉城通/银川）、
  圆角开关、文件名对话框、保存并应用（保存即映射，修复原版"保存后还要再点一次"的坑）。
- 裁剪：160:101 固定比例，双指缩放 1~8x + 拖动 + 边界约束 + 三分线/四角标记。
- 图库：3 列网格、全屏预览、长按删除、添加图片。
- 设置：模块/卡包/作用域状态、隐藏桌面图标、超级岛、显示全部卡面、黑名单、清 hook 缓存、
  清图片缓存、清空所有卡面、**外观**、高级（调试日志/自定义 hook 点/兼容模式）、关于/说明/FAQ/QQ群/捐赠。
- 首次启动三步引导；演示模式（没卡包也能玩）。

### 外观（用户新需求）
- **默认澎湃蓝**：Miuix 原生白/灰底 + #3482FF 主色（开关/按钮/选中态），与 SukiSU 观感一致。
- 动态取色（跟随壁纸，Monet，默认关）↔ 关闭后用主题色（8 个预设：澎湃蓝/青碧/翠绿/紫罗兰/樱粉/暖橙/绯红/流金）。
- 深色模式：跟随系统 / 浅色 / 深色。
- 界面过渡动画开关；切换即时全局生效（根 ThemeController 随 prefs 重建）。

### 动画（用户新需求）
- 路由 AnimatedContent：前进右滑入+淡入 / 后退反向（280ms）；页签淡入+0.98→1 缩放（180ms）。
- 首页/图库网格 `animateItem`；hero 颜色/文字过渡；编辑页预览 Crossfade；图库预览 AnimatedVisibility。
- 「界面过渡动画」关闭后全部退化为 None/snap。

### 图像（用户最大痛点）
- 原版：uCrop 用 `createScaledBitmap(..., false)`（最近邻）从原图直接缩到 960×606 → 锯齿。
- 新版：EXIF 转正 + inSampleSize 解码 → 缩放感知 Lanczos3 + 线性光 + box 预缩小 → 严格 960×606
  → 40px 圆角（4×4 覆盖度抗锯齿）→ 卡面标志 SRC_OVER。
- 指标（`docs/imaging/report.md`）：照片 PSNR 27.69→32.92 dB、SSIM 0.9587→0.9891；
  线稿 PSNR 14.87→32.34 dB、1px 棋盘走样 std 42.29→0.00；12MP→960×606 纯算法约 90ms。
- 对比图：`docs/imaging/compare-crop.png`、`compare-lineart.png`。

### Xposed 模块（复现原版功能）
- libxposed API 101，`META-INF/xposed/*` 打包在 APK 根；作用域 com.miui.tsmclient / com.finshell.wallet。
- DexKit 定位 + 缓存文件 + 自定义 hook 点 + 反射兜底；arg/返回值两种替换模式；
  小图过滤、黑名单、测试模式、last_hook_at 上报。
- 替换图优先复制到卡包私有目录并返回 `file://`（复制需要 root）；
  兼容模式可 root 复制回 `file://`。
- RemotePreferences provider（settings / card_urls）+ hook 侧 ContentResolver 客户端。
- 单测：27/27 通过（UrlMapper 19 + provider 协议 8）。

## 验证矩阵

| 项 | 状态 | 证据 |
|---|---|---|
| 编译（release/debug） | ✅ | `BUILD SUCCESSFUL`，APK 11.25MB |
| 图像算法单测 | ✅ | `:imaging:test` 21/21 |
| hook/协议单测 | ✅ | `:app:testDebugUnitTest` 27/27 |
| 真机安装/升级 | ✅ | InstallerX root 安装、覆盖升级正常 |
| 真机 UI（首页/编辑/裁剪/图库/设置/弹层） | ✅ | `docs/screens/` |
| 保存并应用全流程（含 Lanczos 输出） | ✅ | 实测保存 → 首页卡片立即更新 |
| 主题切换（动态/预设/深色/动画开关） | ✅ | 实测即时生效 |
| 模块元数据/Provider 注册 | ✅ | aapt2 badging + merged manifest 核对 |
| LSPosed 识别模块 | ✅ | 管理器（寄生模式，`com.android.shell`）里模块已启用、作用域已勾小米智能卡 |
| 真机 hook 替换卡面 | ✅ | 卡包交通卡与门卡卡面已替换成用户图片（`docs/screens/01-home.jpg`） |

## 待用户配合 / 下一步

1. 在 LSPosed 管理器里启用「卡面工坊」，勾选作用域（小米智能卡 / ColorOS 卡包）。
2. 强行停止小米智能卡，再打开它浏览一遍卡面；回到 App 下拉刷新，应看到卡面列表。
3. 若替换不生效：设置 → 清除 hook 点缓存 → 重启卡包；仍不行看 logcat `cardface` 日志。
4. 若卡包进程读不到 Provider（包可见性），再上 `io.github.libxposed:service` 方案（见 docs/HOOK-SPEC.md）。


## v1.0.0 最终修复记录（18:34）

真机上卡面替换一开始不生效，最后定位到 **3 个叠加问题**，全部修掉后验证成功：

1. **hook 点定位错了**：反编译卡包（`tsmclient.apk`）发现真正入口是
   `com.miui.tsmclient.util.n0.i(String) -> Object`（CustomGlideUrl 的静态工厂），
   之前 DexKit 错定位成无参的 `n0.c()`（URL getter），所以替换永远不触发。
   → `MethodLocator.locateMiui0` 改为定位「单 String 参数、返回 Object 的静态方法」；
   并把定位缓存文件名升到 `.v2`，旧缓存自动失效。
2. **替换地址用 content:// 卡包不接受**：卡包里的 Glide 加载 file/content 有限制。
   → 改为 App 侧用 root 把图复制进卡包私有目录 `files/images/`，hook 返回 `file://`
   （走 `/proc/1/root/...` 绕过 SukiSU su 的 mount namespace 限制）；
   `CardSync` 启动时与点「重启卡包」时都会同步。
3. **模块更新后卡包进程还在用旧 hook**：
   → 新增 `HookAutoRefresh`：模块定位逻辑版本变化时，打开 App 自动清卡包 hook 缓存并 force-stop 卡包，
   下次打开卡包就是新逻辑。

验证方式：保存替换图 → 打开「小米智能卡」→ 交通卡（¥74.00）与门卡卡面都变成了选定的图片。
另外把「我们复制进卡包的替换图」产生的 file:// URL 从卡面列表里过滤掉，避免重复卡片。


## 18:50 追加修复（用户反馈的 4 个问题）

1. **卡面列表里一堆不是自己的卡面**
   - 根因：通用 Glide 加载 hook（slot=1）把卡包里所有图片（图标/横幅/二维码）都当成卡面记录；
     另外我们自己复制进卡包的替换图（`card-YYYYMMDD-HHMMSS.png` / `content://`）也会被当成卡面。
   - 修复：只有「卡面专用」hook（CustomGlideUrl 工厂、超级岛卡面）才记录新 URL；
     列表和启动清理都会过滤图标/横幅/广告/二维码/`/logo/`/自家替换图。
   - 效果：首页从「已识别 4 张」回到真实的「2 张」。
2. **裁剪页不能放大缩小/拖动，图片被硬截断**
   - 整页重写：Canvas 自绘图片（不再用 Image + ContentScale.Crop），
     双指缩放 1~8x、单指拖动、双击重置，边界约束不露黑边；
     另外加了 − / + / 重置 按钮兜底，实时显示 `缩放 1.6x`。
3. **交通卡标志（京津冀/上海/泉城通/银川）不见了**
   - 以前只有「相册选图并裁剪」后才会出现标志选项；「从图库选」直接写映射，跳过了编辑草稿。
   - 现在「从图库选」也会把图载入草稿，可加标志/圆角后保存；打开已有替换图也会载入草稿。
4. **关于页没按要求改**
   - 重做为 HyperOS 风格完整关于页：应用信息卡（图标/版本/简介/技术标签）、功能亮点、
     帮助与反馈（使用说明 / 常见问题 / QQ 群）、开源与致谢、隐私与免责声明、支持作者、页脚。

另外：保存替换图成功的弹窗里可以直接点「重启卡包」，不用再回设置操作。


## 19:00 第三轮修复

1. **提示条挡住保存按钮**：编辑页里「已选择图库图片」不再弹 snackbar（改由状态行提示）；
   其他提示条在编辑页统一上移 96dp，不再遮住底部蓝色按钮。
2. **设置里删掉「无钱计划」和「加入 QQ 群」**；「关于与捐赠」改名为「关于应用」。
3. **关于页补全开源信息**：本应用 GPL-3.0、原版 DIY NFC卡面图片 v2.4（zhizi42，GPL-3.0）、
   Miuix（Yukonga，Apache-2.0）、LSPosed / libxposed API、DexKit、AndroidX/Compose/Coil，
   都写了作者、许可证和 GitHub 地址；「开源与引用」移到功能卡片下面。
4. **卡面支持重命名**：长按卡面 → 重命名卡面，可自定义名称（留空恢复默认），
   方便区分「公司.png」「卡面 02e62416」这些分不清的卡片。已实测改成「门禁卡」生效。


## 19:03 细节调整

- **保存不再要求重命名**：选图后点「保存并应用」直接用自动文件名（`card-日期-时间.png`）保存并应用，
  不再弹文件名对话框；想改显示名称到首页长按卡面「重命名卡面」即可。
- 已实测：从图库选图 → 保存并应用 → 直接提示「已应用到卡包 · 替换图 card-20261107-190315.png 已保存」。


## 19:26 修复「打开 LSPosed 打开了别的软件」

- 根因：`LsposedManager.find()` 用「provider authority 以 `.XposedService` 结尾」来找管理器宿主。
  但 libxposed 的**每个模块 App** 都会声明自己的 `<包名>.XposedService` provider（原版模块也是），
  所以遍历时先命中的往往是某个模块 App；随后 `open()` 又退化成「启动这个包的 launcher」，
  于是打开成了那个 App 的主界面。
- 修复：
  - 识别改为「管理器活动」判据（`org.lsposed.manager.ui.activity.MainActivity` / `org.lsposed.manager` 包），
    普通模块 App 不再被当成管理器（新增单测覆盖）。
  - 本机是 LSPosed **寄生/隐藏管理器**：LSPosed 用进程内一次性随机 UUID 广播拉起管理器
    （`LSPNotificationManager.openManagerAction`），第三方 App 拿不到、也无法直接 startActivity。
    因此 `open()` 改为「只在确认可启动时才打开」，失败返回 false。
  - 使用说明页点「打开 LSPosed」失败时弹说明：下拉通知栏点「Android 系统 · Xposed 模块已更新」，
    在里面启用模块/勾选作用域（README 使用说明）。
  - 设置页「LSPosed 作用域」显示真实状态：「寄生/隐藏模式 · 从 LSPosed 通知打开管理器」。
  - 诊断终端新增「LSPosed 诊断」，输出候选包与最终选择，便于以后适配其他管理器。
- 另外把 hook 自动刷新版本升到 3：模块更新后打开 App 会自动清 hook 缓存并重启卡包。


## 19:45 切换界面卡顿修复

1. **主题不再跟随全局数据版本重建**：`CardFaceApp` 原来 `remember(prefs.revision)` 建 `ThemeController`，
   而 hook 每上报一张卡面（写 URL / last_hook_at）都会 `revision++`，等于使用过程中反复重建主题、全界面重组。
   现在新增 `AppPrefs.appearanceRevision`，只有动态取色 / 主题色 / 深色模式 / 动画开关变化才重建主题。
2. **LSPosed 检测加缓存并降开销**：原来 `LsposedManager.find()` 每次全量 `getInstalledPackages(GET_ACTIVITIES|GET_PROVIDERS)`
   扫 200+ 包，而且状态会被首页/设置/引导页各调一次；切页时新旧页面同时组合 → 明显卡。
   现在：
   - 先只查 providers 找模块痕迹，再仅对少量候选（管理器包、com.android.shell、模块包）查 activities；
   - 结果缓存 30 秒，诊断按钮可强制刷新；
   - `hasFrameworkTrace`/`isInstalled` 共用同一份缓存。
3. 保留「模块更新后自动重启卡包」与首页「重启卡包」按钮。


## 19:53 关于页修复

1. **滑到底部崩溃**：`AboutScreen` 里 `LazyColumn` 的 `item(key=...)` 因为之前几次重排脚本插入重复段落，
   出现了重复 key（`help`、`oss` 各两份）。Compose LazyList 遇到重复 key 会在滚动到该项时抛
   `IllegalArgumentException` 直接崩溃。已整段重写关于页，所有 key 唯一。
2. **删掉关于页的 QQ 群**：帮助与反馈只剩「使用说明 / 常见问题」；支持作者卡片不再带 QQ 群按钮。
3. **加「重构者」**：应用信息卡片加了一行「重构者：DeepSeek（AI 编程助手）」，
   开源与引用里也补了对应说明（负责 Miuix 界面重构、卡面 hook 链路、图像管线与性能优化）。


## 20:00 文案调整

- 去掉应用内所有「免 root / 不需要 root」的宣称（关于页、引导页、常见问题、设置项摘要），改为说明：
  替换图会复制到卡包私有目录并用 `file://` 加载，复制步骤需要 root；无 root 时退回 `content://` 兜底。
- 关于页新增「兼容性与免责声明」：ColorOS 卡包未做真机验证、小米智能卡验证环境、其他风险与使用限制。


## 20:05 新图标（HyperDay 风格）

- 参考 HyperDay 的图标：**白色背景 + 澎湃渐变图形**。
- 图形直接使用关于页同款 Miuix `BankCards` 图标（两张叠放的银行卡 + 白色芯片），
  矢量路径取自 Miuix 0.9.4 的 `BankCards.Regular`，填充改为线性渐变
  `#FF9A8B → #F56E9E → #7A6CF7`（颜色取样自 HyperDay 图标）。
- 自适应图标结构：background 纯白 `#FEFEFE`，foreground 渐变图形（scale 0.55 留白），
  monochrome 用同款图形做主题取色。


## 20:15 图标渐变更强 + 隐藏图标防丢 + 系统诊断

- **图标渐变取真实取样值**：对 HyperDay 图标前景 PNG 做纯色像素采样，沿对角线得到渐变
  `#FC827F（左上）→ #F88286 → #EC809D → #E681A7 → #DD82C0 → #CB81DC → #B67DE7 → #9D7BF0 → #7D7AF9（右下）`。
  新版图标使用 5 个 stop：`#FF7E74 → #EC7FA0 → #D07BD4 → #A878F3 → #6A5BFF`，白底。
- **隐藏桌面图标防丢失**：隐藏后额外发一个常驻通知「卡面工坊已隐藏 · 点此打开」；
  每次启动时用 prefs 校正 activity-alias 的实际状态（防止以前隐藏/恢复失败把图标弄丢）。
- **系统诊断导出**：诊断终端新增「导出系统诊断」，会导出 logcat crash、systemui/system_server 关键字、
  dropbox 列表、SystemUI 崩溃文件与 LSPosed 日志里的 systemui 记录到 `/sdcard/Download/sysdiag.txt`。
- 临时「卡面工坊入口」(com.dsh.cardface.recovery) 是隐藏测试时的救援 APK，验证完可删除。
