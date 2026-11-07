package com.dsh.miuix.cardface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.dsh.miuix.cardface.core.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.extended.Refresh
import com.dsh.miuix.cardface.core.CardFaceItem
import com.dsh.miuix.cardface.core.WalletApps
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---------------- 使用说明 ----------------

@Composable
fun GuideScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = model.prefs
    val status = remember(prefs.revision) { runCatching { WalletApps.status(context, prefs) }.getOrNull() }
    val faceCount = remember(prefs.revision) { runCatching { prefs.cardFaces().size }.getOrDefault(0) }
    var showManagerHelp by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "header") {
            BackHeader(title = "使用说明", onBack = onBack)
        }
        item(key = "intro") {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "三步就能用起来",
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "替换图会先复制到卡包私有目录，再以 file:// 交给卡包读取；复制步骤需要 root，没有 root 时会尝试 content:// 兜底。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
        item(key = "steps") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                insideMargin = PaddingValues(20.dp),
            ) {
                NumberedStep(
                    index = 1,
                    title = "在 LSPosed 里启用模块",
                    description = "勾选作用域：小米智能卡。改完建议重启一次手机。",
                    done = status?.lsposedInstalled == true && status.moduleActivated,
                    actionText = "打开 LSPosed",
                    onAction = {
                        if (!DeviceBridge.openLsposed(context)) {
                            showManagerHelp = true
                        }
                    },
                )
                NumberedStep(
                    index = 2,
                    title = "打开卡包，把卡面浏览一遍",
                    description = "卡包加载过的卡面才会被记录下来，稍后就能在这里替换。",
                    done = status?.walletInstalled == true && (status?.lastHookAt ?: 0L) > 0L,
                    actionText = "打开卡包",
                    onAction = {
                        if (!DeviceBridge.openWallet(context, status?.walletKind)) {
                            scope.launch { model.notify("没有找到卡包应用") }
                        }
                    },
                )
                NumberedStep(
                    index = 3,
                    title = "回到卡面工坊，选一张卡面换图",
                    description = "点卡面 → 从相册选 → 裁剪 → 保存并应用，重启卡包后生效。",
                    done = faceCount > 0,
                )
            }
        }
        item(key = "finish") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                PrimaryButton(
                    text = "开始使用",
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "本应用不收集任何数据；替换图只存在本机。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    InfoDialog(
        show = showManagerHelp,
        title = "本机 LSPosed 是隐藏/寄生管理器",
        message = "这种模式没有桌面图标，也没有能被第三方 App 打开的入口。\n\n" +
            "打开方式：\n" +
            "1. 下拉通知栏；\n" +
            "2. 点「Android 系统 · Xposed 模块已更新」或「LSPosed」通知；\n" +
            "3. 在模块列表里启用「卡面工坊」，勾选作用域（小米智能卡）；\n" +
            "4. 强行停止卡包，再打开卡包把卡面浏览一遍。",
        onDismiss = { showManagerHelp = false },
    )
}

// ---------------- 常见问题 ----------------

private val FAQ_ITEMS = listOf(
    "需要 root 吗？" to
        "需要。替换图要复制进卡包私有目录（file:// 路径），复制这一步通过 su 完成，通常授权一次即可。没有 root 时会退回 content://，但部分卡包版本不认，替换可能不生效。",
    "改完卡面没有变化？" to
        "替换是在卡包进程里生效的，保存后要把卡包从后台划掉再重新打开（或等系统杀掉进程）。设置页的「清除 hook 点缓存」也会顺带重启卡包。",
    "识别不到任何卡面？" to
        "先在 LSPosed 里确认「卡面工坊」已启用、作用域已勾选小米智能卡，然后打开卡包把卡面浏览一遍，再回到首页下拉刷新。",
    "LSPosed 作用域是什么？" to
        "作用域决定模块要注入哪些应用。本模块只作用于小米智能卡，勾选它就够了，不要全选。",
    "为什么有些小图没被识别？" to
        "默认会过滤掉卡包里的小图标（w270h480、logo、Mibi 之类），避免列表里塞满无关图片。想全部显示可以打开设置里的「显示全部卡面」。",
    "黑名单有什么用？" to
        "加入黑名单的卡面不会出现在首页，也不会被替换。适合屏蔽调试时出现的测试图或不想要的卡面，随时可以在设置 → 卡面黑名单里移出。",
    "图片会被上传吗？" to
        "不会。所有图片都在本机处理（裁剪和缩放都在手机上完成），应用没有上传逻辑，也不收集任何使用数据。",
    "支持哪些卡包？" to
        "目前支持小米智能卡（含小米超级岛卡面）。其他卡包暂不支持。",
)

@Composable
fun FaqScreen(innerPadding: PaddingValues, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            BackHeader(title = "常见问题", onBack = onBack)
        }
        items(count = FAQ_ITEMS.size, key = { "faq_$it" }) { index ->
            val (question, answer) = FAQ_ITEMS[index]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                insideMargin = PaddingValues(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MiuixIcons.Help,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = question,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = answer,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ---------------- 关于与捐赠 ----------------

@Composable
fun AboutScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenGuide: () -> Unit = {},
    onOpenFaq: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = model.prefs
    val version = remember { DeviceBridge.versionName(context) }
    var checking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var autoCheckUpdate by remember { mutableStateOf(prefs.autoCheckUpdate) }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { model.notify("没有可打开链接的应用") }
    }

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            val latest = withContext(Dispatchers.IO) { Updates.fetchLatest() }
            checking = false
            if (latest == null) {
                info = "检查更新" to "检查失败，请检查网络后重试。"
            } else if (latest.versionName.isNotBlank() && latest.versionName != version) {
                info = "发现新版本 v${latest.versionName}" to
                    "当前版本：v$version\n最新版本：v${latest.versionName}\n\n" +
                    (latest.body.ifBlank { "暂无更新说明" }) +
                    "\n\n项目地址：${latest.htmlUrl}"
            } else {
                info = "已是最新版本" to "当前 v$version 已经是最新版本。"
            }
        }
    }

    fun showAnnouncement() {
        scope.launch {
            val latest = withContext(Dispatchers.IO) { Updates.fetchLatest() }
            info = if (latest == null) {
                "更新公告" to "获取失败，请检查网络后重试。"
            } else {
                "更新公告 · v${latest.versionName}" to latest.body.ifBlank { "暂无更新说明" }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 28.dp,
        ),
    ) {
        item(key = "header") { BackHeader(title = "关于", onBack = onBack) }

        // 应用信息
        item(key = "app") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleIcon(
                        imageVector = MiuixIcons.BankCards,
                        containerColor = MiuixTheme.colorScheme.primaryContainer,
                        contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
                        size = 60.dp,
                        iconSize = 32.dp,
                    )
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(
                            text = "卡面工坊",
                            style = MiuixTheme.textStyles.headline2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "v$version · Miuix 重制版",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "把小米智能卡里千篇一律的卡面换成你喜欢的图片。\n" +
                        "基于原版「DIY NFC卡面图片」v2.4 重写：界面换成 Miuix（HyperOS 风格），" +
                        "图片缩放换成 Lanczos3（不再有锯齿），替换图通过 file:// 直接交给卡包加载。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTag("LSPosed 模块")
                    MiniTag("Kotlin + Compose")
                    MiniTag("Miuix 0.9.4")
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "重构者：DeepSeek（AI 编程助手）",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }

        // 项目：地址 / 检查更新 / 更新公告 / 自动检查开关
        item(key = "project") {
            Column {
                SmallTitle(text = "项目")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(vertical = 4.dp),
                ) {
                    ArrowPreference(
                        title = "项目地址",
                        summary = "github.com/MiamBao/cardface-miuix",
                        onClick = { openUrl("https://github.com/MiamBao/cardface-miuix") },
                    )
                    HorizontalDivider()
                    ArrowPreference(
                        title = "检查更新",
                        summary = if (checking) "正在检查…" else "当前版本 v$version",
                        onClick = { checkUpdate() },
                    )
                    HorizontalDivider()
                    ArrowPreference(
                        title = "更新公告",
                        summary = "查看最近版本的更新内容",
                        onClick = { showAnnouncement() },
                    )
                    HorizontalDivider()
                    SwitchPreference(
                        title = "自动检查更新",
                        summary = "启动时检查 GitHub 最新版本",
                        checked = autoCheckUpdate,
                        onCheckedChange = {
                            autoCheckUpdate = it
                            prefs.autoCheckUpdate = it
                        },
                    )
                }
            }
        }

        // 功能
        item(key = "features") {
            Column {
                SmallTitle(text = "功能")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(20.dp),
                ) {
                    AboutLine("高质量缩放", "缩放感知 Lanczos3 + 线性光，960×606 输出，细线/文字不再锯齿。")
                    AboutLine("替换方式", "替换图复制到卡包私有目录后以 file:// 加载（复制步骤需要 root）。")
                    AboutLine("小米智能卡", "在 Xiaomi 15 Pro / HyperOS（LSPosed 2.2.1）上真机验证通过。")
                    AboutLine("外观可调", "澎湃蓝 / 动态取色 / 8 个预设色 / 深色模式 / 过渡动画。")
                    AboutLine("演示模式", "没装卡包也能完整体验选图、裁剪、卡面标志流程。")
                }
            }
        }

        // 开源与引用
        item(key = "oss") {
            Column {
                SmallTitle(text = "开源与引用")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(20.dp),
                ) {
                    AboutLine("本应用", "卡面工坊 · Miuix 重制版，以 GPL-3.0 许可证开源发布。")
                    AboutLine(
                        "原版项目",
                        "「DIY NFC卡面图片」v2.4，作者 zhizi42，GPL-3.0；" +
                            "github.com/Xposed-Modules-Repo/com.zhizi42.diymiuicard",
                    )
                    AboutLine(
                        "Miuix 0.9.4",
                        "HyperOS 风格 Compose 组件库，作者 Yukonga，Apache-2.0；" +
                            "github.com/miuix-kotlin-multiplatform/miuix",
                    )
                    AboutLine(
                        "LSPosed / libxposed API 101",
                        "Xposed 模块框架，GPL-3.0（LSPosed）/ Apache-2.0（libxposed-api）；" +
                            "github.com/LSPosed/LSPosed · github.com/LibXposed/libxposed-api",
                    )
                    AboutLine(
                        "DexKit 2.3.0",
                        "运行时 Dex 方法定位，作者 LuckyPray，Apache-2.0；" +
                            "github.com/LuckyPray/DexKit",
                    )
                    AboutLine(
                        "AndroidX / Compose / Coil 3",
                        "Jetpack Compose、Activity Compose、Coil 3 图片加载，Apache-2.0。",
                    )
                    AboutLine(
                        "重构者",
                        "DeepSeek（AI 编程助手）：负责 Miuix 界面重构、卡面 hook 链路、" +
                            "图像管线与性能优化。",
                    )
                }
            }
        }

        // 帮助与反馈
        item(key = "help") {
            Column {
                SmallTitle(text = "帮助与反馈")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    ArrowPreference(title = "使用说明", summary = "从启用模块到换卡面的完整步骤", onClick = onOpenGuide)
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MiuixTheme.colorScheme.dividerLine,
                    )
                    ArrowPreference(title = "常见问题", summary = "不生效、卡面不刷新、权限问题", onClick = onOpenFaq)
                }
            }
        }

        // 兼容性与免责声明
        item(key = "compat") {
            Column {
                SmallTitle(text = "兼容性与免责声明")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(20.dp),
                ) {
                    AboutLine(
                        "小米智能卡",
                        "在 Xiaomi 15 Pro / HyperOS + LSPosed 2.2.1 环境验证通过；" +
                            "其他机型、系统版本或卡包版本可能存在差异。",
                    )
                    AboutLine(
                        "其他风险",
                        "本应用只修改卡包展示图，不涉及支付、余额与刷卡逻辑；" +
                            "因使用本应用产生的任何问题（包括但不限于卡包异常、账号风险）由使用者自行承担。",
                    )
                    AboutLine(
                        "使用限制",
                        "请勿将本应用用于伪造证件、支付欺诈等任何违法用途。",
                    )
                }
            }
        }

        // 隐私
        item(key = "privacy") {
            Column {
                SmallTitle(text = "隐私")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(20.dp),
                ) {
                    AboutLine("不收集数据", "没有统计、没有广告、不请求账号信息；网络只用于加载卡面原始图片地址。")
                    AboutLine("只在本机处理", "裁剪 / 缩放 / 合成全部在手机本地完成，替换图存在应用私有目录。")
                }
            }
        }

        // 支持作者（不再放 QQ 群）
        item(key = "support") {
            Column {
                SmallTitle(text = "支持作者")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(20.dp),
                ) {
                    Text(
                        text = "项目完全免费、无付费功能、无广告。用得顺手的话，把「卡面工坊」推荐给需要的人就是最好的支持。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item(key = "footer") {
            Text(
                text = "卡面工坊 · GPL-3.0 · 2026\nMade with Kotlin / Compose / Miuix",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 8.dp),
            )
        }
    }

    InfoDialog(
        show = info != null,
        title = info?.first ?: "",
        message = info?.second ?: "",
        onDismiss = { info = null },
    )
}

/** 关于页的小标签（圆角浅色胶囊）。 */
@Composable
private fun MiniTag(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun AboutLine(title: String, content: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = content,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------------- 卡面黑名单 ----------------

@Composable
fun BlacklistScreen(model: AppModel, innerPadding: PaddingValues, onBack: () -> Unit) {
    val prefs = model.prefs
    val scope = rememberCoroutineScope()
    val revision = prefs.revision
    val items = remember(revision) { runCatching { prefs.blacklistedCardFaces() }.getOrDefault(emptyList<CardFaceItem>()) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { BackHeader(title = "卡面黑名单", onBack = onBack) }

        if (items.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    icon = MiuixIcons.Blocklist,
                    title = "黑名单是空的",
                    description = "在首页长按一张卡面，选「加入黑名单」，它就不会再出现在首页里。",
                )
            }
        } else {
            items(count = items.size, key = { items[it].url }) { index ->
                val item = items[index]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.url,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        TextButton(
                            text = "移出",
                            onClick = {
                                prefs.removeBlacklisted(item.url)
                                scope.launch { model.notify("已移出黑名单") }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 自定义 hook 点（高级） ----------------

@Composable
fun DevHookScreen(model: AppModel, innerPadding: PaddingValues, onBack: () -> Unit) {
    val prefs = model.prefs
    val scope = rememberCoroutineScope()
    var className by remember { mutableStateOf(prefs.hookClass) }
    var methodName by remember { mutableStateOf(prefs.hookMethod) }
    var methodArg by remember { mutableStateOf(prefs.hookMethodArg) }
    var loadFromArg by remember { mutableStateOf(prefs.hookLoadUrlFromArg) }
    var replaceToArg by remember { mutableStateOf(prefs.hookReplaceUrlToArg) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "header") { BackHeader(title = "自定义 hook 点", onBack = onBack) }

        item(key = "intro") {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "自动定位失败时，手动填卡包里加载卡面图片的那个方法。改完需要重启卡包。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }

        item(key = "fields") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                TextField(
                    value = className,
                    onValueChange = { className = it },
                    label = "类名（完整包名）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = methodName,
                    onValueChange = { methodName = it },
                    label = "方法名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = methodArg,
                    onValueChange = { methodArg = it },
                    label = "参数类名（可留空）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "switches") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                SwitchPreference(
                    title = "从参数读取卡面地址",
                    summary = "参数里是原图 URL 时打开",
                    checked = loadFromArg,
                    onCheckedChange = { loadFromArg = it },
                )
                RowDivider()
                SwitchPreference(
                    title = "把替换结果写回参数",
                    summary = "方法本身返回空、靠参数传地址时打开",
                    checked = replaceToArg,
                    onCheckedChange = { replaceToArg = it },
                )
            }
        }

        item(key = "save") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                PrimaryButton(
                    text = "保存 hook 点",
                    onClick = {
                        prefs.hookClass = className.trim()
                        prefs.hookMethod = methodName.trim()
                        prefs.hookMethodArg = methodArg.trim()
                        prefs.hookLoadUrlFromArg = loadFromArg
                        prefs.hookReplaceUrlToArg = replaceToArg
                        scope.launch { model.notify("已保存 hook 点，重启卡包后生效") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    text = "清空",
                    onClick = {
                        className = ""
                        methodName = ""
                        methodArg = ""
                        loadFromArg = false
                        replaceToArg = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
