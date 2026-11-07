package com.dsh.miuix.cardface.ui

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.dsh.miuix.cardface.core.AppPrefs
import com.dsh.miuix.cardface.core.ImageStore
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/** 全局动画开关（设置页「界面过渡动画」）；关闭后所有过渡立即失效。 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/** 全部页面路由（单 Activity + Compose 状态导航，不用 navigation 库）。 */
enum class Page(val route: String, val title: String) {
    Home("home", "卡面"),
    Gallery("gallery", "图库"),
    Settings("settings", "设置"),
    Editor("editor", "自定义卡面"),
    Crop("crop", "裁剪卡面"),
    Guide("guide", "使用说明"),
    Faq("faq", "常见问题"),
    About("about", "关于与捐赠"),
    Blacklist("blacklist", "卡面黑名单"),
    DevHook("hook", "自定义 hook 点"),
    DevConsole("console", "诊断终端"),
    ;

    val isTab: Boolean get() = this == Home || this == Gallery || this == Settings
}

/** 一个路由条目；[arg] 目前只用于 editor/{url}。 */
data class Route(val page: Page, val arg: String? = null) {
    fun encode(): String = if (arg.isNullOrEmpty()) page.name else page.name + ":" + Uri.encode(arg)

    companion object {
        fun decode(raw: String): Route {
            val idx = raw.indexOf(':')
            if (idx < 0) return Route(Page.valueOf(raw))
            return Route(Page.valueOf(raw.substring(0, idx)), Uri.decode(raw.substring(idx + 1)))
        }
    }
}

/** 最近一次导航动作，决定页面过渡动画的方向。 */
enum class NavDirection { Forward, Backward, Tab }

/** 页面栈。首页根路由；push/pop 都在这里，BackHandler 只在栈深 > 1 时拦截。 */
class NavState(initial: List<Route> = listOf(Route(Page.Home))) {

    var routes by mutableStateOf(initial)
        private set

    /** 只用于选过渡动画：前进右滑入、后退反向、页签淡入。不参与保存。 */
    var direction by mutableStateOf(NavDirection.Tab)
        private set

    val current: Route get() = routes.last()
    val canGoBack: Boolean get() = routes.size > 1

    fun push(route: Route) {
        direction = NavDirection.Forward
        routes = routes + route
    }

    fun pop(): Boolean {
        if (routes.size <= 1) return false
        direction = NavDirection.Backward
        routes = routes.dropLast(1)
        return true
    }

    /** 切换到某个底部页签（清空栈，避免返回键回到别的页签）。 */
    fun resetTo(page: Page) {
        direction = NavDirection.Tab
        routes = listOf(Route(page))
    }

    internal fun restore(list: List<Route>) {
        routes = if (list.isEmpty()) listOf(Route(Page.Home)) else list
    }

    companion object {
        private const val SEP = "\u001F"

        val Saver: Saver<NavState, String> = Saver(
            save = { state -> state.routes.joinToString(SEP) { it.encode() } },
            restore = { raw -> NavState().apply { restore(raw.split(SEP).map(Route::decode)) } },
        )
    }
}

/** 裁剪页的入口：编辑页（生成替换图）或图库页（存进图库）。 */
enum class CropTarget { Editor, Gallery }

/** 编辑页的草稿状态：跨页面（相册 → 裁剪 → 回到编辑）保留，所以放在 App 级。 */
class EditorDraft(val url: String) {

    /** 相册/裁剪流程得到的本地图；保存时交给 CardImaging 合成 960×606。 */
    var localUri by mutableStateOf<Uri?>(null)

    /** 裁剪页解码出来的原图（已按 EXIF 转正），编辑器预览和保存都用它，避免重复解码。 */
    var localBitmap by mutableStateOf<android.graphics.Bitmap?>(null)

    /** 本地图对应的裁剪区域（原图像素坐标）；null = 不裁剪（居中铺满）。 */
    var localCrop by mutableStateOf<RectF?>(null)

    /** 卡面标志的资源名（jingjinji / shanghai / qvanchengtong / yinchuan），null = 不叠加。 */
    var logoAsset by mutableStateOf<String?>(null)

    /** 圆角裁切开关。 */
    var roundCorners by mutableStateOf(true)

    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun clearPendingImage() {
        localUri = null
        localBitmap = null
        localCrop = null
        logoAsset = null
    }
}

/** 应用级状态（预授权：prefs 数据层 + 图库 + 页面栈 + 编辑草稿 + Snackbar）。 */
class AppModel(context: Context) {

    val context: Context = context.applicationContext
    val prefs = AppPrefs(this.context)
    val store = ImageStore(this.context)
    val snackbar = SnackbarHostState()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 短时提示：默认几秒的 Snackbar 会挡住底部按钮，这里统一压到最短。 */
    fun notify(message: String) {
        uiScope.launch {
            runCatching { snackbar.showSnackbar(message, duration = SnackbarDuration.Short) }
        }
    }

    var editor by mutableStateOf(EditorDraft(""))
        private set

    /** 图库文件的版本号：图库增删后自增，让列表刷新（prefs.revision 只覆盖设置/映射）。 */
    var storeRevision by mutableStateOf(0)
        private set

    fun bumpStore() {
        storeRevision++
    }

    /** 当前裁剪任务来自哪里。 */
    var cropTarget by mutableStateOf(CropTarget.Editor)

    /** 当前裁剪任务的源图。 */
    var cropUri by mutableStateOf<Uri?>(null)

    /** 图库多选批量裁剪队列。 */
    private val cropQueue = ArrayDeque<Uri>()

    /** 批量裁剪进度：总数 / 当前第几张（从 1 开始，0 表示不是批量）。 */
    var cropBatchTotal by mutableStateOf(0)
    var cropBatchIndex by mutableStateOf(0)

    fun startCropBatch(target: CropTarget, uris: List<Uri>) {
        cropTarget = target
        cropQueue.clear()
        cropQueue.addAll(uris)
        cropBatchTotal = uris.size
        cropBatchIndex = if (uris.isEmpty()) 0 else 1
        cropUri = uris.firstOrNull()
    }

    /** 处理完当前张后切到下一张；没有下一张返回 false。 */
    fun advanceCropBatch(): Boolean {
        if (cropQueue.isEmpty()) return false
        cropQueue.removeFirst()
        if (cropQueue.isEmpty()) {
            cropBatchTotal = 0
            cropBatchIndex = 0
            return false
        }
        cropBatchIndex++
        cropUri = cropQueue.first()
        return true
    }

    fun openEditor(url: String) {
        if (editor.url != url) editor = EditorDraft(url)
    }

    fun startCrop(target: CropTarget) {
        cropTarget = target
    }
}

/** 卡面标志可选项：资源名 → 中文名。 */
val CARD_LOGOS: List<Pair<String, String>> = listOf(
    "jingjinji" to "京津冀",
    "shanghai" to "上海",
    "qvanchengtong" to "泉城通",
    "yinchuan" to "银川",
)
