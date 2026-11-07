# Hook / 核心功能规格（复现原版 DIY NFC卡面 v2.4）

原始 APK 已反编译在 `/root/workspace/apk/src`（jadx，`com/zhizi42/diymiuicard/*.java` 和
`defpackage/*.java`），资源在 `/root/workspace/apk/res/resources/`。
**目标：功能对齐，但用更现代、更可靠的实现。**

## 1. 模块形式

- Xposed 模块（新的 libxposed API 101，`io.github.libxposed:api:101.0.1`，compileOnly）。
- `app/src/main/assets/META-INF/xposed/`：
  - `module.prop`: `minApiVersion=101` `targetApiVersion=101` `staticScope=true`
  - `java_init.list`: `com.dsh.miuix.cardface.xposed.HookEntry`
- 入口 `class HookEntry : XposedModule()`，在 `onPackageReady` 里按包名分派：
  只处理 `com.miui.tsmclient`；其他包直接返回。

## 2. 设置读取 / 回写（RemotePreferences）

原版用 LSPosed 的 `RemotePreferences`：
- hook 进程：`getRemotePreferences("settings")` 读设置、`getRemotePreferences("card_urls")`
  读写卡面 URL 集合。
- 模块 App 内实现一个 ContentProvider，authority = `com.dsh.miuix.cardface.preference`
  （必须是 `<modulePackage>.preference`），协议参照反编译的
  `com/zhizi42/diymiuicard/MyPreferenceProvider.java`（LSPosed RemotePreferencesProvider 模板：
  注册 `settings`、`card_urls` 两个 prefs 名；query 返回 key/type/value 三列，
  type: 0=null 1=String 2=StringSet 3=int 4=long 5=float 6=boolean；
  支持 insert/delete/update 写回并 notifyChange）。
- 只在 `android:exported="true"` 下工作（读不需要权限）。

## 3. Hook 点定位

原版用 DexKit（`org.luckypray:dexkit:2.3.0`）+ 缓存文件，在目标进程内定位混淆后的方法：
- 缓存文件放在目标 App 的 `files/` 下，两行：类名、方法名（原版
  `zhizi42.diycard.method.txt` / `.method.1.txt` / `.HyperSuperLand.method.txt`）。
- 我们的缓存文件名：`cardface.method.0.txt` / `.1.txt` / `.island.txt`。
- DexKit 查询（原版 decompile 语义）：
  - MIUI #0：包 `com.miui.tsmclient.util`，源文件 `CustomGlideUrl.java` 的类中，
    返回 String、无参（或单 String 参数）的方法。
  - MIUI #1：包 `com.bumptech.glide`，源文件 `RequestManager.java` 的类中，
    返回 void、单 String 参数的方法（hook 时替换参数）。
  - MIUI #10（超级岛，仅 `super_land` 开）：包 `com.miui.tsmclient.util` 中，
    返回 String、参数为 `com.miui.tsmclient.entity.CardInfo` 的方法。
    返回 void、单 String 参数的方法。
- **兜底**（比原版更稳）：两条路径都实现，按优先级尝试：
  1. 用户配置的自定义 hook 点（设置项 class/method/method_arg + 两个开关）；
  2. DexKit 定位（缓存命中先读文件）；
  3. 反射直接找已知候选（`com.bumptech.glide.RequestManager.load(String)` 等），
     找不到就写日志、不崩溃。
- hook 行为（与原版一致）：
  - 从参数读 URL 模式：取 arg0（String）→ `replaceUrl(arg0)`；替换结果为空 → `chain.proceed()`；
    否则若「把替换写回参数」→ `chain.proceed(newArgs)`，否则直接 `return 替换值`。
  - 从返回值读 URL 模式：`val s = chain.proceed() as String` → 替换非空则返回替换值。
- 拦截到 URL 后：`replaceUrl(url)` 逻辑
  1. `updateCardUrlList(url)`：MIUI 且 `show_all_cards=false` 时过滤
     `w270h480` / `/door-card-img/logo/` / `/Mibi/` 小图；黑名单跳过；
     新 URL 加入 `card_urls/all_card_url_set`（RemotePreferences 写回）。
  2. 测试模式：返回一个固定测试图 URL（用我们自己的 content:// 或内置资源）。
  3. 在 `settings` 里查 `url -> 替换值`：空 → 返回空（保留原图）。
  4. 替换值以 http(s) 开头 → 原样返回。
  5. 否则是图库文件名 → 返回 `content://com.dsh.miuix.cardface.images/images/<name>`
     （不需要 root！）。
  6. 兼容模式开关打开时，改为把文件复制到卡包 `files/images/` 并返回 `file://` 路径（需 root）。
  7. 顺便把 `last_hook_at`（毫秒）写回 `settings`，让 App 显示「已生效」。

## 4. Root 兼容工具（仅在需要时）

用 `su -c`（ProcessBuilder）：
- 复制图片：`cp <src> /data/data/<wallet>/files/images/<name>` + `chmod 644`。
- 强制停止卡包：`am force-stop <wallet>`。
- 清除 hook 缓存：删除目标 App `files/cardface.method*.txt`。
- 清图片缓存：清本应用 cache + 图库缩略图缓存。
- 需要处理：无 root（`su` 不存在/被拒）、超时（5s）、返回码。
- 本机是 SukiSU root 环境，但 App 内获取 root 需要用户点确认 —— 所有流程都要有「不需要 root」
  的默认路径，root 只作兜底。

## 5. App 侧配套

- `core/AppPrefs.kt`：已实现设置/映射/黑名单读写（键名与原版一致）。
- 新增 `core/HookPrefsProvider.kt`（RemotePreferences 协议）。
- 新增 `core/RootShell.kt`、`core/PackageOps.kt`（打开卡包/LSPosed、隐藏图标、kill）。
- `androidx`/manifest 已由 Lead 配好；新增 provider 需要在 AndroidManifest 注册
  （和 Lead 说一声，或自己加并在任务里注明）。

## 6. 验收

1. `:app:assembleRelease` 通过，APK 有 `META-INF/xposed/*`，LSPosed 能识别模块并勾选作用域。
2. 目标进程日志（logcat / LSPosed 日志）出现：`cardface: hooked <class>#<method>`。
3. 模块 App 能读到卡包上报的 URL；替换映射后 hook 返回 content:// 地址。
4. 没有卡包/没有 root 时不崩溃、有清晰错误提示。
5. 提供一个可控的本地验证办法（见下）：
   - 自己写一个最小宿主 APK（`testhost/`，包名 `com.dsh.cardface.testhost`），
     Activity 里用 Glide 加载 `https://example.com/a.png` 并显示最终 URL；
     把 testhost 加进 LSPosed 作用域，用自定义 hook 点验证 `replaceUrl` 全链路。
     （本机没装小米卡包，真机 E2E 只能做到这一步；把结论写进报告。）
