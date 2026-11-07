package com.dsh.miuix.cardface.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dsh.miuix.cardface.core.GalleryImage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 图库：3 列网格 + 全屏预览 + 长按删除 + 添加图片（相册 → 裁剪 → 存图库）。
 */
@Composable
fun GalleryScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onPickForCrop: (List<android.net.Uri>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<GalleryImage?>(null) }
    var lastPreview by remember { mutableStateOf<GalleryImage?>(null) }
    var deleteTarget by remember { mutableStateOf<GalleryImage?>(null) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(preview) {
        if (preview != null) lastPreview = preview
    }

    val revision = model.prefs.revision + model.storeRevision
    val images = remember(revision, tick) {
        runCatching {
            val applied = model.prefs.mappedImageNames().toSet()
            model.store.list().filterNot { it.name in applied }
        }.getOrDefault(emptyList())
    }

    // 多选：一次最多 9 张，裁剪时按顺序一张一张处理。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) onPickForCrop(uris)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                LargeTitleHeader(
                    title = "图库",
                    subtitle = "已保存 ${images.size} 张替换图",
                    contentPadding = PaddingValues(start = 4.dp, end = 2.dp, top = 14.dp, bottom = 10.dp),
                ) {
                    IconButton(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = "添加图片",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            if (images.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyCard(
                        icon = MiuixIcons.Photos,
                        title = "图库还是空的",
                        description = "从相册选一张图，裁剪后就会保存到这里；\n编辑卡面时也能直接选用。",
                    ) {
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            text = "添加图片",
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                items(items = images, key = { it.name }) { image ->
                    GalleryTile(
                        modifier = if (animationsEnabled) Modifier.animateItem() else Modifier,
                        image = image,
                        onClick = { preview = image },
                        onLongPress = { deleteTarget = image },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = preview != null,
            enter = if (animationsEnabled) {
                fadeIn(animationSpec = tween(180)) +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(180))
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                fadeOut(animationSpec = tween(140)) +
                    scaleOut(targetScale = 0.98f, animationSpec = tween(140))
            } else {
                ExitTransition.None
            },
            label = "galleryPreview",
        ) {
            lastPreview?.let { image ->
                GalleryPreview(image = image, onClose = { preview = null })
            }
        }
    }

    val target = deleteTarget
    ConfirmDialog(
        show = target != null,
        title = "删除这张图？",
        message = "「${target?.name ?: ""}」会从图库删除；已经用到卡面上的映射也会一起清掉。",
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            val image = target
            deleteTarget = null
            if (image != null) {
                scope.launch {
                    // 清掉引用这张图的卡面映射
                    runCatching {
                        model.prefs.allCardUrls().forEach { url ->
                            if (model.prefs.replacementFor(url) == image.name) {
                                model.prefs.setReplacement(url, null)
                            }
                        }
                        model.store.delete(image.name)
                        com.dsh.miuix.cardface.core.CardSync.writeMapSnapshot(context, model.prefs)
                        tick++
                    }.onFailure { model.notify("删除失败：${it.message}") }
                    model.notify("已删除 ${image.name}")
                }
            }
        },
        onDismiss = { deleteTarget = null },
    )
}

@Composable
private fun GalleryTile(
    image: GalleryImage,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        Column {
            AppAsyncImage(
                model = "file://${image.file.absolutePath}",
                contentDescription = image.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CardImaging.TARGET_RATIO)
                    .clip(RoundedCornerShape(16.dp)),
                backgroundBrush = cardThumbnailGradient(),
            )
            Text(
                text = image.displayName,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 全屏预览（深色背景 + 关闭）。 */
@Composable
private fun GalleryPreview(image: GalleryImage, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        AsyncImage(
            model = "file://${image.file.absolutePath}",
            contentDescription = image.name,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentScale = ContentScale.Fit,
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                )
            }
        }
        Text(
            text = image.name,
            style = MiuixTheme.textStyles.footnote1,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}
