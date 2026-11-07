package com.dsh.miuix.cardface.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 裁剪页：固定 160:101 视口，图片可双指缩放（1~8x）/单指拖动，边界约束；
 * 用 Canvas 自己画（不用 Image + ContentScale.Crop，避免"看起来被硬截断、拖不动"）。
 */
@Composable
fun CropScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onDone: (bitmap: Bitmap, cropRect: RectF) -> Unit,
) {
    val context = LocalContext.current
    val uri = model.cropUri

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        loading = true
        failed = false
        source = if (uri == null) null else CardImaging.loadSource(context, uri, maxDim = 2048)
        failed = uri != null && source == null
        loading = false
    }

    val bitmap = source
    val srcW = bitmap?.width ?: 0
    val srcH = bitmap?.height ?: 0

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 批量裁剪切换下一张时重置视图，避免带着上一张的缩放/位移。
    LaunchedEffect(uri) {
        zoom = 1f
        offset = Offset.Zero
    }

    fun baseScale(): Float {
        if (srcW == 0 || srcH == 0 || viewport.width == 0 || viewport.height == 0) return 1f
        return maxOf(viewport.width.toFloat() / srcW, viewport.height.toFloat() / srcH)
    }

    fun maxOffset(totalScale: Float): Offset {
        val dw = srcW * totalScale
        val dh = srcH * totalScale
        return Offset(
            ((dw - viewport.width) / 2f).coerceAtLeast(0f),
            ((dh - viewport.height) / 2f).coerceAtLeast(0f),
        )
    }

    fun clampOffset(raw: Offset, targetZoom: Float): Offset {
        val total = baseScale() * targetZoom
        if (total <= 0f || viewport.width == 0) return Offset.Zero
        val max = maxOffset(total)
        return Offset(raw.x.coerceIn(-max.x, max.x), raw.y.coerceIn(-max.y, max.y))
    }

    fun reset() {
        zoom = 1f
        offset = Offset.Zero
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding()),
    ) {
        // 视口：160:101
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CardImaging.TARGET_RATIO)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .onSizeChanged { size ->
                        viewport = size
                        offset = clampOffset(offset, zoom)
                    }
                    .pointerInput(bitmap, viewport) {
                        if (bitmap == null) return@pointerInput
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                            zoom = newZoom
                            offset = clampOffset(offset + pan, newZoom)
                        }
                    }
                    .pointerInput(bitmap) {
                        detectTapGestures(
                            onDoubleTap = {
                                reset()
                            },
                        )
                    },
            ) {
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                    )

                    bitmap != null -> {
                        Canvas(Modifier.fillMaxSize()) {
                            val total = baseScale() * zoom
                            val dw = srcW * total
                            val dh = srcH * total
                            val left = (size.width - dw) / 2f + offset.x
                            val top = (size.height - dh) / 2f + offset.y
                            drawImage(
                                image = bitmap.asImageBitmap(),
                                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                            )
                        }
                        CropOverlay()
                    }

                    failed -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Photos,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "这张图读不出来，返回换一张试试",
                            style = MiuixTheme.textStyles.footnote1,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // 缩放控制：按钮方式也能调，触屏手势不好用时兜底
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f); offset = clampOffset(offset, zoom) }) {
                Icon(
                    imageVector = MiuixIcons.Clear,
                    contentDescription = "缩小",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "缩放 %.1fx".format(zoom),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(8f); offset = clampOffset(offset, zoom) }) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "放大",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = { reset() }) {
                Icon(
                    imageVector = MiuixIcons.Reset,
                    contentDescription = "重置",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        }

        Text(
            text = "双指缩放 · 单指拖动 · 双击重置 · 比例固定 160:101",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )

        PrimaryButton(
            text = "完成裁剪",
            onClick = {
                val bmp = bitmap ?: return@PrimaryButton
                if (viewport.width == 0 || viewport.height == 0) return@PrimaryButton
                val total = baseScale() * zoom
                val cropW = (viewport.width / total).coerceAtMost(srcW.toFloat())
                val cropH = (viewport.height / total).coerceAtMost(srcH.toFloat())
                val centerX = srcW / 2f - offset.x / total
                val centerY = srcH / 2f - offset.y / total
                val left = (centerX - cropW / 2f).coerceIn(0f, (srcW - cropW).coerceAtLeast(0f))
                val top = (centerY - cropH / 2f).coerceIn(0f, (srcH - cropH).coerceAtLeast(0f))
                onDone(bmp, RectF(left, top, left + cropW, top + cropH))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 2.dp,
                    bottom = innerPadding.calculateBottomPadding() + 14.dp,
                ),
            enabled = bitmap != null && !loading,
        )
    }
}

/** 裁剪框叠加层：三分线 + 四角 L 形标记 + 边框。 */
@Composable
private fun CropOverlay() {
    val border = Color.White.copy(alpha = 0.6f)
    val grid = Color.White.copy(alpha = 0.22f)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val thin = 1.dp.toPx()
        drawRect(color = border, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(thin * 1.5f))
        for (i in 1..2) {
            val x = w * i / 3f
            drawLine(grid, Offset(x, 0f), Offset(x, h), thin)
            val y = h * i / 3f
            drawLine(grid, Offset(0f, y), Offset(w, y), thin)
        }
        val arm = 22.dp.toPx()
        val inset = 6.dp.toPx()
        val thick = 3.dp.toPx()
        val corners = listOf(
            Triple(Offset(inset, inset), 1f, 1f),
            Triple(Offset(w - inset, inset), -1f, 1f),
            Triple(Offset(inset, h - inset), 1f, -1f),
            Triple(Offset(w - inset, h - inset), -1f, -1f),
        )
        corners.forEach { (p, sx, sy) ->
            drawLine(border, p, Offset(p.x + arm * sx, p.y), thick)
            drawLine(border, p, Offset(p.x, p.y + arm * sy), thick)
        }
    }
}
