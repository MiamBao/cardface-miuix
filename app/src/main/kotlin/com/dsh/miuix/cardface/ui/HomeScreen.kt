package com.dsh.miuix.cardface.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dsh.miuix.cardface.core.CardFaceItem
import com.dsh.miuix.cardface.core.DemoCards
import com.dsh.miuix.cardface.core.ModuleStatus
import com.dsh.miuix.cardface.core.PackageOps
import com.dsh.miuix.cardface.core.WalletApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页（卡面）：大标题 + 状态 hero 卡 + 2 列卡面网格 + 空状态三步引导 + 下拉刷新。
 */
@Composable
fun HomeScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onOpenEditor: (String) -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = model.prefs
    val store = model.store
    val scope = rememberCoroutineScope()

    // prefs.revision 变化（本进程写过数据）+ refreshTick（手动刷新）都触发重读。
    val revision = prefs.revision
    var refreshTick by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    val status = remember(revision, refreshTick) {
        runCatching { WalletApps.status(context, prefs) }.getOrElse { ModuleStatus() }
    }
    val faces = remember(revision, refreshTick) {
        runCatching { prefs.cardFaces() }.getOrDefault(emptyList())
    }
    val replacedCount = faces.count { it.replaced }
    // 本机没装卡包但载入了演示卡面时，首页显示「演示模式」而不是红色的「模块未生效」。
    val demoMode = status.walletKind == null && faces.any { DemoCards.isDemoUrl(it.url) }

    var sheetFace by remember { mutableStateOf<CardFaceItem?>(null) }
    var renameFace by remember { mutableStateOf<CardFaceItem?>(null) }
    var demoLoading by remember { mutableStateOf(false) }

    val pullToRefreshState = rememberPullToRefreshState()
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            delay(500)
            refreshTick++
            isRefreshing = false
        }
    }

    val animationsEnabled = LocalAnimationsEnabled.current
    val gridState = rememberLazyGridState()
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = { isRefreshing = true },
        pullToRefreshState = pullToRefreshState,
        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                LargeTitleHeader(
                    title = "卡面工坊",
                    subtitle = "把卡包里千篇一律的卡面换成你喜欢的图",
                    contentPadding = PaddingValues(start = 4.dp, end = 2.dp, top = 14.dp, bottom = 10.dp),
                ) {
                    IconButton(onClick = { isRefreshing = true }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "刷新",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                HeroStatusCard(
                    status = status,
                    faceCount = faces.size,
                    replacedCount = replacedCount,
                    demoMode = demoMode,
                    onActivate = onOpenGuide,
                )
            }

            if (faces.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyHomeCard(
                        status = status,
                        demoLoading = demoLoading,
                        onRefresh = { isRefreshing = true },
                        onOpenWallet = {
                            if (!DeviceBridge.openWallet(context, status.walletKind)) {
                                scope.launch { model.notify("没有找到卡包应用") }
                            }
                        },
                        onLoadDemo = {
                            if (!demoLoading) {
                                demoLoading = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { DemoCards.seed(context, prefs, store) }.getOrDefault(false)
                                    }
                                    demoLoading = false
                                    refreshTick++
                                    model.bumpStore()
                                    model.notify(
                                        if (ok) "已载入演示卡面，点卡片就能试完整流程" else "演示卡面载入失败",
                                    )
                                }
                            }
                        },
                        onOpenGuide = onOpenGuide,
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
                    SmallTitle(
                        text = "已识别卡面 (${faces.size})",
                        insideMargin = PaddingValues(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                items(items = faces, key = { it.url }) { face ->
                    CardFaceTile(
                        modifier = if (animationsEnabled) Modifier.animateItem() else Modifier,
                        face = face,
                        storeFileUri = face.replacement?.let { runCatching { store.fileUri(it) }.getOrDefault("") },
                        onClick = { onOpenEditor(face.url) },
                        onLongPress = { sheetFace = face },
                    )
                }
            }
        }
    }

    // 长按卡面 → 底部弹层
    val currentSheet = sheetFace
    OverlayBottomSheet(
        show = currentSheet != null,
        title = currentSheet?.let { nameOf(it) },
        onDismissRequest = { sheetFace = null },
    ) {
        Card {
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "复制卡面地址",
                summary = "用于排查或手动配置",
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Copy,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceContainer,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = {
                    val url = currentSheet?.url
                    sheetFace = null
                    if (url != null) {
                        DeviceBridge.copyText(context, url)
                        scope.launch { model.notify("卡面地址已复制") }
                    }
                },
            )
            RowDivider()
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "重命名卡面",
                summary = "自定义名称，方便区分不同的卡",
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Rename,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceContainer,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = {
                    renameFace = currentSheet
                    sheetFace = null
                },
            )
            RowDivider()
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "加入黑名单",
                summary = "从主界面隐藏这张卡面，不影响替换",
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Blocklist,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = {
                    val url = currentSheet?.url
                    sheetFace = null
                    if (url != null) {
                        model.prefs.addBlacklisted(url)
                        scope.launch { model.notify("已加入黑名单") }
                    }
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(text = "取消", onClick = { sheetFace = null }, modifier = Modifier.fillMaxWidth())
    }

    // 重命名对话框
    val renaming = renameFace
    if (renaming != null) {
        var input by remember(renaming.url) {
            mutableStateOf(prefs.customName(renaming.url) ?: renaming.displayName)
        }
        OverlayDialog(
            show = true,
            title = "重命名卡面",
            summary = "留空则恢复默认名称（取自卡面地址）",
            onDismissRequest = { renameFace = null },
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                label = "卡面名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { renameFace = null },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "保存",
                    onClick = {
                        prefs.setCustomName(renaming.url, input)
                        scope.launch { model.notify("已重命名为「${input.ifBlank { renaming.displayName }}」") }
                        renameFace = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun nameOf(item: CardFaceItem): String = item.displayName

/** 状态卡：模块是否生效 + 卡面数量统计 + 一句话提示。 */
@Composable
private fun HeroStatusCard(
    status: ModuleStatus,
    faceCount: Int,
    replacedCount: Int,
    demoMode: Boolean,
    onActivate: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60L * 60L * 1000L
    val fresh = status.lastHookAt > 0L && now - status.lastHookAt < dayMs
    val staleDays = if (status.lastHookAt > 0L) {
        (((now - status.lastHookAt) / dayMs).toInt()).coerceAtLeast(1)
    } else {
        0
    }

    val animationsEnabled = LocalAnimationsEnabled.current
    val bg = MiuixTheme.colorScheme.background
    val dark = (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) < 0.5f
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    if (fresh) {
        // ===== SukiSU 同款绿卡 =====
        // 浅绿底 #DFFAE4、36sp 粗体标题、等宽小字，右下角 150dp 绿色对勾（偏移 27/31，被卡片裁掉一角）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(
                color = if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4),
            ),
        ) {
            Box {
                BigCheckCircle(
                    color = Color(0xFF36D167),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = (-10).dp)
                        .size(84.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 112.dp)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "已生效",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurface,
                        )
                        Text(
                            text = "已识别 $faceCount 张 · 已替换 $replacedCount 张",
                            fontSize = 13.sp,
                            color = summaryColor,
                        )
                        Text(
                            text = "LSPosed · 小米智能卡",
                            fontSize = 13.sp,
                            color = summaryColor,
                        )
                    }
                }
            }
        }
        return
    }

    // ===== 其余状态：SukiSU 的白色卡片形态（状态行 + 36sp 大标题 + 按钮） =====
    val accent: Color
    val statusLine: String
    val bigTitle: String
    when {
        demoMode -> {
            accent = Color(0xFF3482FF)
            statusLine = "演示模式 · 未检测到真实卡包数据"
            bigTitle = "演示卡面"
        }

        status.lastHookAt > 0L -> {
            accent = Color(0xFFF59E0B)
            statusLine = "上次生效：${staleDays} 天前"
            bigTitle = "需要刷新"
        }

        else -> {
            accent = Color(0xFFF72727)
            statusLine = "LSPosed 模块未生效"
            bigTitle = "未生效"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = statusLine,
                    fontSize = 13.sp,
                    color = accent,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = bigTitle,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = onSurface,
            )
            if (demoMode) {
                Text(
                    text = "本机没装卡包，当前是内置演示卡面；装好卡包后打开浏览一遍就能识别真实卡面",
                    style = MiuixTheme.textStyles.footnote2,
                    color = summaryColor,
                )
            } else {
                TextButton(
                    text = "去启用",
                    onClick = onActivate,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 大号「圆圈对勾」，仿 SukiSU 卡片里的 CheckCircleOutline（150dp，比卡片还大一点）。 */
@Composable
private fun BigCheckCircle(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val sw = size.minDimension * 0.065f
        val radius = size.minDimension / 2f - sw / 2f
        drawCircle(color = color, radius = radius, style = Stroke(width = sw))
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.305f, h * 0.52f)
            lineTo(w * 0.45f, h * 0.665f)
            lineTo(w * 0.72f, h * 0.385f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 空状态：三步说明 + 刷新 / 打开卡包。 */
@Composable
private fun EmptyHomeCard(
    status: ModuleStatus,
    demoLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenWallet: () -> Unit,
    onLoadDemo: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(
                imageVector = MiuixIcons.BankCards,
                containerColor = MiuixTheme.colorScheme.secondaryContainer,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                size = 48.dp,
                iconSize = 26.dp,
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = "还没有识别到卡面",
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "按下面三步操作就能看到卡面",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            index = 1,
            title = "在 LSPosed 里启用「卡面工坊」",
            description = "勾选作用域：小米智能卡",
            done = status.lsposedInstalled && status.moduleActivated,
        )
        NumberedStep(
            index = 2,
            title = "打开卡包，把卡面浏览一遍",
            description = "卡包加载过的卡面才会被记录下来",
            done = status.walletInstalled && status.lastHookAt > 0L,
        )
        NumberedStep(
            index = 3,
            title = "回到这里刷新",
            description = "下拉刷新，或者点下面的「刷新列表」",
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.walletInstalled) {
                SecondaryButton(
                    text = "打开卡包",
                    onClick = onOpenWallet,
                    modifier = Modifier.weight(1f),
                )
            }
            PrimaryButton(
                text = "刷新列表",
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            text = if (demoLoading) "正在载入演示卡面…" else "载入演示卡面（不需要卡包）",
            onClick = onLoadDemo,
            modifier = Modifier.fillMaxWidth(),
            enabled = !demoLoading,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "本机没装卡包也能先玩：演示卡面会内置两张示例图，流程和真实卡面完全一样。",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(
            text = "查看使用说明",
            onClick = onOpenGuide,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 一张卡面的网格卡片：160:101 图 + 已替换胶囊 + 名称。 */
@Composable
private fun CardFaceTile(
    face: CardFaceItem,
    storeFileUri: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = when {
        face.replacement.isNullOrBlank() -> face.url
        DeviceBridge.isRemote(face.replacement) -> face.replacement
        !storeFileUri.isNullOrBlank() -> storeFileUri
        else -> face.url
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(8.dp),
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardImaging.TARGET_RATIO)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            AppAsyncImage(
                model = model,
                contentDescription = face.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CardImaging.TARGET_RATIO),
                backgroundBrush = cardThumbnailGradient(),
            )
            if (face.replaced) {
                StatusPill(
                    text = "已替换",
                    containerColor = MiuixTheme.colorScheme.primary,
                    contentColor = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            } else {
                StatusPill(
                    text = "未替换",
                    containerColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    contentColor = MiuixTheme.colorScheme.surface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 2.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = face.displayName,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
