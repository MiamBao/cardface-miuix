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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
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
    val route = nav.current
    val currentPage = route.page

    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    // 首次启动：直接进引导页（首页作为返回栈底，返回键不会退出应用）。
    LaunchedEffect(Unit) {
        if (model.prefs.firstRun && nav.routes.size == 1) {
            nav.push(Route(Page.Guide))
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
            if (currentPage.isTab) {
                FloatingNavigationBar() {
                    FloatingTabItem(
                        selected = currentPage == Page.Home,
                        onClick = { nav.resetTo(Page.Home) },
                        icon = MiuixIcons.Home,
                        label = "卡面",
                    )
                    FloatingTabItem(
                        selected = currentPage == Page.Gallery,
                        onClick = { nav.resetTo(Page.Gallery) },
                        icon = MiuixIcons.Photos,
                        label = "图库",
                    )
                    FloatingTabItem(
                        selected = currentPage == Page.Settings,
                        onClick = { nav.resetTo(Page.Settings) },
                        icon = MiuixIcons.Settings,
                        label = "设置",
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
            modifier = Modifier.fillMaxSize(),
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
                    onOpenGuide = { nav.push(Route(Page.Guide)) },
                )

                Page.Gallery -> GalleryScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onPickForCrop = { uris ->
                        model.startCropBatch(CropTarget.Gallery, uris)
                        if (uris.isNotEmpty()) nav.push(Route(Page.Crop))
                    },
                )

                Page.Settings -> SettingsScreen(
                    model = model,
                    innerPadding = innerPadding,
                    onOpenBlacklist = { nav.push(Route(Page.Blacklist)) },
                    onOpenGuide = { nav.push(Route(Page.Guide)) },
                    onOpenFaq = { nav.push(Route(Page.Faq)) },
                    onOpenAbout = { nav.push(Route(Page.About)) },
                    onOpenDevHook = { nav.push(Route(Page.DevHook)) },
                    onOpenDevConsole = { nav.push(Route(Page.DevConsole)) },
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
                                if (card == null) {
                                    model.snackbar.showSnackbar("保存失败：图片合成出错")
                                } else {
                                    val name = model.store.uniqueName(model.store.defaultName())
                                    val saved = withContext(Dispatchers.IO) {
                                        runCatching { model.store.save(card, name) }.isSuccess
                                    }
                                    model.bumpStore()
                                    model.snackbar.showSnackbar(
                                        if (saved) "已保存到图库：$name" else "保存失败",
                                    )
                                }
                                // 还有下一张就留在裁剪页继续，全部处理完再返回。
                                if (!model.advanceCropBatch()) {
                                    nav.pop()
                                }
                            }
                        }
                    },
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
                    onOpenGuide = { nav.push(Route(Page.Guide)) },
                    onOpenFaq = { nav.push(Route(Page.Faq)) },
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
    }
}

