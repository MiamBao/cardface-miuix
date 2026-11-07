# 卡面工坊 · Miuix 界面规格（v1）

目标：把原版「DIY NFC卡面图片」（截图见 `docs/ref/before-*.jpg`）重制成
**Miuix / HyperOS 风格**、操作路径一眼看懂的 App。视觉参考 SukiSU
（`docs/ref/sukisu-home.jpg`）：大标题 + 高信息量状态卡 + 圆角卡片列表 + 悬浮底部导航。

## 0. 设计原则

1. **状态可见**：首页第一眼就能看到「模块生效了吗 / 卡包装了吗 / 识别到几张卡面」。
2. **一条主路径**：首页点卡面 → 选图 → 裁剪 → 保存。任何一步都在标题栏写出当前步骤。
3. **不需要 root**：默认用 content:// 直接把图库图片给卡包进程读；
   root 复制只作为「兼容模式」藏在高级设置里。
4. **少文字、讲人话**：原版 FAQ 里的术语（hook 点、作用域）只在设置页出现，并配一句解释。
5. **SukiSU 观感**：浅灰背景 + 白色大圆角卡片 + 强调色状态卡 + 悬浮胶囊导航。

## 1. 设计 token（用 MiuixTheme，不写死颜色）

- 主题：`ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFF2F6BFF))`，
  跟随系统深色模式；所有颜色用 `MiuixTheme.colorScheme.*`。
- 页面背景：`colorScheme.background`；卡片：`Card`（默认 `surface`，圆角 16–20dp）。
- 主色状态卡：用 `primaryContainer` / `onPrimaryContainer`；
  「未生效」用 `errorContainer` / `onErrorContainer`（Miuix 只有 error 红，不要造警告色）。
- 文本：`MiuixTheme.textStyles.title1`（大标题，FontWeight.Bold）、`headline2`（卡片大标题）、
  `body1` / `body2`（正文）、`footnote1` / `footnote2`（说明/次要）、`button`（按钮）。
- 圆角：卡片 20dp，缩略图 16dp，chip/胶囊 = 全圆角（`CircleShape`）。
- 间距：页面左右 16dp；卡片内 16–20dp；分组之间 12dp；`SmallTitle` 用于分组标题。
- 图标：只用 `miuix-icons` 已有图标（`MiuixIcons.Home / Photos / Settings / Add / Delete /
  Refresh / Back / Info / Help / Link / Image / BankCards / Hide / Show / Clear / Rename /
  Check / Close / More` 等，先查
  `/root/workspace/miuix-src/miuix-icons/src/commonMain/kotlin/top/yukonga/miuix/kmp/icon/extended/`）。

## 2. 信息架构

单 Activity + Compose 状态导航（不用 miuix-nav，降低风险）：

| 路由 | 页面 | 入口 |
|---|---|---|
| `home` | 卡面（首页） | 底部导航 / 启动 |
| `gallery` | 图库 | 底部导航 |
| `settings` | 设置 | 底部导航 |
| `editor/{url}` | 自定义卡面 | 首页卡片 |
| `crop` | 裁剪（选择本地图后进入） | 编辑页 |
| `guide` | 使用说明 | 首次启动 / 设置 |
| `faq` | 常见问题 | 设置 |
| `about` | 关于 / 捐赠 | 设置 |
| `blacklist` | 卡面黑名单 | 设置 |

底部导航：`FloatingNavigationBar`（3 项：卡面 / 图库 / 设置），悬浮胶囊，参考 SukiSU。
子页面用 `TopAppBar`（返回 + 标题 + 需要的操作），内容区 `Scaffold` + 滚动。

## 3. 首页（卡面）

```
[状态栏留白]
卡面工坊                                        [刷新]
┌───────────────────────────────────────────┐
│  ● 模块已生效                    ╭────╮   │  ← 主色/错误色大卡，20dp 圆角
│  已识别 3 张 · 已替换 1 张        │ ✓  │   │
│  打开卡包浏览卡面后，回到这里下拉刷新 ╰────╯   │
└───────────────────────────────────────────┘
已识别卡面 (3)
┌──────────────┐ ┌──────────────┐
│  [160:101图] │ │  [160:101图] │
│        已替换│ │        未替换│  ← 右上角小胶囊
│ 中信颜卡     │ │ 市政交通一卡通│
└──────────────┘ └──────────────┘
```

- 大标题行：`Text("卡面工坊", title1, Bold)` + 右侧 `IconButton(Refresh)`。
- 状态卡（`Card` + `primaryContainer` 背景），三行：状态标题（headline2 Bold）、
  「已识别 N 张 · 已替换 M 张」（body2）、提示（footnote1）；右侧大图标 72dp（Check / Info）。
  - 生效判定见 `ModuleStatus.moduleActivated`（hook 进程上报时间戳 > 0 且 24h 内为「已生效」，
    有过上报但超过 24h 为「上次生效：x 天前」，从未上报为「未生效」）。
  - 未生效时卡片用 errorContainer，并显示「去启用」按钮跳 guide。
- 卡面列表：`LazyVerticalGrid(2 列, spacing 12dp)`；每项 `Card`：
  - 上部 160:101 图片（Coil 加载：已替换 → 本地文件；未替换 → 原 URL），`ContentScale.Crop`，
    圆角 16dp；加载失败显示图标占位（`Image` 图标 + 「加载失败」）。
  - 右上角胶囊：已替换 = primary 底 + 「已替换」；未替换 = 半透明黑 + 「未替换」。
  - 下部一行：`displayName`（body2，1 行省略）+ 右侧 `ChevronForward` 小图标。
  - 点击 → editor(url)。
  - 长按 → 底部弹层：复制地址 / 加入黑名单 / 取消。
- 空状态（无卡面）`Card`：标题「还没有识别到卡面」+ 三步说明（带序号圆点）：
  1. 在 LSPosed 里启用「卡面工坊」并勾选作用域（小米智能卡）
  2. 打开卡包，把卡面浏览一遍
  3. 回到这里，下拉刷新
  按钮：「刷新列表」（主）、「打开卡包」（次，未安装卡包时隐藏）。
- 下拉刷新：`PullToRefresh` 或普通 `SwipeRefresh` 替代——用 Miuix `PullToRefresh`；
  刷新动作 = 重新读 prefs + 触发 Coil 重新加载。

## 4. 自定义卡面（editor/{url}）

```
← 自定义卡面                    [清空替换]
┌───────────────────────────────────────┐
│         [160:101 预览，圆角]           │  ← 当前替换图（或原图）
└───────────────────────────────────────┘
当前效果：已替换 / 原图
[从相册选择] [粘贴网址] [从图库选]     ← 三个 Button（次按钮样式）
卡面标志
[无] [京津冀] [上海] [泉城通] [银川]    ← LazyRow，选中高亮；本地图裁剪后才出现
圆角裁切                          [开关]  ← 默认开
输出 960×606 PNG · Lanczos3 高质量缩放   ← footnote2 说明
────────────────────────────────────────
[        保存并应用        ]             ← 主按钮，固定在底部安全区
```

- 预览从 `replacement` 或原 URL 加载；选本地图/裁剪完成后预览变成本地结果。
- 「粘贴网址」→ `OverlayDialog`：TextField 填 URL + 取消/确定；确定后直接映射为远程替换值
  （不写文件，不做缩放）。
- 「从图库选」→ `OverlayBottomSheet`：图库缩略图网格，点选即用。
- 「从相册选择」→ Photo Picker（`ActivityResultContracts.PickVisualMedia`），取到 Uri 后进入
  `crop` 页（裁剪比例锁定 160:101，可双指缩放/拖动，最小缩放=铺满）。
- 裁剪完成 → 回到 editor，预览显示裁剪结果；显示卡面标志 chips（`assets/card_logo/*.png`：
  jingjinji=京津冀、shanghai=上海、qvanchengtong=泉城通、yinchuan=银川）。
- 保存并应用 → 先弹文件名输入框（默认 `card-yyyyMMdd-HHmmss`，校验非法字符/重名），
  然后后台合成（圆角 + 标志 + 960×606）→ 写入图库 → `prefs.setReplacement(url, name)` →
  底部弹层/对话框显示成功：「已应用到卡包，重启卡包后生效」+ 按钮「重启卡包」（需要时调用
  Core 的 killWallet）/「完成」。
- 保存中显示 `ProgressIndicator`，按钮禁用；失败显示错误原因（不吞异常）。
- 「清空替换」二次确认后 `prefs.setReplacement(url, null)`。

## 5. 图库（gallery）

- 大标题「图库」+ 右上角 `IconButton(Add)`（打开相册选择 → 直接裁剪 → 保存为图库图片）。
- `LazyVerticalGrid(3 列)`：160:101 缩略图 + 文件名（footnote2，1 行）。
- 点按 → 全屏预览（深色背景 + 图片，关闭按钮）。
- 长按 → 删除确认（说明：已应用到卡面的图片会被一起清掉映射）。
- 空状态：图标 + 「还没有图片」+「添加图片」按钮。

## 6. 设置（settings）

大标题「设置」。分组（`SmallTitle` + `Card`，组内用 `BasicComponent` 行 + `Divider`）：

**模块**
- 模块状态：summary = 已生效 / 未生效 / 上次生效 x 天前；右侧状态图标；点击 → 说明对话框。
- 卡包应用：summary = 小米智能卡 / 未安装；点击 → 打开卡包（未装则提示）。
- LSPosed 作用域：summary = 已勾选 / 未检测到；点击 → 打开 LSPosed 管理器。

**卡面显示**
- 隐藏桌面图标（`Switch`）：开启后从桌面消失，仅能从 LSPosed 模块设置进入，弹确认。
- 包含小米超级岛卡面（`Switch`，仅小米卡包时显示）。
- 显示全部卡面（`Switch`）：关闭时过滤 w270h480 / door-card-img/logo / Mibi 小图。
- 主界面卡面黑名单（箭头 → blacklist 页）。

**缓存与修复**
- 清除 hook 点缓存（箭头；确认后清缓存并强制停止卡包）。
- 清除图片缓存（箭头；确认后清 Coil 缓存 + 应用缓存，并重启本应用提示）。

**高级**（长按「关于」10 次或版本号解锁，`devMode`）
- 输出调试日志（`Switch`）。
- 自定义 hook 点（箭头 → 子页）：类名 / 方法名 / 参数类名 三个 TextField + 两个 Switch
  （从参数读 URL、把替换写回参数），保存后提示需重启卡包。
- 兼容模式：复制图片到卡包目录（需 root）（`Switch`，默认关）。

**关于**
- 版本 v1.0.0（summary「Miuix 重制版」）。
- 使用说明 → guide；常见问题 → faq；加入 QQ 群（点击复制群号 820056900 / 尝试打开 QQ）；
- 捐赠 → about 页底部；无钱计划 → 对话框（承诺免费、无付费功能）。

## 7. 首次启动引导（guide）

- 首次启动直接显示 guide（「开始使用」按钮进入首页，写入 `first_run=false`）。
- 内容：三个大步骤卡（编号圆点）：
  1 启用模块（按钮：打开 LSPosed）
  2 浏览卡包（按钮：打开卡包）
  3 回到卡面工坊刷新（按钮：刷新并开始）
- 底部小字：「本应用不收集任何数据；替换图只存在本机。」

## 8. 交互/工程约束

- 页面滚动用 `LazyColumn` / `verticalScroll`；内容底部留出底部导航高度 + 24dp。
- 所有可点区域用 Miuix 组件（`Button` / `BasicComponent` / `IconButton` / `Card(onClick=...)`）。
- 对话框：页内 `OverlayDialog`；跨页/toast 用 `WindowDialog`/`Snackbar`（需 `Scaffold`）。
- 深色模式必须可读（不要写死白色/黑色）。
- 图片加载统一走 Coil（`AsyncImage`），File 用 `File`/路径，content 用 `Uri`。
- 文案中文，不出现英文缩写（LSPosed 除外）。
- 所有新增代码放 `app/src/main/kotlin/com/dsh/miuix/cardface/ui/**`，
  根组件 `CardFaceApp()` 可改；**不要修改** `core/**`（按需调用即可）、
  gradle/manifest/resources（例外：需要新增字符串请直接用 Kotlin 字符串）。
- 编译命令：`/root/bin/cardface-build.sh :app:assembleDebug`
  （同一时间只允许一个构建，脚本已加 flock；失败先看 `/tmp/cardface-build.log`）。
- 自测：`/root/bin/cardface-build.sh :app:assembleDebug` 必须通过；
  安装到手机截图验证（Leads 负责装机和最终视觉确认，你也可以用 mcp__phone__* 自测，
  但改机前先在任务里说明）。

## 9. 验收标准

1. 三个底部页签 + 编辑页 + 裁剪页 + 黑名单 + 说明/FAQ/关于 全部可进入、返回键正确。
2. 空状态、有数据状态、模块未生效状态都有合理界面，不出现空白页。
3. 明暗两种模式下文字/卡片对比度正常。
4. 没有任何 Material 组件混入（全部 Miuix）。
5. 首页 → 选图 → 裁剪 → 保存 一条龙可用，保存后首页卡片立即变「已替换」。
