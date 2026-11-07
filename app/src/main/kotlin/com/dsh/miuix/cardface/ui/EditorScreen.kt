package com.dsh.miuix.cardface.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dsh.miuix.cardface.core.GalleryImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 编辑页：预览 → 选图（相册 / 网址 / 图库）→ 卡面标志 + 圆角 → 保存并应用。
 */
@Composable
fun EditorScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onStartCrop: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val prefs = model.prefs
    val store = model.store
    val scope = rememberCoroutineScope()
    val draft = model.editor
    val url = draft.url
    val animationsEnabled = LocalAnimationsEnabled.current
    val revision = prefs.revision

    val replacement = remember(revision) { prefs.replacementFor(url) }

    var showPasteDialog by remember { mutableStateOf(false) }
    var showGallerySheet by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 打开编辑页时：如果这个卡面已经用了图库图片，把它载入草稿，方便继续加标志/圆角后重新保存。
    LaunchedEffect(url) {
        val current = prefs.replacementFor(url)
        if (draft.localBitmap == null && current != null && !DeviceBridge.isRemote(current)) {
            draft.localBitmap = store.load(current)
            draft.localCrop = null
        }
    }

    // 裁剪结果 + 标志/圆角变化 → 重新生成预览（等同最终输出）
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(draft.localBitmap, draft.localCrop, draft.logoAsset, draft.roundCorners) {
        val source = draft.localBitmap
        previewBitmap = if (source == null) {
            null
        } else {
            CardImaging.buildCard(
                context = context,
                src = source,
                crop = draft.localCrop,
                opts = CardImaging.Options(
                    roundCorners = draft.roundCorners,
                    logoAsset = draft.logoAsset,
                ),
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onStartCrop(uri)
    }

    fun toast(message: String) {
        scope.launch { model.notify(message) }
    }

    fun applyReplacement(value: String?) {
        prefs.setReplacement(url, value)
        draft.clearPendingImage()
    }

    fun doSave(name: String) {
        val source = draft.localBitmap ?: return
        val finalName = store.uniqueName(name)
        draft.saving = true
        scope.launch {
            val bitmap = CardImaging.buildCard(
                context = context,
                src = source,
                crop = draft.localCrop,
                opts = CardImaging.Options(
                    roundCorners = draft.roundCorners,
                    logoAsset = draft.logoAsset,
                ),
            )
            if (bitmap == null) {
                draft.saving = false
                errorMessage = "图像合成失败：这张图可能已经损坏，换一张试试。"
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) { store.saveApplied(bitmap, finalName) }
            }.onSuccess {
                prefs.setReplacement(url, finalName)
                runCatching {
                    withContext(Dispatchers.IO) {
                        com.dsh.miuix.cardface.core.CardSync.copyImage(context, store, finalName)
                    }
                }
                draft.clearPendingImage()
                savedName = finalName
                draft.saving = false
                showSuccess = true
            }.onFailure {
                draft.saving = false
                errorMessage = "保存失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun startSave() {
        val source = draft.localBitmap
        if (source == null) {
            toast("先从相册选一张图并裁剪")
            return
        }
        // 不再弹文件名对话框：直接用自动命名保存，想改名去首页长按卡面「重命名卡面」。
        doSave(store.defaultName())
    }


    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = 12.dp,
            ),
        ) {
            item(key = "header") {
                BackHeader(title = "自定义卡面", onBack = onBack) {
                    TextButton(
                        text = "清空替换",
                        onClick = { showClearConfirm = true },
                        enabled = replacement != null || draft.localUri != null,
                    )
                }
            }

            item(key = "preview") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(10.dp),
                ) {
                    val localPreview = previewBitmap
                    val model_ = when {
                        localPreview != null -> null
                        replacement.isNullOrBlank() -> url
                        DeviceBridge.isRemote(replacement) -> replacement
                        else -> store.fileUri(replacement!!)
                    }
                    val previewState = EditorPreviewState(localPreview, model_)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(CardImaging.TARGET_RATIO)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer),
                    ) {
                        Crossfade(
                            targetState = previewState,
                            animationSpec = if (animationsEnabled) tween(220) else snap(),
                            label = "editorPreview",
                        ) { state ->
                            when {
                                state.bitmap != null -> androidx.compose.foundation.Image(
                                    bitmap = state.bitmap.asImageBitmap(),
                                    contentDescription = "替换预览",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )

                                !state.model.isNullOrBlank() -> AppAsyncImage(
                                    model = state.model,
                                    contentDescription = "当前预览",
                                    modifier = Modifier.fillMaxSize(),
                                    backgroundBrush = cardThumbnailGradient(),
                                )

                                else -> Icon(
                                    imageVector = MiuixIcons.Photos,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(40.dp),
                                )
                            }
                        }
                        val pill = when {
                            localPreview != null -> "待保存"
                            replacement.isNullOrBlank() -> "原图"
                            DeviceBridge.isRemote(replacement) -> "网络图"
                            else -> "已替换"
                        }
                        StatusPill(
                            text = pill,
                            containerColor = if (localPreview != null || !replacement.isNullOrBlank()) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            },
                            contentColor = if (localPreview != null || !replacement.isNullOrBlank()) {
                                MiuixTheme.colorScheme.onPrimary
                            } else {
                                MiuixTheme.colorScheme.surface
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        )
                    }
                }
            }

            item(key = "status") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(
                        text = when {
                            draft.localBitmap != null -> "当前效果：待保存（裁剪 + 标志 + 圆角已就绪）"
                            replacement.isNullOrBlank() -> "当前效果：原图（还没有替换）"
                            DeviceBridge.isRemote(replacement) -> "当前效果：网络地址替换"
                            else -> "当前效果：已替换为图库图片 $replacement"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = url,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionTile(
                        icon = MiuixIcons.Album,
                        label = "从相册选",
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = MiuixIcons.Link,
                        label = "粘贴网址",
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        icon = MiuixIcons.Photos,
                        label = "从图库选",
                        onClick = { showGallerySheet = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (draft.localBitmap != null) {
                item(key = "logos") {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        SmallTitle(text = "卡面标志")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "none") {
                                LogoChip(
                                    text = "无",
                                    selected = draft.logoAsset == null,
                                    onClick = { draft.logoAsset = null },
                                )
                            }
                            items(items = CARD_LOGOS, key = { it.first }) { logo ->
                                LogoChip(
                                    text = logo.second,
                                    selected = draft.logoAsset == logo.first,
                                    onClick = { draft.logoAsset = logo.first },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "round") {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "圆角裁切",
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "按卡面的圆角把四个角裁掉，避免露出直角",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Switch(
                                checked = draft.roundCorners,
                                onCheckedChange = { draft.roundCorners = it },
                            )
                        }
                    }
                    Text(
                        text = "输出 960×606 PNG · Lanczos3 高质量缩放",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // 固定在底部的保存条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = innerPadding.calculateBottomPadding() + 14.dp,
                ),
        ) {
            PrimaryButton(
                text = if (draft.saving) "正在生成…" else "保存并应用",
                onClick = { startSave() },
                enabled = !draft.saving && draft.localBitmap != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 粘贴网址
    if (showPasteDialog) {
        var text by remember { mutableStateOf("") }
        OverlayDialog(
            show = true,
            title = "粘贴图片网址",
            summary = "支持 http / https 直链；只做地址替换，不上传图片。",
            onDismissRequest = { showPasteDialog = false },
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = "图片地址",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { showPasteDialog = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "使用这个地址",
                    onClick = {
                        val value = text.trim()
                        if (!value.startsWith("http://") && !value.startsWith("https://")) {
                            toast("地址要以 http:// 或 https:// 开头")
                        } else {
                            applyReplacement(value)
                            showPasteDialog = false
                            toast("已应用网络图片，重启卡包后生效")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // 从图库选
    if (showGallerySheet) {
        val images = remember {
            runCatching {
                val applied = prefs.mappedImageNames().toSet()
                store.list().filterNot { it.name in applied }
            }.getOrDefault(emptyList())
        }
        OverlayBottomSheet(
            show = true,
            title = "从图库选择",
            onDismissRequest = { showGallerySheet = false },
        ) {
            if (images.isEmpty()) {
                Text(
                    text = "图库还是空的，先在「图库」页添加图片。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = images, key = { it.name }) { image ->
                        GalleryPickTile(
                            image = image,
                            onClick = {
                                val bitmap = store.load(image.name)
                                if (bitmap == null) {
                                    toast("这张图读不出来：${image.name}")
                                } else {
                                    draft.localBitmap = bitmap
                                    draft.localCrop = null
                                    draft.localUri = null
                                    showGallerySheet = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 保存文件名
    // 成功
    if (showSuccess) {
        OverlayDialog(
            show = true,
            title = "已应用到卡包",
            summary = "替换图「$savedName」已保存，重启卡包后生效。",
            onDismissRequest = { showSuccess = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "重启卡包",
                    onClick = {
                        showSuccess = false
                        scope.launch {
                            val kind = com.dsh.miuix.cardface.core.WalletApps.primary(context)
                            val ok = withContext(Dispatchers.IO) {
                                DeviceBridge.restartWallet(context, kind)
                            }
                            toast(if (ok) "已重启卡包" else "请手动从后台划掉卡包，再重新打开")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "完成",
                    onClick = { showSuccess = false },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // 清空替换
    ConfirmDialog(
        show = showClearConfirm,
        title = "清空这张卡面的替换？",
        message = "会把这张卡面恢复成卡包里的原图（图库里的图片仍然保留）。",
        confirmText = "清空",
        destructive = true,
        onConfirm = {
            showClearConfirm = false
            replacement?.takeIf { !DeviceBridge.isRemote(it) }?.let { store.delete(it) }
            applyReplacement(null)
            toast("已清空替换，重启卡包后生效")
        },
        onDismiss = { showClearConfirm = false },
    )

    // 错误
    val error = errorMessage
    if (error != null) {
        InfoDialog(
            show = true,
            title = "保存失败",
            message = error,
            onDismiss = { errorMessage = null },
        )
    }
}

@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(vertical = 14.dp),
        colors = CardDefaults.defaultColors(),
        onClick = onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            maxLines = 1,
        )
    }
}

@Composable
private fun LogoChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        cornerRadius = 50.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        colors = if (selected) {
            CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.primary,
                contentColor = MiuixTheme.colorScheme.onPrimary,
            )
        } else {
            CardDefaults.defaultColors()
        },
        onClick = onClick,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun GalleryPickTile(image: GalleryImage, onClick: () -> Unit) {
    Card(
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
    ) {
        AppAsyncImage(
            model = "file://${image.file.absolutePath}",
            contentDescription = image.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardImaging.TARGET_RATIO)
                .clip(RoundedCornerShape(12.dp)),
            backgroundBrush = cardThumbnailGradient(),
        )
        Text(
            text = image.displayName,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 预览来源：本地裁剪结果（Bitmap）或远程/图库地址（String）；用于 Crossfade 的相等性判断。 */
private data class EditorPreviewState(
    val bitmap: android.graphics.Bitmap?,
    val model: String?,
)
