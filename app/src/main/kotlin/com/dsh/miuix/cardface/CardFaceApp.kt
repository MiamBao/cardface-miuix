package com.dsh.miuix.cardface

import androidx.activity.compose.BackHandler
import com.dsh.miuix.cardface.ui.NavDirection
import com.dsh.miuix.cardface.ui.LocalAnimationsEnabled
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.miuix.cardface.core.appearance
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import com.dsh.miuix.cardface.core.Appearance
import com.dsh.miuix.cardface.core.CardSync
import com.dsh.miuix.cardface.core.HookAutoRefresh
import com.dsh.miuix.cardface.ui.AppModel
import com.dsh.miuix.cardface.ui.AboutScreen
import com.dsh.miuix.cardface.ui.BlacklistScreen
import com.dsh.miuix.cardface.ui.CardImaging
import com.dsh.miuix.cardface.ui.ChangelogScreen
import com.dsh.miuix.cardface.ui.CatalogScreen
import com.dsh.miuix.cardface.ui.CropScreen
import com.dsh.miuix.cardface.ui.CropTarget
import com.dsh.miuix.cardface.ui.DevConsoleScreen
import com.dsh.miuix.cardface.ui.DevHookScreen
import com.dsh.miuix.cardface.ui.EditorScreen
import com.dsh.miuix.cardface.ui.FaqScreen
import com.dsh.miuix.cardface.ui.SukiFloatingBottomBar
import com.dsh.miuix.cardface.ui.SukiTab
import com.dsh.miuix.cardface.ui.GalleryScreen
import com.dsh.miuix.cardface.ui.FloatingTabItem
import com.dsh.miuix.cardface.ui.GuideScreen
import com.dsh.miuix.cardface.ui.HomeScreen
import com.dsh.miuix.cardface.ui.NavState
import com.dsh.miuix.cardface.ui.Page
import com.dsh.miuix.cardface.ui.Route
import com.dsh.miuix.cardface.ui.SettingsScreen
import com.dsh.miuix.cardface.ui.VersionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用根组件：MiuixTheme + 单 Activity Compose 状态导航。
 *
 * 路由：home / gallery / settings / editor/{url} / crop / guide / faq / about / blacklist / hook。
 * 底部悬浮胶囊导航只在三个页签页显示；子页面用返回键或标题栏返回。
 */
@Composable
fun CardFaceApp() {
    val context = LocalContext.current
    val model = remember { AppModel(context) }

    // 只有外观设置变化时才重建主题控制器。
    // 不能用 prefs.revision：hook 上报卡面 / last_hook_at 也会自增，那会在使用过程中反复换主题导致卡顿。
    val appearanceRevision = model.prefs.appearanceRevision
    val appearance = remember(appearanceRevision) { model.prefs.appearance() }
    val controller = remember(appearance) { appearance.createController() }

    // 启动时把已有映射的替换图同步到卡包私有目录（原版方案的 file:// 路径最稳）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext<Unit>(Dispatchers.IO) {
            try {
                CardSync.syncAll(context, model.prefs, model.store)
            } catch (_: Throwable) {
            }
            try {
                HookAutoRefresh.ensureFresh(context, model.prefs)
            } catch (_: Throwable) {
            }
            try {
                model.prefs.sanitizeCardUrls()
            } catch (_: Throwable) {
            }
        }
    }

    MiuixTheme(controller = controller) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalAnimationsEnabled provides appearance.animations,
        ) {
            CardFaceRoot(model, appearance.animations)
        }
    }
}

@Composable
private fun CardFaceRoot(model: AppModel, animationsEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = rememberSaveable(saver = NavState.Saver) { NavState() }
    val glassBackdrop = rememberLayerBackdrop()
    var pendingGallerySave by remember { mutableStateOf<Bitmap?>(null) }
    var pendingGalleryName by remember { mutableStateOf("") }
    val route = nav.current
    val currentPage = route.page

    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    // 首次启动：直接进引导页（首页作为返回栈底，返回键不会退出应用）。
    LaunchedEffect(Unit) {
        if (model.prefs.firstRun && nav.routes.size == 1) {
            nav.push(Route(Page.Guide))
        }
    }

    // 启动时静默检查 GitHub 最新版本（可在关于页关掉）。
    LaunchedEffect(Unit) {
        if (!model.prefs.autoCheckUpdate) return@LaunchedEffect
        val latest = withContext(Dispatchers.IO) {
            runCatching { com.dsh.miuix.cardface.core.Updates.fetchLatest() }.getOrNull()
        } ?: return@LaunchedEffect
        val current = com.dsh.miuix.cardface.ui.DeviceBridge.versionName(context)
        if (latest.versionName.isNotBlank() &&
            com.dsh.miuix.cardface.core.Updates.isNewer(latest.versionName, current)
        ) {
            model.notify("发现新版本 v${latest.versionName}，可在关于页查看")
        }
    }

    Scaffold(
        topBar = {
            if (currentPage == Page.Crop) {
                SmallTopAppBar(
                    title = "裁剪卡面",
                    navigationIcon = {
                        IconButton(onClick = { nav.pop() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (currentPage.isTab && !model.galleryPreviewVisible && !model.posterPreviewVisible) {
                val tabs = listOf(
                    GlassNavigationItem(MiuixIcons.Home, "卡面", "卡面"),
                    GlassNavigationItem(MiuixIcons.Photos, "图库", "图库"),
                    GlassNavigationItem(MiuixIcons.Settings, "设置", "设置"),
                )
                val selected = when (currentPage) {
                    Page.Home -> 0
                    Page.Gallery -> 1
                    else -> 2
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    GlassNavigationBar(
                        items = tabs,
                        selectedIndex = selected,
                        onSelect = { index ->
                            nav.resetTo(
                                when (index) {
                                    0 -> Page.Home
                                    1 -> Page.Gallery
                                    else -> Page.Settings
                                },
                            )
                        },
                        backdrop = glassBackdrop,
                        defaultWindowInsetsPadding = true,
                        modifier = Modifier
                            .widthIn(max = 330.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(
                model.snackbar,
                modifier = Modifier.padding(
                    bottom = if (currentPage == Page.Editor) 96.dp else 0.dp,
                ),
            )
        },
        containerColor = MiuixTheme.colorScheme.surface,
    ) { innerPadding ->
        AnimatedContent(
            targetState = route,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(glassBackdrop),
            transitionSpec = {
                if (!animationsEnabled) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    when (nav.direction) {
                        NavDirection.Forward -> (
                            slideInHorizontally(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                initialOffsetX = { it / 3 },
                            ) + fadeIn(animationSpec = tween(220))
                            ) togetherWith (
                            slideOutHorizontally(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                targetOffsetX = { -it / 3 },
                            ) + fadeOut(animationSpec = tween(160))
                            )

                        NavDirection.Backward -> (
                            slideInHorizontally(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                initialOffsetX = { -it / 3 },
                            ) + fadeIn(animationSpec = tween(220))
                            ) togetherWith (
                            slideOutHorizontally(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                targetOffsetX = { it / 3 },
                            ) + fadeOut(animationSpec = tween(160))
                            )

                        NavDirection.Tab -> (
                            fadeIn(animationSpec = tween(180)) +
                                scaleIn(initialScale = 0.98f, animationSpec = tween(180))
                            ) togetherWith fadeOut(animationSpec = tween(120))
                    }
                }
            },
            contentKey = { it.page },
            label = "routeTransition",
        ) { targetRoute ->
            val page = targetRoute.page
            Box(modifier = Modifier.fillMaxSize()) {
            when (page) {
                Page.Home -> HomeScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onOpenEditor = { url ->
                        model.openEditor(url)
                        nav.push(Route(Page.Editor, url))
                    },
                    onOpenFaq = { nav.push(Route(Page.Faq)) },
                    onOpenGuide = { nav.push(Route(Page.Guide)) },
                )

                Page.Gallery -> GalleryScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onPickForCrop = { uris ->
                        model.startCropBatch(CropTarget.Gallery, uris)
                        if (uris.isNotEmpty()) nav.push(Route(Page.Crop))
                    },
                    onOpenCatalog = {
                        model.openCatalog(CropTarget.Gallery)
                        nav.push(Route(Page.Catalog))
                    },
                )

                Page.Settings -> SettingsScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onOpenBlacklist = { nav.push(Route(Page.Blacklist)) },
                    onOpenAbout = { nav.push(Route(Page.About)) },
                    onOpenVersion = { nav.push(Route(Page.Version)) },
                )

                Page.Editor -> EditorScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                    onStartCrop = { uri ->
                        model.startCrop(CropTarget.Editor)
                        model.cropUri = uri
                        nav.push(Route(Page.Crop))
                    },
                )

                Page.Catalog -> CatalogScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                    onUse = { uri ->
                        val forEditor = model.catalogForEditor
                        nav.pop()
                        if (forEditor) {
                            model.startCrop(CropTarget.Editor)
                            model.cropUri = uri
                        } else {
                            model.startCropBatch(CropTarget.Gallery, listOf(uri))
                        }
                        nav.push(Route(Page.Crop))
                    },
                )

                Page.Crop -> CropScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onDone = { bitmap, cropRect ->
                        if (model.cropTarget == CropTarget.Editor) {
                            val draft = model.editor
                            draft.localUri = model.cropUri
                            draft.localBitmap = bitmap
                            draft.localCrop = cropRect
                            nav.pop()
                        } else {
                            scope.launch {
                                val card = CardImaging.buildCard(
                                    context = context,
                                    src = bitmap,
                                    crop = cropRect,
                                    opts = CardImaging.Options(roundCorners = true, logoAsset = null),
                                )
                                val batchTotal = model.cropBatchTotal
                                var saved = false
                                var name = ""
                                if (card == null) {
                                    model.notify("保存失败：图片合成出错")
                                } else if (model.prefs.askNameOnGallerySave && batchTotal <= 1) {
                                    // 开关打开：先弹命名框，等用户确认后再保存
                                    pendingGallerySave = card
                                    pendingGalleryName = model.store.defaultName().removeSuffix(".png")
                                    return@launch
                                } else {
                                    name = model.store.uniqueName(model.store.defaultName())
                                    saved = withContext(Dispatchers.IO) {
                                        runCatching { model.store.save(card, name) }.isSuccess
                                    }
                                    model.bumpStore()
                                    // 批量裁剪时不在每张之间弹提示，避免挡住底部按钮；全部完成再汇总一次。
                                    if (batchTotal <= 1) {
                                        model.notify(if (saved) "已保存到图库：$name" else "保存失败")
                                    }
                                }
                                // 还有下一张就留在裁剪页继续，全部处理完再返回。
                                val hasNext = model.advanceCropBatch()
                                if (!hasNext) {
                                    if (batchTotal > 1) {
                                        model.notify(if (saved) "已保存 $batchTotal 张到图库" else "批量保存完成，有图片失败")
                                    }
                                    nav.pop()
                                }
                            }
                        }
                    },
                )

                Page.Version -> VersionScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                    onOpenChangelog = { nav.push(Route(Page.Changelog)) },
                )

                Page.Changelog -> ChangelogScreen(
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                )

                Page.Guide -> GuideScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                    onFinish = {
                        model.prefs.firstRun = false
                        nav.resetTo(Page.Home)
                    },
                )

                Page.Faq -> FaqScreen(innerPadding = innerPadding, onBack = { nav.pop() })

                Page.About -> AboutScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                    onOpenChangelog = { nav.push(Route(Page.Changelog)) },
                )

                Page.Blacklist -> BlacklistScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                )

                Page.DevConsole -> DevConsoleScreen(
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },

                )

                Page.DevHook -> DevHookScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onBack = { nav.pop() },
                )
            }
            }
        }

    val saveBitmap = pendingGallerySave
    if (saveBitmap != null) {
        OverlayDialog(
            show = true,
            title = "命名图片",
            summary = "保存到图库前给这张图起个名字",
            onDismissRequest = {
                pendingGallerySave = null
                saveBitmap.recycle()
                nav.pop()
            },
        ) {
            TextField(
                value = pendingGalleryName,
                onValueChange = { pendingGalleryName = it.take(60) },
                label = "图片名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "取消",
                    onClick = {
                        pendingGallerySave = null
                        saveBitmap.recycle()
                        nav.pop()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "保存",
                    onClick = {
                        scope.launch {
                            val newName = runCatching {
                                model.store.uniqueName(pendingGalleryName.ifBlank { model.store.defaultName() })
                            }.getOrDefault(model.store.defaultName())
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { model.store.save(saveBitmap, newName) }.isSuccess
                            }
                            saveBitmap.recycle()
                            pendingGallerySave = null
                            model.bumpStore()
                            model.notify(if (ok) "已保存到图库：$newName" else "保存失败")
                            nav.pop()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    }
}

