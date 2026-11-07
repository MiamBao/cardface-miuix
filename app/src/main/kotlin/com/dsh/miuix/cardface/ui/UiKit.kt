package com.dsh.miuix.cardface.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import top.yukonga.miuix.kmp.icon.extended.Photos
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 页签页的大标题行：大标题 + 右侧操作（SukiSU 那种「大标题 + 图标按钮」）。 */
@Composable
fun LargeTitleHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
        actions()
    }
}

/** 子页面标题行（返回 + 标题 + 操作）。不放进 Scaffold.topBar，避免和根 Scaffold 的 inset 打架。 */
@Composable
fun BackHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = MiuixIcons.Back,
                contentDescription = "返回",
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** 页签页的分组卡片（左右 16dp 留白）。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(vertical = 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        insideMargin = insideMargin,
        content = content,
    )
}

/** 小胶囊标签（已替换 / 未替换 / 待保存…）。 */
@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(containerColor, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 圆形彩色图标（状态卡右侧 / 空状态）。 */
@Composable
fun CircleIcon(
    imageVector: ImageVector,
    containerColor: Color,
    contentColor: Color,
    size: Dp = 64.dp,
    iconSize: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(containerColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 主按钮。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button, maxLines = 1)
    }
}

/** 次按钮。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button, maxLines = 1)
    }
}

/** 带编号圆点的步骤行（空状态 / 引导页共用）。 */
@Composable
fun NumberedStep(
    index: Int,
    title: String,
    description: String,
    done: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    if (done) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    text = actionText,
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 只读信息对话框。 */
@Composable
fun InfoDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String = "知道了",
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(
            text = confirmText,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 二次确认对话框。 */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String = "确定",
    cancelText: String = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                text = cancelText,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.error,
                        textColor = MiuixTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }
}

/**
 * 底部悬浮胶囊里的页签项：图标 + 文字。
 *
 * Miuix 0.9.4 的 FloatingNavigationBarItem 只画图标（label 只作为无障碍描述），
 * 所以 SukiSU 那种「图标+文字」的胶囊要自己组合；选中项用主色胶囊底突出。
 */
@Composable
fun FloatingTabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    val containerColor = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurfaceContainerVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 卡片内的行间距分隔线（Miuix 默认不给 Card 插线，需要时显式加）。 */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(0.75.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

/** 空状态卡片。 */
@Composable
fun EmptyCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        insideMargin = PaddingValues(20.dp),
        colors = CardDefaults.defaultColors(),
    ) {
        CircleIcon(
            imageVector = icon,
            containerColor = MiuixTheme.colorScheme.secondaryContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp),
        )
        Text(
            text = title,
            style = MiuixTheme.textStyles.headline2,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        content()
    }
}

/** Coil 图片 + 加载失败占位（灰色图标 + 「加载失败」），卡面/图库/预览共用。 */
@Composable
fun AppAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    backgroundBrush: Brush? = null,
) {
    var failed by remember(model) { mutableStateOf(false) }
    val placeholderColor = MiuixTheme.colorScheme.secondaryContainer
    val backgroundModifier = if (backgroundBrush != null) {
        Modifier.background(backgroundBrush)
    } else {
        Modifier.background(placeholderColor)
    }
    Box(modifier = modifier.then(backgroundModifier)) {
        if (!failed && model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onError = { failed = true },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Photos,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "加载失败",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 卡面缩略图背后的柔和渐变：真实图片是不透明的会盖住；演示图/透明 PNG 会露出这层，看起来更像一张卡。 */
@Composable
fun cardThumbnailGradient(): Brush = Brush.linearGradient(
    colors = listOf(
        MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        MiuixTheme.colorScheme.tertiaryContainer,
    ),
)

/** 底栏页签。 */
data class SukiTab(
    val icon: ImageVector,
    val label: String,
)

/**
 * SukiSU 风格悬浮底栏：白色圆角胶囊 + 选中项滑动的高亮胶囊 + 图标/文字。
 *
 * 完全按 SukiSU 的观感实现：整条悬浮在页面底部，左右各留 28dp，高度 64dp，
 * 选中项背景是主色 14% 的胶囊，图标和文字变成主色，指示器用 spring 滑动过去。
 */
@Composable
fun SukiFloatingBottomBar(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    tabs: List<SukiTab>,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    val animX = remember { Animatable(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 10.dp)
            .shadow(elevation = 16.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(colors.surfaceContainer)
            .height(64.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val itemWidth = (maxWidth - 12.dp) / tabs.size
            LaunchedEffect(selectedIndex, maxWidth) {
                val target = with(density) { (6.dp + itemWidth * selectedIndex).toPx() }
                animX.animateTo(target, animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f))
            }
            // 选中指示器（在内容下面）
            Box(
                modifier = Modifier
                    .offset { IntOffset(animX.value.roundToInt(), 0) }
                    .padding(vertical = 8.dp)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.14f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val contentColor = if (selected) colors.primary else colors.onSurfaceContainerVariant
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .clickable(role = Role.Tab) { onSelected(index) },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier.size(23.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
