# 原版「DIY NFC卡面图片」v2.4 功能规格（重制版验收基线）

> 本文档是**原版行为**的完整拆解，用来当重制版（卡面工坊）的验收清单。
> 重制版允许实现更好，但**第 11 节清单里的每一条都必须有明确的「对齐 / 有意改进」结论**；
> 凡属「有意改进」的条目，在第 10 节「技术债」里必须能找到对应理由。

| 项目 | 值 |
|---|---|
| 原版包名 | `com.zhizi42.diymiuicard` |
| 版本 | versionName `2.4` / versionCode `14` |
| SDK | minSdk 26 / targetSdk 36 / compileSdk 36 |
| 模块形态 | LSPosed 模块（libxposed API 101，`META-INF/xposed/*`） |
| 反编译源码 | `/root/workspace/apk/src/sources`（jadx 默认 dump） |
| 资源 | `/root/workspace/apk/res/resources`（Manifest / res / assets / META-INF） |
| APK | `/root/workspace/apk/diy.apk` |
| 补充反编译 | 用 `jadx --show-bad-code` 二次反编译（临时目录），恢复原 dump 中被标 `Method not decompiled` 的关键方法；再用 dex 字节码逐条核对 |
| 相关设计文档 | `docs/HOOK-SPEC.md`、`docs/IMAGING-SPEC.md`、`docs/UI-SPEC.md`（重制方案，不是原版行为） |

**证据等级约定**

- ✅ = 已对照 dex 字节码核对（最可靠）
- 📄 = 反编译 Java 源码/资源原文（可靠；个别 try/catch 嵌套曾被 jadx 放错，已单独标注）
- ⚠️ = 反编译推断，重制版不必完全照抄，但要在实现里给结论

**字符串甄别规则**：`res/values/strings.xml` 里混有 androidx / material / uCrop 的字符串。
本应用自有字符串 = Manifest / `com.zhizi42.diymiuicard` 代码 / 本应用 layout / 本应用 preference xml
实际引用到的那些；其余（`abc_*`、`m3_*`、`mtrl_*`、`material_*`、`bottomsheet_*`、`ucrop_*` 等）属依赖库。
`ucrop_*` 只有 `values-zh` 有中文翻译，默认（非中文系统）uCrop 界面是英文。

---

## 1. 应用结构与启动

### 1.1 组件一览

| 组件 | exported | 说明 |
|---|---|---|
| `MainActivity`（singleTop） | true | 唯一主界面；intent-filter = `MAIN` + `de.robv.android.xposed.category.MODULE_SETTINGS`（LSPosed 里「模块设置」入口） |
| `com.zhizi42.diymiuicard.StartMainActivity`（**activity-alias**） | true | target = MainActivity，intent-filter = `MAIN` + `LAUNCHER` = **桌面图标**；隐藏图标就是禁用这个 alias |
| `SettingsActivity` | false | label=`title_activity_settings`（设置）；parent = MainActivity |
| `SelectImageActivity` | false | 添加图片/选图/裁剪/标志/保存；parent = MainActivity |
| `BlackListActivity` | false | 主界面卡面黑名单；parent = SettingsActivity |
| `DonateActivity` | false | 捐赠页（支付宝/微信收款码 + 打开支付宝按钮） |
| `com.yalantis.ucrop.UCropActivity` | — | 第三方 uCrop，theme `Theme.AppCompat.Light.NoActionBar` |
| `MyPreferenceProvider` | true | authority `com.zhizi42.diymiuicard.preference`，给 LSPosed RemotePreferences 用（见 §6.7） |
| `io.github.libxposed.service.XposedProvider` | true | authority `com.zhizi42.diymiuicard.XposedService`；模块 App 与 LSPosed 服务通信 |
| `androidx.startup.InitializationProvider` | false | androidx 启动项（emoji2 / lifecycle / profileinstaller） |
| `androidx.profileinstaller.ProfileInstallReceiver` | true | 依赖库自带 |

### 1.2 Manifest 其他事实

- 权限：只有 `android.permission.INTERNET`（+ androidx 自用的 signature 级 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）。
- `application`：`usesCleartextTraffic=true`、`allowBackup=true`、`extractNativeLibs=false`、`supportsRtl=true`、
  `android:description="@string/description"`。
- 原生库只有 `lib/arm64-v8a/libdexkit.so`（**仅 arm64**，32 位设备/模拟器不可用）。
- Theme：`Theme.DiyMiuiCard` → `Base.Theme.DiyMiuiCard`（parent `Theme.Material3.DayNight.NoActionBar`，
  但被引用的 `Theme.Material3.DayNight.NoActionBar` 在资源里 parent 又是 `Theme.Material3.Light.NoActionBar`）
  ⇒ **原版实际是固定浅色主题**，没有真正的 DayNight 深色适配。
- 主色：`colorPrimary=#03dac5`、`colorAccent=#66ccff`、`colorPrimaryDark=#39c5bb`；
  所有 Toolbar 背景 = colorPrimary，标题文字 = 白色（`CustomActionBarTitleText`）。
- 系统栏（`dj.a`）：每个 Activity `onCreate` 里手动设置 —— 状态栏透明、导航栏浅色半透明
  （`argb(230,255,255,255)`）/深色半透明（`argb(128,27,27,27)`），并切换状态栏/导航栏图标明暗；
  Toolbar 通过 `OnApplyWindowInsetsListener` 自行加顶部 inset（`fitsSystemWindows=false`）。

### 1.3 Xposed 模块声明

`META-INF/xposed/`：

| 文件 | 内容 |
|---|---|
| `module.prop` | `minApiVersion=101`、`targetApiVersion=101`、`staticScope=true` |
| `java_init.list` | `com.zhizi42.diymiuicard.Hook` |

- 入口类 `com.zhizi42.diymiuicard.Hook extends io.github.libxposed.api.XposedModule`（`@qt` 注解登记）。
- 静态块 `System.loadLibrary("dexkit")`（DexKit 2.x）。
- 生命周期只用 `onPackageReady(PackageReadyParam)`。

### 1.4 启动路径

1. 桌面图标 → `StartMainActivity`（alias）→ `MainActivity`。
2. LSPosed 管理器 → 该模块「模块设置」→ `MainActivity`（MODULE_SETTINGS category）。
3. 隐藏图标后只能走 (2)；`MainActivity` 的 exported 入口保证 (2) 始终可用。

### 1.5 本地状态（重制版必须逐项确认，否则会出现「设置不生效」）

原版有三套“设置存储”，键名与用途不同，**这是最容易踩的坑**：

| 存储 | 获取方式 | 存什么 |
|---|---|---|
| `settings`（SharedPreferences，MODE_PRIVATE） | `getSharedPreferences("settings", 0)` | ① 卡面数据：`<卡片URL>` → 替换值（URL 或文件名）；② `all_card_url_set`；③ `black_card_url_set`；④ `first_run`；⑤ `first_add_image`；⑥ **远程同步过来的 hook 配置**：`super_land` / `show_all_cards` / `debug` / `test_mode` / `class` / `method` / `method_arg` / `load_url_from_arg` / `replace_url_to_arg` |
| `com.zhizi42.diymiuicard_preferences`（androidx PreferenceManager 默认文件） | `getSharedPreferences(getPackageName()+"_preferences", 0)` | 设置页控件的自动持久化副本（`hide_icon`、`super_land`、`show_all_cards`、`debug`、`test_mode`、自定义 hook 五个字段）+ 手动写的 `dev_mode_open` |
| `settings`（hook 进程侧） | `XposedModule.getRemotePreferences("settings")`（经 `content://com.zhizi42.diymiuicard.preference/settings`） | 与第 1 行是**同一个文件**，跨进程访问通道不同 |

关键机制：
- 设置页控件值**显示**读默认 prefs；**改动**时监听器（`h90`）同时写入远程 `settings`。
  因此 hook 读到的和设置页显示的正常情况下一致。
- `LSPosed` 服务未连接（`dr.y == null`）时：设置页里所有需要远程同步的控件只调用
  `setPersistent(false)`（**不保存**），且不显示开关的真实状态更新；`bx.i/g` 写映射时
  仍会写本地 `settings`，但会 toast `xposed service not exist`。
- `dev_mode_open` 只在默认 prefs（不进远程），所以 hook 进程看不到它（也不需要）。

文件/目录：

| 路径 | 用途 |
|---|---|
| `/data/data/com.zhizi42.diymiuicard/files/images/` | 本应用的图片库（保存的 PNG；列表“我的图片”） |
| `/data/data/com.zhizi42.diymiuicard/cache/cache.png` | uCrop 输出临时文件，保存成功后删除 |
| `/data/data/<卡包包名>/files/images/` | 实际给卡包进程读取的图片（root 复制） |
| `/data/data/<卡包包名>/files/zhizi42.diycard.method*.txt` | hook 点缓存（见 §6.3） |
| 卡包 `files/images/` 目录 | Hook 在 `prepareHook` 里 `mkdir()` 保证存在 |

- `MainActivity.onCreate` 会 `mkdirs()` 本应用图片目录；失败时 Toast 硬编码 `"create my app images folder failed!"`。

### 1.6 卡包类型（OSType）与检测

| OSType | 包名 | 说明 |
|---|---|---|
| 0 | `com.miui.tsmclient` | 小米智能卡（MIUI/HyperOS） |
| 2 | `com.meizu.mznfcpay` | 魅族钱包：**代码残留**，`onPackageReady` 不接受该包，UI 也检测不到 ⇒ 实际不可用 |

- 检测函数 `gr.e(context)`：先 `getPackageInfo("com.miui.tsmclient", 128)` → 0；失败再

---

## 2. 首页（MainActivity）

### 2.1 布局结构（activity_main.xml）

```
[Toolbar：标题 “DIY NFC卡面图片”，背景 colorPrimary，白字]
[左：设置按钮 ic_settings]  [中间：34sp 状态文字]  [右：删除按钮 ic_delete]
SwipeRefreshLayout
  └ RecyclerView（垂直 LinearLayoutManager，item 行）
```

- 状态文字 `textView`：`textSize=34sp`，初始 `@string/text_disable`（“已禁用”），
  LSPosed 服务连上后由 `mw.i()` 改成 `@string/text_enable`（“LSPosed已启用”）。✅
- 状态文字极大（34sp），是原版最显眼的大字号问题。
- 无空态占位、无引导（列表为空时只有一片空白）。

### 2.2 数据来源与刷新

- 数据 = `getSharedPreferences("settings",0).getStringSet(all_card_url_set)`（主界面）
  或 `black_card_url_set`（黑名单页，见 §5.3/§4）。
- `bx.h()`：重新读 prefs → `ArrayList` → 排序（`ax` 比较器）→ `notifyDataSetChanged()`。
- 触发刷新的时机：Activity 创建、下拉刷新、映射变更（选图/清空/删除）、滑动加入黑名单、删除全部。

### 2.3 排序规则（`ax` 比较器，✅）

1. 有替换映射的（`prefs.getString(url,"")` 非空）排在**前**；
2. 同组内按 URL 字符串**字典序升序**；
3. 没有其它排序（无时间、无名称）。

### 2.4 item 两种布局

`bx.c(i)` 返回 viewType：`getString(url,"").isEmpty() ? 0 : 1`。

| viewType | 布局 | 内容 |
|---|---|---|
| 0 = 未替换 | `item_card_original.xml` | 一个 ImageView（160×101dp，`layout_weight=1` ⇒ 实际被拉满整行宽）；Glide 加载**卡片 URL 原图**，占位 `ic_card` |
| 1 = 已替换 | `item_card_replaced.xml` | 左：原图 160×101（Glide URL，占位 `ic_card`）；中：`ic_arrow_right`；右：替换图 160×101（`ic_image` 占位） |

替换图加载规则（`bx.d`）：

- 映射值为空 → 不会进这个布局（viewType 0）。
- 映射值 `http://` 或 `https://` 开头 → 直接按 URL 加载。
- 其他 → 当文件名，加载 `File("/data/data/com.zhizi42.diymiuicard/files/images/"+value)`（Glide + 占位 `ic_image`）。
- ⚠️ 文件名不存在时 Glide 失败只显示占位图，无错误提示。

### 2.5 交互

| 操作 | 行为 | 文案/细节 |
|---|---|---|
| 点原图（type 0）或点左图（type 1） | 打开「图片输入对话框」（§4）；主界面 `g=false` 才有此点击；黑名单页无 | — |
| 点右侧替换图（type 1） | 同一对话框，额外把该 ImageView 传进去（供彩蛋用，见 §4.6） | — |
| 长按原图/左图 | 弹「卡片网址」：标题 `card_url_title`，正文 = URL；按钮「确认」「复制」 | 复制到剪贴板，label = `"image url"`；无 toast |
| **左滑** item | 加入黑名单：从 `all_card_url_set` 移除、写 `black_card_url_set`、`bx.g(url)` 删除该 URL 映射、列表移除并 notify | 滑动背景色 `#E90000`，图标 `ic_archive`；**无确认、无撤销** |
| 下拉 | `bx.h()` 重新读 + `setRefreshing(false)` | — |
| 右上删除按钮 | 确认框：标题 `confirm`（“确认”），正文 `confirm_delete_msg`（“确认删除所有卡片和图片信息吗？”），按钮 `取消` / `确认`（`pw(0)`） | ✅ 实际只做 `settings.edit().remove("all_card_url_set")` 然后刷新（远程同文件因此也看不到这些 URL）；**不删映射、不删图片、不删本地图库、不删黑名单、不碰卡包目录**。文案与行为严重不符 |

### 2.6 首次启动 / 服务状态

- `first_run`（默认 true）为 true 时，onCreate 里弹 `使用说明` 对话框：
  标题 `how_to_use_title`、正文 `how_to_use_msg`、按钮「确认」；随后写 `first_run=false`。只在**第一次**出现。
- LSPosed 服务连接回调（`mw.i`）：
  1. 保存 `dr.y`（服务代理）、`dr.z = 远程 settings`；把状态文字设为 `text_enable`；✅
  2. 检测 OSType：-1 → 弹 `no_card_app_installed_title/content` 对话框（仅「确认」）；
  3. OSType 0/1 → 查询服务已授权的 scope 列表（`IXposedService.transact(11)`）：
     不含本 OS 的卡包包名 → 弹 `请添加卡包APP到作用域` / `模块作用域没有卡包APP，请点击确定后，同意添加卡包APP到本模块作用域。`
     → 确认按钮调用 `transact(12)` 请求把卡包加进作用域。
- 若 LSPosed 服务一直不连接：状态文字一直「已禁用」，其它功能按 §1.5 的降级逻辑走。

---

## 3. 选图 → 裁剪 → 卡面标志 → 保存（SelectImageActivity）

### 3.1 整体流程

```
图片输入对话框 [添加图片] ──(首次: root 提示)──▶ SelectImageActivity
  ├─ 立即弹系统选图（GET_CONTENT, image/*）
  ├─ 选图 → 预览（setImageURI + 全尺寸 decode）
  ├─ [裁剪至标准大小并可选添加卡面标志] → uCrop
  ├─ uCrop 返回 → 预览 + 卡面标志横滑列表
  ├─ 点标志 → 圆角 + 叠加标志 → 预览
  └─ [确定添加] → 保存文件名对话框 → compress/root 复制 → 返回 MainActivity(FILE_NAME)
```

### 3.2 启动与选图

- 入口：图片输入对话框的「添加图片」按钮（`tw` case）：
  - `settings.first_add_image` 默认 true：先弹 root 说明对话框
    （标题 `input_image_dialog_confirm_root_title`「请授予root权限」，正文 `input_image_dialog_confirm_root_msg`，
    按钮「取消」/「确认」）；确认 → `first_add_image=false` 并启动 `SelectImageActivity`。
  - 已经是 false：直接启动。
- MainActivity 用 `ActivityResultLauncher<Intent>`（StartActivityForResult 契约）启动，结果回调 `ow.a`。
- SelectImageActivity `onCreate` 里立刻调用 `ActivityResultContracts.GetContent`：
  `Intent(ACTION_GET_CONTENT).addCategory(CATEGORY_OPENABLE).setType("image/*")`。
- 选图回调（`ts.a`）：Uri 为 null → `finish()`；否则记到 `C`，预览 `setImageURI`，
  **`BitmapFactory.decodeStream` 全尺寸解码**存到 `F`（无 `inSampleSize`，大图 OOM 风险）。
- `gr.e(this)` 若 = -1 → 弹 `no_card_app_installed_title/content` 对话框（不阻止后续操作）。

### 3.3 uCrop 参数（✅ 字节码常量已核对）

| extra | 值 |
|---|---|
| `com.yalantis.ucrop.InputUri` | 选中的图片 Uri |
| `com.yalantis.ucrop.OutputUri` | `Uri.fromFile(File(cacheDir,"cache.png"))` |
| `com.yalantis.ucrop.AspectRatioX` | `160.0f` |
| `com.yalantis.ucrop.AspectRatioY` | `101.0f` |
| `com.yalantis.ucrop.MaxSizeX` | `960` |
| `com.yalantis.ucrop.MaxSizeY` | `606` |
| `com.yalantis.ucrop.CompressionFormatName` | `"PNG"` |
| `com.yalantis.ucrop.CompressionQuality` | `100` |
| `com.yalantis.ucrop.ToolbarColor` | `@color/colorPrimary`（#03dac5） |

- 设置 AspectRatioX/Y 会让 uCrop **锁定 160:101 比例并隐藏比例选择器**。
- `MaxSize` 表示「输出最长边不超过 960×606」：源图更小时**不放大**，输出可能小 960×606。
- 返回：`onActivityResult(requestCode=69, RESULT_OK)`，从 `intent.getParcelableExtra("com.yalantis.ucrop.OutputUri")`
  解码 → `E = F = bitmap`，预览显示；同时显示卡面标志列表。

### 3.4 卡面标志（assets）

- 位置：`assets/card_logo/*.png`，**4 张，都是 960×606 PNG**：
  `jingjinji.png`、`qvanchengtong.png`、`shanghai.png`、`yinchuan.png`。
- 列表 = `Arrays.asList(getAssets().list("card_logo"))`（字母序）+ 首项「空」；
  横向 `LinearLayoutManager(1)` + `c1(0)`（水平滚动）。
- 显示名（代码内硬编码）：

| 文件 | 显示名 |
|---|---|
| （首项，非文件） | `空` |
| `jingjinji.png` | `京津冀互联互通卡` |
| `shanghai.png` | `上海公共交通卡 交通联合版` |
| `qvanchengtong.png` | `泉城通` |
| `yinchuan.png` | `银川交通一卡通` |

- 未知文件名 → 直接显示文件名。
- item：128×128dp 黑底缩略图（加载 `file:///android_asset/card_logo/<name>`，占位 `ic_image`）+ 名称（20sp）。

### 3.5 点标志的合成逻辑（`ye` case 0，✅）

- 点「空」（index 0）：预览恢复为 `E`，`F = E`。**不做圆角、不缩放。**
- 点其它标志：
  1. 若 `E` 尺寸 ≠ 960×606：Toast `diy_card_toast_image_resolution_small`
     （“图片分辨率过小，最终图片效果可能不好”），然后 `Bitmap.createScaledBitmap(E, 960, 606, true)`
     （**双线性** filter=true；小图会被拉伸，大图会被缩）；
  2. `x(bitmap)` 做圆角（见 §9.2）；
  3. 从 assets 解码对应标志 PNG（960×606）；
  4. `copy(ARGB_8888, true)` → `Canvas.drawBitmap(logo, 0, 0, Paint(alpha=255))`（SRC_OVER 叠加）；
  5. `F = 合成图`，预览显示。
- 注意：**只有选标志时才做圆角**；选「空」或直接保存不裁剪图时不会圆角。

### 3.6 保存文件名对话框（`startSave`）

- 标题 `save_filename_dialog_title`（“保存文件名”）；自定义 EditText（hint `save_filename_dialog_edittext_hint`）。
- 按钮：`确认`（`y80` case 0）/ `取消`。
- 打开时确认按钮 `setEnabled(false)`；`a90` TextWatcher：
  `trim()` 非空 **且** 不匹配 `.*[/\\:*?"<>|].*` 才启用。
- 用户输入的是**不含后缀**的文件名，代码固定追加 `.png`（输入 `a.png` 会存成 `a.png.png`）。

### 3.7 保存过程（`y80.onClick` case 0，✅ 字节码已核对）

1. `fileName = input + ".png"`；
2. `localFile = new File("/data/data/com.zhizi42.diymiuicard/files/images/", fileName)`；
3. `exists()` → Toast `save_toast_file_exists`（“要保存的文件名已存在，请更换名字”），结束（不覆盖）；
4. `createNewFile()` 失败 → Toast `"create new image file failed!"`（硬编码英文）；
5. `F.compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(localFile))`；
6. 目标目录（`G` = 创建时检测到的 OSType）：

| OSType | 目标目录 | 命令 |
|---|---|---|
| 0 | `/data/data/com.miui.tsmclient/files/images/` | `chmod 666 "<local>"` / `cp "<local>" "<dir>/"` / `chmod 666 "<dir>/<fileName>"` |
| 2 | `/data/data/com.meizu.mznfcpay/files/images`（**少一个 `/`，bug**） | 同上 |
| -1 | `null`（未装卡包） | `cp ... "null"` → 必然失败 |

7. root 执行 `gr.m(list)`：`Runtime.exec("su --mount-master")` → 向 stdin 逐行写命令 → 读 stdout/stderr →
   `waitFor()==0` 才算成功。**在 UI 线程同步执行**（可 ANR）。
8. 失败 → Toast `select_image_save_copy_error_toast`
   （“复制文件失败，请确定授予root权限后联系开发者”）→ `finish()`（无 result，本地文件残留）；
9. 成功 → 删除 `cache/cache.png` → `setResult(RESULT_OK, Intent{extra:"FILE_NAME"=fileName})` → `finish()`。

### 3.8 保存完成后的回调

`MainActivity.onActivityResult`（`ow.a`）：
- 把 `File(imagesDir, FILE_NAME)` 加入**当前打开的图片对话框**列表，`notifyItemInserted`；
- 弹完成对话框：标题 `select_image_done_dialog_title`（“添加图片完成”），
  正文 `select_image_done_dialog_text`，按钮 `确认` + 第三按钮 `马上清除缓存`（`select_image_done_dialog_clear_cache`）；
- 「马上清除缓存」→ `gr.g(context)` 弹**清除图片缓存确认框**（见 §5.5）。

> ⚠️ 保存成功后**不会自动映射**到当前卡片。用户必须在图片输入对话框里点新图片缩略图才会写映射。
> 同一文件名重复保存会被第 3 步拦掉；但磁盘上旧映射仍指向旧文件内容。

### 3.9 不裁剪直接保存

- 选图后不点「裁剪」直接「确定添加」：`F` = 选图的原图（全尺寸），不做圆角、不做 960×606 处理，
  直接按原尺寸 PNG 保存。**uCrop 是可选的**，原版没有强制裁剪。

---

## 4. 图片输入对话框（`image_input_dialog`）

由 `bx.j(url, imageView)` 创建，只在 MainActivity 里可用（内部强转 `MainActivity`）。

### 4.1 结构

- 标题：`input_image_name_title`（“选择图片或输入url”）；
- 自定义视图（AndroidX AlertDialog 的 custom view）：
  - `RecyclerView`（我的图片，垂直 `LinearLayoutManager(1)+c1(1)`，maxHeight 约束）；
  - `ImageButton`（`ic_add_image`，“添加图片”）；
  - `EditText`（hint `input_image_input_hint`“图片URL”，多行、单行 false）；
- 按钮：`确认`（positive）/ `清空已选`（negative）/ `说明`（neutral）。

### 4.2 我的图片列表

- 数据 = `MainActivity.E.listFiles()`（本应用 `files/images/` 下全部文件，**顺序未排序**）。
- item：160×101 缩略图（Glide `File` + 占位 `ic_image`）。
- **点缩略图 = 建立映射**：`settings[url] = fileName`（文件名，不含路径），关闭对话框，刷新主列表。
- **长按缩略图 = 删除**（无确认）：先 `root rm "<目标卡包>/files/images/<name>"`（✅ 路径**硬编码为 MIUI**，
- 「添加图片」按钮 → 同 §3.2 的首次 root 提示 → `SelectImageActivity`。

### 4.3 URL / 确认 / 清空 / 说明

- 打开时 `EditText` 预填 `settings[url]`（当前映射值）。
- **确认**：`settings[url] = EditText 文本`（本地 + 远程）；刷新。空文本 = 恢复原图（等价清空）。
- **清空已选**：删除 `settings[url]` key（本地 + 远程），刷新。
- **说明**：对话框标题 `input_image_button_tips_title`（“说明”），正文 `input_image_tips_text`，按钮「确认」。
- 无 Xposed 服务时写本地后弹 toast `"xposed service not exist"`（但远程写失败不影响本地写）。

### 4.4 映射值语义（hook 和列表都依赖）

| 映射值 | 含义 | hook 返回 | 列表加载 |
|---|---|---|---|
| 空 | 不替换 | `""` → 原逻辑继续 | viewType 0 |
| `http://` / `https://` 开头 | 远程图片 | 原样返回该 URL，由卡包自己下载 | Coil/Glide 按 URL |
| 其它 | 本应用图片库文件名 | `file:///data/data/<卡包包名>/files/images/<value>` | `files/images/<value>` |

### 4.5 首次 root 提示

- `settings.first_add_image` 默认 true；第一次点「添加图片」时弹：
  标题 `input_image_dialog_confirm_root_title`、正文 `input_image_dialog_confirm_root_msg`、`取消`/`确认`；
- 确认 → `first_add_image=false` + 启动 SelectImageActivity；取消 → 不启动、不写标记。

### 4.6 彩蛋（保留是加分，不保留需在清单里标注）

- 在 URL 输入框输入 `rechrd what` 并点「确认」，且当前对话框是从**右侧替换图**进入（`imageView != null`）：
  会把右侧预览设为 `@drawable/general` 并直接 return，**不写映射**。（`bx.j` 内硬编码字符串）

---

## 5. 设置页（SettingsActivity + header_preferences.xml）

### 5.1 页面

- 布局 `settings_activity.xml`：Toolbar（标题 `settings_title_settings`“设置”）+ `FrameLayout` 承载 PreferenceFragment。
- 设置页数据源 = `R.xml.header_preferences`；点进「自定义hook点」时用 `x(preference)` 压栈
  `SettingsActivity$CustomHookFragment`（`R.xml.custom_hook_preferences`），标题改为该项标题。
- `onBackPressed` 自定义：Fragment back stack 可退则退，否则交给系统。
- 打开时取 `gr.e(requireContext())` 得到 OSType（iE）。
- **`dr.y == null`（LSPosed 服务未连接）时**：`super_land`、`show_all_cards`、`debug`、`test_mode`、
  自定义 hook 五个字段全部 `setPersistent(false)`（改动不保存），不设置远程写监听。

### 5.2 条目总表（键名 / 类型 / 默认 / 可见性 / 点击行为）

| # | key | 控件 | 默认 | 可见条件 | 点击/开关行为 |
|---|---|---|---|---|---|
| 1 | `hide_icon` | Switch | false | 始终 | 见 §5.4（确认后才隐藏图标） |
| 2 | `super_land` | Switch | false | **仅 OSType==0（MIUI）** | 写远程 `settings.super_land`；控制 Hook 是否额外定位超级岛方法（§6.3） |
| 3 | `black_list` | Preference | — | 始终 | 跳 `BlackListActivity`（§5.3） |
| 4 | `how_to_use` | Preference | — | 始终 | 弹「使用说明」：`how_to_use_title` / `how_to_use_msg` / 确认 |
| 5 | `faq` | Preference | — | 始终 | 弹「常见问题」：`settings_title_FAQ` / `settings_FAQ_msg` / 确认 |
| 6 | `test_mode` | Switch | false | 始终 | 写远程 `settings.test_mode`；见 §6.5 |
| 7 | `delete_image_cache` | Preference | — | 始终 | 弹确认框（§5.5）；**确认会崩溃，见 §10 P0-1** |
| 8 | `clear_hook_cache` | Preference | — | `iE != 2`（2 实际不出现 ⇒ **始终可见**） | 弹确认框 → 按 OS 强杀卡包 + 删 hook 点缓存（§5.6） |
| 9 | `dev_opt` | PreferenceCategory | — | `dev_mode_open==true` | 分组标题硬编码 `"developer option"` |
| 10 | `show_all_cards` | Switch | false | `dev_mode_open && OSType==0` | 写远程 `settings.show_all_cards`；控制 Hook 是否过滤小图/logo（§6.5） |
| 11 | `debug` | Switch | false | `dev_mode_open` | 写远程 `settings.debug`；控制 Hook/App 日志（`gr.h`） |
| 12 | `custom_hook` | Preference | — | `dev_mode_open` | 进子页 §5.7；标题 `settings_title_custom_hook` |
| 13 | `about` | `LongClickPreference` | — | 始终 | 点击弹「关于」对话框（见下）；**长按连续 4 次解锁 dev_mode** |
| 14 | `join_qq` | Preference | — | 始终 | 打开 QQ 加群深链（失败 Snackbar `settings_open_qq_fail`） |
| 15 | `donate` | Preference | — | 始终 | 跳 `DonateActivity` |
| 16 | `no_money` | Preference | — | 始终 | 弹「无钱计划」对话框：`settings_no_money_dialog_text` + 按钮「打开无钱计划网址」（跳 `https://github.com/NoMoneyPlan`）/「确认」 |

- 关于对话框：标题 `settings_title_about`，正文 `settings_about_msg`（`%s` 固定填 `"2.4"`），按钮「确认」。
- 捐赠页：QR 图 `alipay.png` / `wechat.png`，按钮 `donate_call_alipay_text` → 打开
  `https://QR.ALIPAY.COM/FKX00236BXA07MOA2XU607`（微信图只做了 insets 处理，无点击）。
- QQ 深链：`mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D5jcvi8iIuZuQVqm4HZXlVhxoMQ1RLVtF`

### 5.3 黑名单页（BlackListActivity）

- 标题 `activity_title_black_list`（“主界面卡面黑名单”）。
- 复用一个 `bx` 适配器，`g=true`：数据源 `black_card_url_set`，item 布局按「映射是否为空」决定
  （因为加入黑名单时映射已被删，所以基本都是 `item_card_original`，显示原图 URL）。
- 点击被禁用（`g=true` 不注册点击）；长按仍是「卡片网址 + 复制」。
- **滑动（左滑）→ 移出黑名单**：从 `black_card_url_set` 删除，列表移除；背景色 `#009AD6`，图标 `ic_unarchive`。
- 下拉刷新重新读。
- 空态同样没有占位。

### 5.4 隐藏桌面图标（原理，✅）

- 确认框：标题 `settings_hide_icon_dialog_title`、正文 `settings_hide_icon_dialog_text`、`取消`/`确认`。
- Switch 的 OnPreferenceChangeListener（`j8` case 4）：
  - 用户关闭 → `setComponentEnabledSetting(ComponentName(pkg,"com.zhizi42.diymiuicard.StartMainActivity"), ENABLED(1), DONT_KILL_APP(1))`，返回 true；
  - 用户打开 → 只弹确认框并返回 false（开关先不动）。
- 确认框「确认」→ `setComponentEnabledSetting(..., DISABLED(2), DONT_KILL_APP)`，并把 switch `setChecked(true)`。
- 即「隐藏图标」= 禁用 activity-alias（桌面入口），MainActivity 本身仍在，LSPosed 的 MODULE_SETTINGS 入口可打开。

### 5.5 清除模块图片缓存（P0 崩溃点）

- 二次确认：标题 `settings_clear_cache_dialog_title`、正文 `settings_clear_cache_dialog_text`、`取消`/`确认`。
- 确认 → `lg0(context,1)`：
  1. Glide 清磁盘缓存 / trim 内存池；
  2. 然后做线程判断：**主线程 → 抛 `IllegalArgumentException("You must call this method on a background thread")`**；
     非主线程 → 内存缓存 clear + Toast `clear_card_cache_succ`。
- 但入口又在对话框按钮（必在主线程），且前面还有 `jg0.a()` 断言必须在主线程。
  ✅ **字节码核对结论：从 UI 点确认 100% 抛异常崩溃（`m1.o` 直接 throw）。**
  重制版必须改成「后台线程清缓存 + 主线程弹 toast」，绝不允许崩溃。
- 文案 `clear_card_cache_fail`（“清除缓存失败”）在代码里**从未使用**。

### 5.6 清除 hook 点缓存

- 二次确认：标题 `settings_clear_hook_cache_dialog_title`、正文 `settings_clear_hook_cache_dialog_text`、`取消`/`确认`。
- 确认 → 按 OSType 组装 root 命令并执行：
  - OSType 0：
    - `am force-stop com.miui.tsmclient`
    - `rm -rf /data/data/com.miui.tsmclient/files/zhizi42.diycard.method.txt`
    - `rm -rf /data/data/com.miui.tsmclient/files/zhizi42.diycard.method.1.txt`
    - 若**默认 prefs** 的 `super_land==true`：
      `rm -rf /data/data/com.miui.tsmclient/files/zhizi42.diycard.HyperSuperLand.method.txt`
  - OSType -1：命令列表为空，仍会执行一次 `su --mount-master`（可能误报成功）。
- 成功 → Toast `clear_card_cache_succ`（复用“清除缓存成功”文案）；失败**无任何提示**。
- 注意：super_land 判断读的是默认 prefs（`<pkg>_preferences`），不是远程 `settings`。✅

### 5.7 自定义 hook 点子页（custom_hook_preferences.xml）

| key | 控件 | 默认 | 标题（硬编码） | 写入 |
|---|---|---|---|---|
| `class` | EditTextPreference | "" | `Class name` | 远程 settings |
| `method` | EditTextPreference | "" | `Method name` | 远程 settings |
| `method_arg` | EditTextPreference | "" | `Method arg class name` | 远程 settings |
| `load_url_from_arg` | Switch | false | `load url from arg` | 远程 settings |
| `replace_url_to_arg` | Switch | false | `replace url to arg` | 远程 settings |

- `dr.y == null` 时全部 `setPersistent(false)`。
- 作用：只要 `class` 和 `method` 都非空，Hook 就**完全跳过 DexKit/缓存定位**，直接 hook 用户指定的方法
  （`method_arg` 为空 = 无参方法；填 `String` = 单 String 参数；其他 = 用 ClassLoader 加载该参数类）。
- 两个 switch 语义见 §6.4。

### 5.8 dev_mode 解锁细节（LongClickPreference + j90，✅）

- 长按「关于」条目：
  - 距上次长按 > 5s 则计数清零；否则计数 +1。
  - 计数 == 3 时 Toast `"long click one times again to open/close dev mode"`（英文硬编码），return。
  - 计数 >= 4 时清零并切换 `dev_mode_open`（默认 prefs），立即刷新可见性：
    `dev_opt` 分组 = z；`show_all_cards` = `OSType==0 ? z : false`；`debug` = z；`custom_hook` = z。
- dev_mode 状态保存在默认 prefs，重开设置页仍然有效。

---

## 6. Hook 与替换逻辑（Hook.java）

### 6.1 入口与目标

  只处理 `com.miui.tsmclient` → `OSType=0`；其它包 → 记错误日志并 return。
- 只处理小米智能卡（scope.list 一致）。

### 6.2 初始化顺序（prepareHook）

1. `sharedPreferences = getRemotePreferences("settings")`；
   `gr.h = settings.debug`（日志开关）；`showAllCard = settings.show_all_cards`；`z = settings.super_land`。
2. Hook `Application.attach(Context)`：拿到 Context 后 `writeSharedPreference = new n60(context)`
   （即通过 `content://...preference/settings` 的代理 SharedPreferences，用于把新卡片 URL 写回）。
   失败只记日志。
3. 确保目标目录 `<target>/files/images` 存在（`mkdir()`）。
4. 若 `settings.class` 与 `settings.method` 都非空 → `prepareMethodToHook(class, method, method_arg, -1)`，**return**（不再 DexKit 定位）。
5. 否则按 OSType 定位并 hook：
   - OSType 0：先方法 0，再方法 1；`super_land` 时再方法 10。
   - OSType 2（不可达）：`com.meizu.mznfcpay.model.BaseCardItem#largeIconUrl/smallIconUrl`。

### 6.3 方法定位（缓存 + DexKit）

- 缓存文件（在目标卡包 `files/` 下，两行：类名、方法名）：
  `zhizi42.diycard.method.txt`（方法 0）、`zhizi42.diycard.method.1.txt`（方法 1）、
  `zhizi42.diycard.HyperSuperLand.method.txt`（方法 10）。
- 命中缓存 → 直接 `prepareMethodToHook(class, method, "String"/"CardInfo", num)`；
  缓存缺失/不完整 → DexKit 定位，成功后把类名/方法名写回缓存文件。
- DexKit：`new DexKitBridge(applicationInfo.sourceDir)`，用 `FindClass` + `FindMethod` 组合查询，
  要求**唯一命中**（`result.size()==1`）；否则打印所有候选并放弃（记错误日志）。
  查询的语义（Hook 侧的反编译 matcher 对象为 flatbuffers 的 `nz/bb/vb0`；精确条件另见 `docs/HOOK-SPEC.md` §3）：

  | 方法号 | 平台 | 定位条件（语义） | 参数类型 | hook 后 URL 来源 |
  |---|---|---|---|---|
  | 0 | MIUI | 包 `com.miui.tsmclient.util`，源文件 `CustomGlideUrl.java` 的类中，返回 String（从参数取 URL） | `String` | 参数 arg0 |
  | 1 | MIUI | 包 `com.bumptech.glide`，源文件 `RequestManager.java` 的类中，单 String 参数（把替换 URL 写回参数） | `String` | 参数 arg0 |
  | 10 | MIUI+super_land | 包 `com.miui.tsmclient.util`，返回 String、参数 `com.miui.tsmclient.entity.CardInfo` | `CardInfo` | 返回值 |

### 6.4 注入模式（Hook.MyHooker，✅ 语义已核对）

`startHook(method, num)` 先算两个默认开关，然后按 num 覆盖（自定义 hook 用 i=-1 时读用户配置）：

| 场景 | loadUrlFromArg（从参数读 URL） | replaceUrlToArg（把替换值写回参数） | 效果 |
|---|---|---|---|
| MIUI #0 | true | false | 读 arg0 → `replaceUrl` 非空则**直接 return 替换值**（跳过原方法） |
| MIUI #1 | true | true | 读 arg0 → 非空则 `chain.proceed(new Object[]{替换值})` |
| MIUI #10 | false | true | `chain.proceed()` 拿返回值 → 非空则 return 替换值 |
| 自定义 hook | 用户配置 | 用户配置 | 同上两种组合 |

拦截逻辑伪代码：

```java
if (loadUrlFromArg) {
    String r = replaceUrl((String) chain.getArg(0));
    if (r.isEmpty()) return chain.proceed();
    return replaceUrlToArg ? chain.proceed(new Object[]{r}) : r;
} else {
    String s = (String) chain.proceed();
    String r = replaceUrl(s);
    return r.isEmpty() ? s : r;
}
```

### 6.5 replaceUrl 规则（✅ 字节码已核对）

```java
String replaceUrl(String url) {
    updateCardUrlList(url);                       // 先收集卡片 URL（内部可能提前 return，不影响下面继续）
    if (settings.test_mode) return "https://home.zhizi42.top:555/test_card.png";   // 测试模式
    String v = settings.getString(url, "");
    if (v.isEmpty()) return "";                   // 不替换 → 保留原图
    if (v.startsWith("https://") || v.startsWith("http://")) return v;
    // 否则当作文件名：file://<target>/files/images/<v>
    String file = gr.v(OSType) + "files/images/" + v;
    String ret = "file://" + file;
    if (gr.h /*debug*/) { /* 只打日志说明文件是否存在 */ }
    return ret;                                    // 文件不存在也照原样返回
}
```

- `updateCardUrlList(url)`（收集首页列表数据，✅）：
  1. MIUI 且 `show_all_cards=false`：URL 含 `w270h480` 或 `/door-card-img/logo/` 或 `/Mibi/` → 视为小图，**不加入列表**；
  2. URL 在 `black_card_url_set` 中 → 不加入；
  3. `writeSharedPreference` 为 null（Application.attach 还没拿到 context）→ 只记日志，放弃；
  4. 已在 `all_card_url_set` → 不重复；否则加入并 `apply()` 写回。
- `test_mode=true` 时**所有**能加载的图片都被替换成固定测试图 URL（卡面列表也会被污染，原版接受此副作用）。
- `replace_url` 不检查目标文件是否存在（debug 日志除外）。

### 6.6 两个开关的最终语义（实现时要复刻或明确改进）

- `show_all_cards`（仅 dev_mode、仅 MIUI）：默认 **过滤**小图与 logo（w270h480 / door-card-img/logo / Mibi）；
  打开后**不过滤**，把所有 URL 都塞进列表。
- `test_mode`：默认关；打开后 hook 返回固定测试图（用于验证模块链路），不查映射。
- `debug`：打开后 hook 与 App 侧都输出详细日志（`gr.J` tag `"DIY NFC Card"`；App 侧 `gr.h` 从默认 prefs 读）。
- `super_land`（仅 MIUI）：额外 hook 超级岛取图方法（方法 10），并让清除 hook 缓存时多删一个缓存文件。

### 6.7 写回的 SharedPreferences 协议（RemotePreferences，重建时必须照抄）

`MyPreferenceProvider` 是标准的 LSPosed `RemotePreferencesProvider` 模板（写在模块 App 侧），hook 进程
通过 framework 的 `RemotePreferences` 访问它；模块 App 侧通过 libxposed 服务的 `transact(21)` 读取同一份数据。

| 项 | 值 |
|---|---|
| authority | `com.zhizi42.diymiuicard.preference` |
| exported | `true`（无权限保护；**任何 App 可读写，安全问题**） |
| 注册的 prefs 名 | 只有 `"settings"`（`ck[]`，数组长度 1；v2.4 没有 `card_urls` 这个独立文件） |
| URI 形态 | `content://<authority>/<prefs>` 或 `content://<authority>/<prefs>/<key>`（UriMatcher：`*/`=1、`*/*`=2） |
| query 列 | 默认 `{"key","type","value"}`，也可由调用方指定 |
| type 编码 | 0=null、1=String、2=StringSet、3=int、4=long、5=float、6=boolean |
| value 编码 | boolean→0/1；StringSet→`er.Y()`：元素按 `;` 拼接，`\`→`\\`、`;`→`\;`；读取用 `er.i()` 反转 |
| 写操作 | `insert`（ContentValues: key/type/value；URI 带 key 时两处 key 冲突要报错）、`update`（有 ContentValues 等同 insert，否则 delete）、`delete`（带 key 删单项，否则 clear）、`bulkInsert`（单 key URI 拒绝） |
| 变更通知 | `registerOnSharedPreferenceChangeListener` → `notifyChange(content://<authority>/<prefs>[/<key>])` |
| 写入方式 | 全部 `commit()` 同步提交 |

- 远程 `settings` 与模块 App 的 `getSharedPreferences("settings",0)` 是**同一文件**。
- hook 侧写入（`n60`）用于把 `all_card_url_set` 写回；写入时机必须在 `Application.attach` 之后。

---

## 7. 完整文案清单（本应用自有，可直接引用原文）

> 规则：`{...}` 表示占位；`（代码）` 表示不在 strings.xml、硬编码在 Java 里。
> 依赖库字符串（abc_/m3_/mtrl_/material_/ucrop_ 等）不在此表。

### 7.1 应用名 / 状态 / 通用

| 资源名 | 原文 |
|---|---|
| `app_name` | DIY NFC卡面图片 |
| `text_enable` | LSPosed已启用 |
| `text_disable` | 已禁用 |
| `title_activity_settings` | 设置 |
| `confirm` | 确认 |
| `cancel` | 取消 |
| `clear_card_cache_succ` | 清除缓存成功 |
| `clear_card_cache_fail` | 清除缓存失败（**代码未使用**） |

### 7.2 首页 / 列表 / 说明

| 资源名 | 原文 |
|---|---|
| `confirm_delete_msg` | 确认删除所有卡片和图片信息吗？ |
| `card_url_title` | 卡片网址 |
| `dialog_button_copy` | 复制 |
| `how_to_use_title` | 使用说明 |
| `no_card_app_installed_title` | 未找到适配的卡包APP |
| （代码） | 请添加卡包APP到作用域 |
| （代码） | 模块作用域没有卡包APP，请点击确定后，同意添加卡包APP到本模块作用域。 |
| （代码） | xposed service not exist |
| （代码） | image url（剪贴板 label） |

### 7.3 添加图片 / 裁剪 / 保存

| 资源名 | 原文 |
|---|---|
| `activity_title_add_image` | 添加图片 |
| `select_image_button_save` | 确定添加 |
| `select_image_button_start_diy` | 裁剪至标准大小并可选添加卡面标志 |
| `select_image_button_tips` | 相关说明 |
| `select_image_tips` | 点击确认添加会添加当前预览的图片样式到应用卡包图片库。\n目前一键添加卡面标志功能仅支持京津冀互联互通卡、上海公共交通卡 交通联合版、泉城通、银川交通一卡通。\n因作者技术和精力有限，不会提供所有交通卡卡面和其它卡卡面。\n如您有能力，可自行取出想要的卡面标志图片交给作者，作者会添加到应用。 |
| `save_filename_dialog_title` | 保存文件名 |
| `save_filename_dialog_edittext_hint` | 保存文件名（不包括后缀名，不建议使用非英文字符） |
| `save_toast_file_exists` | 要保存的文件名已存在，请更换名字 |
| `diy_card_toast_image_resolution_small` | 图片分辨率过小，最终图片效果可能不好 |
| `select_image_done_dialog_title` | 添加图片完成 |
| `select_image_done_dialog_text` | 添加图片完成！\n若卡包卡片卡面显示错误，可能是因为您输入了与之前相同的文件名，到设置清理缓存即可更新。 |
| `select_image_done_dialog_clear_cache` | 马上清除缓存 |
| `select_image_save_copy_error_toast` | 复制文件失败，请确定授予root权限后联系开发者 |
| （代码） | create new image file failed! |
| （代码） | 空（卡面标志列表首项，§3.4） |

### 7.4 图片输入对话框

| 资源名 | 原文 |
|---|---|
| `input_image_name_title` | 选择图片或输入url |
| `input_image_input_hint` | 图片URL |
| `input_image_button_clear` | 清空已选 |
| `input_image_button_tips_title` | 说明 |
| `input_image_tips_text` | 点击图片来选择，点击下面的添加图片按钮来添加图片（需要root权限），长按图片来删除图片，点击“清空已选”来还原原图 |
| `input_image_dialog_confirm_root_title` | 请授予root权限 |
| `input_image_dialog_confirm_root_msg` | 应用需要root权限才能添加图片（复制图片到应用私有目录），请授予root权限。 |
| （代码） | rechrd what（彩蛋） |

### 7.5 设置页

| 资源名 / 来源 | 原文 |
|---|---|
| `settings_title_settings` | 设置 |
| `settings_title_hide_icon` | 隐藏桌面图标 |
| `settings_summary_hide_icon` | 开启后，会隐藏桌面上本应用的图标 |
| `settings_hide_icon_dialog_title` | 确定隐藏图标吗？ |
| `settings_hide_icon_dialog_text` | 开启隐藏图标后，桌面上本应用的图标将会消失！\n此后只可通过lsposed内，长按本应用，选择模块设置，来打开本应用。\n您可随时关闭此选项来重新显示图标。 |
| （header_preferences 硬编码） | 包括小米超级岛卡面图片 |
| （同上 summary） | 开启后会包含小米超级岛显示的图片（仅HyperOS3最新版卡包可用） |
| `settings_title_blacklist` | 主界面卡面黑名单 |
| `settings_summary_blacklist` | 编辑不显示在主界面的卡面名单 |
| `settings_title_show_all_cards` | 显示所有卡片 |
| `settings_summary_show_all_cards_off` | 现在会过滤小卡片和logo |
| `settings_summary_show_all_cards_on` | 现在不过滤小卡片和logo |
| `settings_title_debug` | 输出log |
| `settings_summary_debug` | 仅供debug使用 |
| `settings_title_custom_hook` | 自定义hook点（仅供测试使用，不懂勿动） |
| （header_preferences 硬编码） | 测试模式 |
| （同上 summaryOff） | 开启后会尝试替换所有能加载的图片 |
| （同上 summaryOn） | 现在会尝试替换所有能加载的图片 |
| `settings_title_clear_card_cache` | 清除模块图片缓存 |
| `settings_clear_cache_dialog_title` | 是否清除模块图片缓存？ |
| `settings_clear_cache_dialog_text` | 是否确认清除自己的图片缓存？\n清除后可能会修正图片加载错误。\n清除后请重启本应用以生效。 |
| `settings_title_clear_hook_cache` | 清除hook点缓存 |
| `settings_clear_hook_cache_dialog_title` | 是否清除hook点缓存？ |
| `settings_clear_hook_cache_dialog_text` | 是否清除hook点缓存？\n清除后可能解决加载不到目标图片的问题。\n清除后会自动停止运行卡包。 |
| （header_preferences 硬编码） | developer option |
| （custom_hook_preferences） | Class name / Method name / Method arg class name / load url from arg / replace url to arg |
| `settings_title_about` | 关于 |
| `settings_title_FAQ` | 常见问题 |
| `settings_FAQ_msg` | 常见问题：\n模块主界面没有图片：请确认LSP里模块已启用并勾选作用域，强行停止卡包，在卡包里加载完图片\n替换了图片但不生效：如上若仍不生效请确认/data/data/卡包包名/files/images下存在选择的图片文件\n更改了替换图片但仍显示旧图片：设置里清除卡面图片缓存\n更新模块后：请强行停止卡包后再重启卡包和应用\n若确认模块是最新版，已重启卡包和应用仍有故障：请加群反馈 |
| `settings_title_join_qq` | 加入作者的QQ群 |
| `settings_open_qq_fail` | 打开QQ失败，请检查手机是否有安装最新版QQ |
| `settings_title_donate` | 给开发者买咖啡 |
| `donate_call_alipay_text` | 直接打开支付宝去捐赠 |
| `settings_title_no_money_plan` | 无钱计划 |
| `settings_no_money_dialog_text` | 本应用已加入 无钱计划，承诺一直免费使用且无收费附加功能。 |
| `settings_dialog_button_text_no_money_link` | 打开无钱计划网址 |
| `activity_title_black_list` | 主界面卡面黑名单 |
| （代码） | long click one times again to open/close dev mode |

### 7.6 常量 / 链接 / 路径（代码内）

| 项 | 值 |
|---|---|
| 测试图 URL | `https://home.zhizi42.top:555/test_card.png` |
| 无钱计划 | `https://github.com/NoMoneyPlan` |
| 支付宝 | `https://QR.ALIPAY.COM/FKX00236BXA07MOA2XU607` |
| QQ 加群深链 | 见 §5.2 |
| 本应用图片目录 | `/data/data/com.zhizi42.diymiuicard/files/images/` |
| 缓存临时图 | `cache/cache.png` |
| Result extra key | `"FILE_NAME"` |
| 日志 tag | `"DIY NFC Card"`（hook）、`"RootCommandOutput"` / `"RootCommandError"` / `"execute command info"` / `"execute error"` / `"BitmapCropTask"` |
| hook 缓存文件名 | `zhizi42.diycard.method.txt` / `zhizi42.diycard.method.1.txt` / `zhizi42.diycard.HyperSuperLand.method.txt` |

---

## 8. 边界情况与失败处理

| 场景 | 原版行为 | 问题/重制要求 |
|---|---|---|
| 未安装卡包 | `gr.e`=-1：首页服务回调/选图页弹 `no_card_app_installed_title/content`；设置页隐藏 `super_land`、`show_all_cards`；`clear_hook_cache` 仍可见但命令列表为空 | 保存仍会走到「cp 到 null」→ 报 root 复制失败（误导）。重制版应直接禁用「添加/保存」并明确提示 |
| 未 root | `gr.m` 捕获 IOException → 返回 false → 保存时 toast 复制失败并 finish；本地文件已写入，残留孤儿文件 | 重制版用 content:// 方案（见 HOOK-SPEC）避免 root；若保留 root 需先探测 su、失败回滚 |
| LSPosed 未启用 / 服务未连接 | 状态文字「已禁用」；设置页需远程的控件 `setPersistent(false)`；映射写入本地 + toast `xposed service not exist`；hook 不运行 | 重制版应在首页给出「去启用」引导，并区分「模块未激活」与「服务异常」 |
| 卡包未加入 scope | 服务回调检查 scope，弹「请添加卡包APP到作用域」，确认可请求添加 | 保留，文案与交互可优化 |
| 空列表 | 无任何占位/引导；删除按钮仍可用 | 重制版必须有空态 + 三步引导（UI-SPEC 已有） |
| 图片加载失败 | Glide 显示占位图（`ic_card`/`ic_image`），无错误文案 | 重制版显示「加载失败」+ 重试 |
| 选了图但取消 uCrop | `E/F` 保持选图原图，标志列表不出现；仍可直接保存原尺寸 | 逻辑可保留，但重制版应在编辑页明确「未裁剪」状态 |
| 保存时未点标志 | 保存原图（无圆角、非 960×606） | 重制版应默认圆角/成尺寸（见 §9） |
| 源图小于 960×606 | 不选标志时原尺寸保存；选标志时 toast「分辨率过小」+ 双线性放大 | 重制版统一走高质量流水线 |
| 文件名重复 | 拒绝保存并 toast | 保留；重制版可加「覆盖/自动重命名」 |
| 文件名含非法字符 | 确认按钮禁用 | 保留（重制版可自动清洗） |
| 文件名超长/磁盘满 | `createNewFile` 失败 → 英文 toast | 重制版用中文/资源化文案 |
| 目标目录不存在 | Hook 会 mkdir；若 hook 未跑过，cp 失败 → 报错 | 重制版自行确保目录或写入自身目录 |
| `first_add_image` 首次 root 提示 | 只提示一次 | 保留（但重制版无需 root 时应删除该提示） |
| 图片缓存清除 | **点确认必崩**（§5.5） | 必须修复（P0） |
| 删除全部 | 只清列表数据，映射/图片仍在，下次 hook 会重新加回 | 重制版要明确「清空记录 / 删除图片」两个语义 |
| 黑名单里再点开 | 点击禁用，只能长按复制/滑动移出 | 重制版可允许查看/直接恢复 |
| 长按我的图片删除 | 无确认直接删，且目标路径硬编码 MIUI；映射不清理 | 重制版需确认 + 清理引用 + 按 OS 寻址 |
| 同一文件被多张卡引用 | 删除/覆盖会一起坏掉 | 重制版用内容寻址/引用计数 |
| 32 位设备 | 无 libdexkit.so arm 32 位 → 模块不可用 | 重制版需声明支持范围或提供兜底定位 |
| 卡包版本变化 | DexKit 定位失败 → 日志报错，功能静默失效 | 重制版需要「hook 点状态」可见 + 自定义 hook 兜底 |

---

## 9. 图像处理 / 降采样专节（重点）

### 9.1 原版所有缩放/尺寸相关代码位置（✅）

| 位置 | 调用 | 参数 | 质量影响 |
|---|---|---|---|
| uCrop `BitmapCropTask`（混淆类 `y7.a()`） | `Bitmap.createScaledBitmap(bitmap, w, h, false)` | **filter=false（最近邻）** 把整张位图缩到 MaxSize | 缩小倍数大时严重锯齿/摩尔纹；这是原版画质差的首要原因之一 |
| uCrop `BitmapCropTask` | `Bitmap.createBitmap(src, x, y, w, h)` | 裁切（裁剪区已按 1:1 像素，不再缩放） | 裁切本身无损 |
| uCrop 旋转 | `Bitmap.createBitmap(src, 0,0,w,h, matrix, true)` | filter=true | 可接受 |
| `ye.onClick`（选标志） | `Bitmap.createScaledBitmap(E, 960, 606, true)` | **双线性**；只在尺寸 ≠960×606 时执行 | 小图被放大、大图被缩；比最近邻好但仍偏软 |
| `SelectImageActivity.x()` | 圆角处理（见 9.2） | 半径固定 **40.0f 像素** | 与 dp 无关；非 960 宽时圆角比例失真 |
| `ts.a`（选图回调） | `BitmapFactory.decodeStream(openInputStream(uri))` | **无 inSampleSize** | 12MP/48MP 图直接全尺寸解码 → 内存峰值高，可能 OOM |
| `SelectImageActivity.onActivityResult` | `BitmapFactory.decodeStream(...)` | 无采样 | 输出已被 MaxSize 限制，影响小 |
| 标志合成 | `Canvas.drawBitmap(logo960x606, 0,0, alpha=255)` | SRC_OVER | 正确 |
| 主列表/对话框缩略图 | Glide（默认采样到 View 尺寸） | — | 内存友好 |

### 9.2 圆角实现（`SelectImageActivity.x()`）✅

```java
Bitmap out = Bitmap.createBitmap(w, h, ARGB_8888);
Canvas c = new Canvas(out);
Paint p = new Paint(); p.setAntiAlias(true);
RectF r = new RectF(0,0,w,h);
c.drawRoundRect(r, 40f, 40f, p);                 // 先画一层圆角矩形（作为 alpha mask）
p.setShader(new BitmapShader(src, CLAMP, CLAMP)); // 再用原图填充
p.setXfermode(new PorterDuffXfermode(SRC_IN));
c.drawRoundRect(r, 40f, 40f, p);                 // 只在圆角矩形内保留原图
return out;
```

- 半径写死 `40.0f`（像素），不是 dp，也不随输出尺寸缩放。
- 只对「选了卡面标志」的路径执行（见 §3.5）；未选标志不圆角。
- 合成顺序：**先圆角，再叠加标志**（标志不受圆角裁剪，标志 PNG 自身边缘是透明的）。
- 重制版要求：抗锯齿覆盖度/超采样、半径按输出宽度比例（960→40），见 `docs/IMAGING-SPEC.md`。

### 9.3 重制版的硬性验收（来自任务 9 点 + IMAGING-SPEC）

1. 禁止使用 `Bitmap.createScaledBitmap` 做缩小（原版两处都是这个问题）。
2. 缩小必须 scale-aware 重采样（Lanczos3 或 box 预缩小 + Lanczos3），并给出前后对比图/指标。
3. 解码必须 `inSampleSize`/按需解码，不得全尺寸 decode。
4. 输出严格 960×606 PNG；圆角半径按比例、边缘干净。
5. 不选标志也要圆角/成尺寸（原版不一致）。
6. 大图内存峰值 < 200MB（12MP 输入）。

---

## 10. 原版已知问题 / 重制版必须修复（按优先级）

| 级别 | 问题 | 证据 | 重制建议 |
|---|---|---|---|
| **P0-1** | 设置页「清除模块图片缓存」→ 确认 → **必崩**（主线程抛 `IllegalArgumentException`） | ✅ 字节码（`lg0` default 分支 + `m1.o` throw） | 后台线程清缓存，主线程 toast；失败用 `clear_card_cache_fail` 文案 |
| **P0-2** | 「删除全部」文案说删全部，实际只删 `all_card_url_set`，映射/图片/黑名单都不动 | ✅ 字节码（`pw` case 0） | 明确区分「清空列表记录 / 删除图片文件 / 重置映射」并给确认 |
| **P0-3** | 画质：uCrop MaxSize 用 `createScaledBitmap(..., false)`（最近邻）降采样 | ✅ 字节码（`y7.a`） | 全链路高质量重采样（IMAGING-SPEC） |
| **P0-4** | 选图后全尺寸 `BitmapFactory.decodeStream`，大图 OOM | ✅ 字节码（`ts.a`） | `inSampleSize` + EXIF 旋转 + 缩略图预览 |
| **P0-5** | root 依赖：`su --mount-master` 同步跑在主线程；无 root 必然失败且残留孤儿文件 | ✅ 字节码（`gr.m`/`y80`） | 默认 content:// 免 root；root 仅兼容模式（HOOK-SPEC） |
| **P1-1** | 保存成功后不自动映射，必须再点缩略图，路径不直观 | ✅ 字节码（`y80` 只传 FILE_NAME） | 保存即映射当前卡片，一步完成 |
| **P1-2** | 不选标志时图像不圆角、尺寸不统一 | ✅ 字节码（`ye` index 0 分支） | 统一流水线，圆角/尺寸始终生效 |
| **P1-3** | 状态文字 34sp 过大；首页无空态/无引导；删除按钮无上下文 | 📄 layout | Miuix 大标题 + 状态卡 + 空态引导（UI-SPEC） |
| **P1-4** | 长按删图无确认、目标路径硬编码 MIUI、不清理映射 | ✅ 字节码（`k00`） | 确认弹层 + 按 OS 寻址 + 引用清理/引用计数 |
| **P1-5** | 首页/黑名单滑动无确认无撤销；误滑即改数据 | 📄 `d1` | 支持撤销 Snackbar |
| **P1-6** | 文案大量硬编码（中英混杂，且不在 strings.xml，无法本地化/会漂移） | 📄 源码 | 全部收进资源文件/字符串表 |
| **P1-7** | RemotePreferences provider `exported=true` 无权限，任意 App 可读写模块设置 | 📄 Manifest | 加 signature 级权限或改用应用内导出/受限协议 |
| **P1-8** | 图片 `chmod 666`（世界可读），目标目录里任何 App 可读 | ✅ 字节码（`y80`） | 免 root 方案天然规避；root 模式用 600/644 + 专用目录 |
| **P1-9** | Hook 静默失败（DexKit 定位失败只记日志，UI 不知道） | 📄 Hook | 首页显示 hook 状态/最近生效时间；失败可跳自定义 hook 兜底 |
| **P1-10** | `test_mode` 会污染真实卡面列表（所有 URL 都进列表） | 📄 Hook | 测试模式只影响替换，不影响列表收集 |
| **P1-11** | 配置存三处（settings / default prefs / 远程 settings），易不一致 | 📄 §1.5 | 单一数据源 + 类型安全存储（DataStore） |
| **P2-1** | 首次 root 提示弹窗、英文 toast、`rechrd what` 彩蛋、英文硬编码 | 📄 | 统一中文文案；彩蛋可保留但需注释 |
| **P2-2** | Meizu 路径少 `/`、`clear_hook_cache` 可见条件 `!=2` 等死代码/残留 | ✅ | 删除不可达分支 |
| **P2-3** | 主题名为 DayNight 实际固定浅色；自定义状态栏/导航栏颜色 hack | 📄 theme/e j | 用 Miuix ThemeController 跟随系统 |
| **P2-4** | 仅 arm64 的 dexkit 原生库；32 位设备不可用 | 📄 lib/ | 明确支持范围或提供反射兜底 |
| **P2-5** | 关于页版本号写死 `"2.4"`（格式化参数），未用 BuildConfig | 📄 | 从 BuildConfig/版本 API 读取 |
| **P2-6** | 图片名固定追加 `.png`，用户输入 `x.png` → `x.png.png` | ✅ | 自动去重后缀 |

---

## 11. 验收清单（重制版逐条打勾）

> 用法：每条给「对齐 / 改进 / 不做」三选一；改进的必须写理由并链接到 §10。
> 共 **108** 条。

### A. 结构 / 启动（A01–A12）

- [ ] A01 包名/版本/图标/应用名与主题可正常安装启动
- [ ] A02 存在桌面入口（可被隐藏）
- [ ] A03 从 LSPosed「模块设置」可打开主界面（隐藏图标后仍可用）
- [ ] A04 Manifest 声明两个卡包包名的 `<queries>`
- [ ] A05 Xposed `module.prop`（api 101、staticScope）与 scope 列表正确
- [ ] A06 `java_init.list` 指向模块入口类
- [ ] A08 原生库按设备架构可用（或提供非 DexKit 兜底）
- [ ] A09 权限最小化（不申请存储/root 权限）
- [ ] A10 应用数据目录结构（图库/缓存/映射）明确且有迁移策略
- [ ] A11 服务未连接时全 App 不崩溃、有可理解的降级
- [ ] A12 深浅色/大字号下界面可用（原版 34sp 问题修复）

### B. 首页（B01–B16）

- [ ] B01 列表数据来自卡面 URL 集合（跨进程可刷新）
- [ ] B02 排序：已替换在前，同组 URL 字典序
- [ ] B03 未替换 item：显示原图
- [ ] B04 已替换 item：原图 → 箭头 → 替换图
- [ ] B05 替换图支持本地文件与 http(s) URL 两种来源
- [ ] B06 图片加载失败有可见错误态
- [ ] B07 点击卡片打开编辑/选择图片
- [ ] B08 长按显示卡片 URL + 复制
- [ ] B09 左滑加入黑名单（有确认或撤销）
- [ ] B10 下拉刷新
- [ ] B11 首次启动显示使用说明（只一次）
- [ ] B12 状态区正确显示「已启用/未启用」及原因
- [ ] B13 卡包未安装有明确提示
- [ ] B14 卡包未在 scope 有引导（可一键请求）
- [ ] B15 空列表有空态 + 引导
- [ ] B16 「删除/清空」语义明确且与文案一致

### C. 选图 / 裁剪 / 合成（C01–C16）

- [ ] C01 从相册选图（SAF/Photo Picker），取消不崩溃
- [ ] C02 裁剪比例锁定 160:101
- [ ] C03 MaxSize 960×606（不放大、大图高质量缩小）
- [ ] C04 输出 PNG 无损
- [ ] C05 裁剪 UI 主题色与 App 一致
- [ ] C06 EXIF 方向处理
- [ ] C07 大图解码内存受控（inSampleSize）
- [ ] C08 4 个内置卡面标志可选（京津冀/上海/泉城通/银川）
- [ ] C09 标志 PNG 960×606、透明叠加在正确位置
- [ ] C10 圆角半径按比例（960 宽 ≈ 40px）、边缘抗锯齿
- [ ] C11 不选标志也圆角并统一 960×606
- [ ] C12 预览响应及时、可返回重选
- [ ] C13 文件名对话框：非空 + 非法字符校验、自动补 `.png`、重名处理
- [ ] C14 保存成功有明确反馈与下一步（自动映射/完成）
- [ ] C15 保存失败有可读原因且不残留脏数据
- [ ] C16 保存过程不阻塞/不 ANR

### D. 图片输入 / 映射（D01–D12）

- [ ] D01 对话框展示「我的图片」列表
- [ ] D02 点缩略图建立映射
- [ ] D03 长按删除图片（确认 + 按 OS 寻址 + 清理引用）
- [ ] D04 URL 输入框可填远程 URL
- [ ] D05 空值/「清空已选」恢复原图
- [ ] D06 说明按钮展示完整提示
- [ ] D07 添加图片入口
- [ ] D08 首次使用有权限/流程提示（若仍需 root）
- [ ] D09 URL 与文件名两种映射语义正确
- [ ] D10 映射变更后列表即时刷新
- [ ] D11 映射数据跨进程（hook 可读）
- [ ] D12 无悬空映射（文件删除后自动清理）

### E. 设置页（E01–E20）

- [ ] E01 隐藏桌面图标：确认后才生效，可反向恢复
- [ ] E02 隐藏后可从 LSPosed 重新打开
- [ ] E03 超级岛开关仅 MIUI 可见
- [ ] E04 黑名单入口
- [ ] E05 使用说明入口
- [ ] E06 常见问题入口
- [ ] E07 测试模式开关，语义与 Hook 一致
- [ ] E08 清除图片缓存：**不崩溃**、有成功/失败反馈
- [ ] E09 清除 hook 缓存：强杀卡包 + 删缓存文件，按 OS 正确寻址
- [ ] E10 dev 选项默认隐藏
- [ ] E11 关于页长按 4 次（5s 窗口）解锁 dev 模式，第 3 次有提示
- [ ] E12 显示所有卡片仅 dev + MIUI
- [ ] E13 debug 日志开关
- [ ] E14 自定义 hook 页 5 个字段 + 持久化
- [ ] E15 无 LSPosed 服务时设置项有明确禁用态（不是静默不保存）
- [ ] E16 关于对话框版本号来自构建信息
- [ ] E17 QQ 群入口（失败提示）
- [ ] E18 捐赠页（支付宝二维码 + 打开支付宝）
- [ ] E19 无钱计划对话框 + 链接
- [ ] E20 设置项默认值与数据源单一、可备份/迁移

### F. Hook / 替换（F01–F16）

- [ ] F02 读取远程 prefs（authority/prefs 名/类型/编码）兼容原协议
- [ ] F03 捕获 Application context 后能写回 URL 集合
- [ ] F04 目标方法定位：先缓存、再 DexKit，唯一命中
- [ ] F05 缓存文件写入/读取兼容原格式（或提供迁移）
- [ ] F06 参数取 URL / 返回值取 URL / 写回参数三种模式
- [ ] F07 URL 过滤（w270h480 / door-card-img/logo / Mibi）
- [ ] F08 黑名单 URL 不加入列表
- [ ] F09 去重置回 all_card_url_set
- [ ] F10 replaceUrl：空值保留原图
- [ ] F11 replaceUrl：http(s) 直接返回；文件名转本地资源
- [ ] F12 截图/文件不存在时不崩、有日志
- [ ] F13 自定义 hook 点可覆盖默认定位
- [ ] F14 test_mode 语义明确且不污染列表
- [ ] F15 生效状态对用户可见（最近生效时间/hook 点状态）
- [ ] F16 目标 App 版本变化时优雅降级

### G. 数据 / 文案 / 安全（G01–G12）

- [ ] G01 所有用户可见文案资源化（中文）
- [ ] G02 文案与行为一致（删除全部/清缓存等）
- [ ] G03 不出现英文 toast / 调试文案
- [ ] G04 配置单一数据源，键名与原版映射清楚
- [ ] G05 图库文件命名安全（路径穿越/非法字符/长度）
- [ ] G06 保存/删除失败不产生孤儿文件
- [ ] G07 跨进程 provider 暴露面收敛（权限/只读）
- [ ] G08 图片文件权限最小化
- [ ] G09 无 root 默认路径可用
- [ ] G10 root 操作有探测、超时、回滚
- [ ] G11 卸载后目标卡包目录不留垃圾（或提供清理）
- [ ] G12 备份/恢复/迁移说明清楚

### H. 质量与证据（H01–H04）

- [ ] H01 图像质量对比图/指标（原版最近邻 vs 新版）入库
- [ ] H02 关键流程有自动化测试（排序/映射/圆角/尺寸/协议解析）
- [ ] H03 真机验证记录（SukiSU root 环境 / 目标卡包版本）
- [ ] H04 与本文档清单逐条对账，偏差记录在 §10

---

*本文档由逆向分析整理；反编译产物路径见文首。所有 ✅ 条目均对照过 dex 字节码。*
