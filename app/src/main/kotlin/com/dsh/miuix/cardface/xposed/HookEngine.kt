package com.dsh.miuix.cardface.xposed

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import com.dsh.miuix.cardface.core.RootShell
import java.lang.reflect.Method

/**
 * 单个卡包进程里的 hook 运行时：
 *  - 读设置（framework remote prefs + 模块 provider 合并）
 *  - 定位 hook 点并安装拦截器（DexKit / 缓存 / 自定义 / 反射兜底）
 *  - 执行 replaceUrl（UrlMapper）+ 写回 card_urls / last_hook_at
 */
class HookEngine(
    private val module: XposedModule,
    private val osType: Int,
    private val classLoader: ClassLoader,
    private val appInfo: ApplicationInfo,
) {

    private val settingsRepo = SettingsRepository(module)
    private val locator = MethodLocator(osType, classLoader, appInfo)

    private val walletPackage: String = MethodLocator.MIUI_PACKAGE

    private val walletDataDir: String = appInfo.dataDir ?: "/data/data/$walletPackage"

    private val walletImagesDir: String = "$walletDataDir/files/images"

    private val moduleDataDir: String by lazy {
        val pkg = module.moduleApplicationInfo.packageName
        "/data/data/$pkg"
    }

    @Volatile
    private var installedCount = 0

    /** 已安装的 hook 数量（供入口判断是否需要重试）。 */
    val installedHooks: Int get() = installedCount

    @Volatile
    private var contextReady = false

    @Volatile
    private var rootDenied = false

    private var lastCompatWarnMs = 0L

    private val contextHookers: MutableList<XposedInterface.HookHandle> = ArrayList()

    /** 自定义 hook 点需要 provider 里的开关；Context 还没到时先记下，拿到 Context 再装。 */
    @Volatile
    private var pendingCustomHook = false

    fun start() {
        XLog.i("cardface hook starting: osType=$osType package=$walletPackage api=${module.apiVersion}")
        settingsRepo.init()

        // 1. 先拿 Context：provider 读写（last_hook_at / card_urls / 设置）需要。
        installContextHook()

        // 2. 自动 hook 点永远装（自愈；自定义 hook 点是额外的兜底，不冲突——两者都走 replaceUrl）。
        val settings = settingsRepo.snapshot()
        installAutoHooks(settings)

        // 3. 自定义 hook 点：settings 里的 class/method 配置。
        val customConfigured = settings.customClass.isNotEmpty() && settings.customMethod.isNotEmpty()
        if (customConfigured) {
            if (contextReady) {
                installCustomHook(settings)
            } else {
                pendingCustomHook = true
            }
        }
        locator.close()

        XLog.i("cardface hook installed hooks=$installedCount for $walletPackage")
        if (installedCount > 0) {
            settingsRepo.touchLastHookAt(force = true)
        } else {
            XLog.e("no hook point installed for $walletPackage, check settings / DexKit / LSPosed scope")
        }
    }

    // ---------- Context 捕获 ----------

    private fun installContextHook() {
        tryHook("Application.attach") {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }?.let { return }
        tryHook("Instrumentation.callApplicationOnCreate") {
            Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        }?.let { return }
        tryHook("ContextWrapper.attachBaseContext") {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        }
        if (!contextReady) {
            XLog.w("failed to install any context hook; replaceUrl writes will be skipped")
        }
    }

    private fun tryHook(label: String, executable: () -> Method): Unit? {
        return try {
            val method = executable()
            val handle = module.hook(method).intercept(
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try {
                            if (label.startsWith("ContextWrapper")) {
                                val target = chain.thisObject
                                if (target is Application) {
                                    (chain.getArg(0) as? Context)?.let { onContextReady(it) }
                                }
                            } else {
                                (chain.getArg(0) as? Context)?.let { onContextReady(it) }
                            }
                        } catch (t: Throwable) {
                            XLog.w("$label: context capture failed", t)
                        }
                        return result
                    }
                },
            )
            contextHookers.add(handle)
            XLog.i("context hook installed: $label")
            Unit
        } catch (t: Throwable) {
            XLog.w("$label not available: $t")
            null
        }
    }

    private fun onContextReady(context: Context) {
        if (contextReady) return
        contextReady = true
        val appContext = context.applicationContext ?: context
        // 不要阻塞卡包的启动线程：provider 初始化 / DexKit 查询 / 写回都放后台线程。
        try {
            Thread({
                try {
                    settingsRepo.attachContext(appContext)
                } catch (t: Throwable) {
                    XLog.w("attach context failed", t)
                }
                if (pendingCustomHook) {
                    pendingCustomHook = false
                    try {
                        val settings = settingsRepo.snapshot()
                        if (settings.customClass.isNotEmpty() && settings.customMethod.isNotEmpty()) {
                            installCustomHook(settings)
                            locator.close()
                        }
                    } catch (t: Throwable) {
                        XLog.w("install pending custom hook failed", t)
                    }
                }
                if (installedCount > 0) settingsRepo.touchLastHookAt(force = true)
            }, "cardface-context").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            XLog.w("start context thread failed", t)
        }
    }

    // ---------- hook 点安装 ----------

    private fun installCustomHook(settings: HookSettings) {
        if (settings.customClass.isEmpty() || settings.customMethod.isEmpty()) return
        val method = locator.findCustom(settings.customClass, settings.customMethod, settings.customMethodArg)
        if (method == null) {
            XLog.e(
                "custom hook point failed: ${settings.customClass}#${settings.customMethod}" +
                    " arg=${settings.customMethodArg}",
            )
            return
        }
        install(
            method = method,
            label = "custom(-1)",
            loadUrlFromArg = settings.loadUrlFromArg,
            replaceUrlToArg = settings.replaceUrlToArg,
            trackNewUrls = true,
        )
    }

    private fun installAutoHooks(settings: HookSettings) {
        val slots = ArrayList<Int>()
        slots.add(MethodLocator.SLOT_URL_FROM_ARG)
        slots.add(MethodLocator.SLOT_REPLACE_ARG)
        if (settings.superLand) slots.add(MethodLocator.SLOT_SUPER_ISLAND)
        val found = locator.findTargets(slots)
        for (slot in slots) {
            if (slot == MethodLocator.SLOT_REPLACE_ARG) {
                // Glide RequestManager 可能有多个 String 重载：全部装上，哪个被调用都能替换。
                val candidates = locator.findCandidates(slot)
                if (candidates.isNotEmpty()) {
                    val (loadUrlFromArg, replaceUrlToArg) = flagsForSlot(slot)
                    candidates.forEachIndexed { index, method ->
                        // 通用 Glide 加载：只做替换，不记录 URL（避免把图标/横幅当成卡面）。
                        install(method, label = "slot=$slot/$index", loadUrlFromArg, replaceUrlToArg, trackNewUrls = false)
                    }
                }
                continue
            }
            val method = found[slot] ?: continue
            val (loadUrlFromArg, replaceUrlToArg) = flagsForSlot(slot)
            install(method, label = "slot=$slot", loadUrlFromArg, replaceUrlToArg, trackNewUrls = true)
        }
    }

    /**
     * 原版 startHook 的开关推导：
     *  - MIUI #0  ：从参数读 URL，直接返回替换值（load=true,  replaceArg=false）
     *  - MIUI #1  ：从参数读 URL，替换参数（load=true,  replaceArg=true）
     *  - MIUI #10 ：从返回值读 URL（load=false, replaceArg=false）
     */
    private fun flagsForSlot(slot: Int): Pair<Boolean, Boolean> = when {
        slot == MethodLocator.SLOT_URL_FROM_ARG -> true to false
        slot == MethodLocator.SLOT_REPLACE_ARG -> true to true
        slot == MethodLocator.SLOT_SUPER_ISLAND -> false to false
        else -> true to false
    }

    private fun install(
        method: Method,
        label: String,
        loadUrlFromArg: Boolean,
        replaceUrlToArg: Boolean,
        trackNewUrls: Boolean,
    ) {
        try {
            module.hook(method).intercept(UrlHooker(loadUrlFromArg, replaceUrlToArg, trackNewUrls))
            installedCount++
            XLog.i(
                "hooked ${method.declaringClass.name}#${method.name} ($label, " +
                    "loadUrlFromArg=$loadUrlFromArg, replaceUrlToArg=$replaceUrlToArg)",
            )
        } catch (t: Throwable) {
            XLog.e("hook failed: ${method.declaringClass.name}#${method.name}", t)
        }
    }

    /** 与原版 Hook.MyHooker 完全一致的两种模式。 */
    private inner class UrlHooker(
        private val loadUrlFromArg: Boolean,
        private val replaceUrlToArg: Boolean,
        /** 是否把这个 URL 记入卡面列表；只有「卡面专用」hook 点才记录，通用 Glide 加载不记。 */
        private val trackNewUrls: Boolean,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (loadUrlFromArg) {
                // 参数可能是 String / Uri / File（Glide 的 load(Object) 重载）。
                val raw = try {
                    chain.getArg(0)
                } catch (t: Throwable) {
                    null
                }
                val url = when (raw) {
                    is String -> raw
                    is android.net.Uri -> raw.toString()
                    is java.io.File -> raw.absolutePath
                    else -> null
                }
                val replaced = if (url == null) "" else replaceUrl(url, trackNewUrls)
                if (replaced.isEmpty()) return chain.proceed()
                if (!replaceUrlToArg) return replaced
                // 按原参数类型构造新参数，避免类型不匹配。
                val newArg: Any = when (raw) {
                    is android.net.Uri -> android.net.Uri.parse(replaced)
                    is java.io.File -> java.io.File(replaced.removePrefix("file://"))
                    else -> replaced
                }
                return chain.proceed(arrayOf<Any?>(newArg))
            }
            // 从返回值读 URL 模式：proceed 的异常保持原样抛出（模块只处理自己的异常）。
            val result = chain.proceed()
            val url = result as? String ?: return result
            val replaced = replaceUrl(url, trackNewUrls)
            return if (replaced.isEmpty()) result else replaced
        }
    }

    // ---------- 核心替换 ----------

    fun replaceUrl(url: String, trackNew: Boolean = true): String {
        return try {
            val settings = settingsRepo.snapshot()
            val env = UrlMapper.Env(
                isMiui = osType == MethodLocator.OS_MIUI,
                showAllCards = settings.showAllCards,
                testMode = settings.testMode,
                compatMode = settings.compatMode,
                blacklist = settingsRepo.blacklist(),
                mappings = settingsRepo.mappings(),
                walletImagesDir = walletImagesDir,
                existingWalletImages = walletImageNames(),
            )
            val result = UrlMapper.map(url, env)

            if (trackNew && result.trackUrl && !isOwnReplacementUrl(url, settingsRepo.mappings().values)) {
                settingsRepo.addCardUrl(url)
            }

            var finalUrl = result.url
            val copy = result.copy
            if (copy != null) {
                if (copyToWallet(copy)) {
                    XLog.d("compat mode copied ${copy.localName} -> ${copy.destPath}")
                } else {
                    // root 不可用时退回 content://，保证卡面仍然能替换。
                    finalUrl = UrlMapper.LOCAL_IMAGE_URI_PREFIX + copy.localName
                    XLog.w("compat copy failed, fallback to content:// for ${copy.localName}")
                }
            }

            settingsRepo.touchLastHookAt()
            XLog.i(
                "load image url: $url -> " +
                    (if (finalUrl.isEmpty()) "<keep original>" else finalUrl) +
                    " (track=${result.trackUrl})",
            )
            finalUrl
        } catch (t: Throwable) {
            XLog.e("replaceUrl error for $url", t)
            ""
        }
    }

    @Volatile
    private var walletImageCacheAt = 0L

    @Volatile
    private var walletImageCache: Set<String> = emptySet()

    /** 卡包 files/images 下已存在的文件名（2s 缓存，避免每次图片加载都 list 目录）。 */
    private fun walletImageNames(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - walletImageCacheAt < 2000L) return walletImageCache
        val names = try {
            File(walletImagesDir).list()?.toSet().orEmpty()
        } catch (_: Throwable) {
            emptySet()
        }
        walletImageCache = names
        walletImageCacheAt = now
        return names
    }

    /** 卡包加载我们自己复制进去的替换图时会产生 file:// URL；这种不是真实卡面，不记录。 */
    private fun isOwnReplacementUrl(url: String, replacementNames: Collection<String>): Boolean {
        val name = url.substringAfterLast('/')
        // 我们自己生成/复制的替换图：content:// 或 file:// 都不是真实卡面。
        if (replacementNames.contains(name)) return true
        if (name.matches(Regex("^card-\\d{8}-\\d{6}\\.png$"))) return true
        return url.contains("com.dsh.miuix.cardface")
    }

    private fun copyToWallet(copy: UrlMapper.CopyRequest): Boolean {
        if (rootDenied) return false
        val source = "$moduleDataDir/files/images/${copy.localName}"
        val result = RootShell.copyIntoDir(source, walletImagesDir, copy.localName, timeoutMs = 5000)
        if (result.ok) return true
        // 兼容模式的复制由卡包进程触发 root 请求很不友好；失败一次后就本进程内不再重试，
        // 直接退回 content:// （不需要 root，卡面替换依然生效）。
        rootDenied = true
        val now = System.currentTimeMillis()
        if (now - lastCompatWarnMs > 60_000) {
            lastCompatWarnMs = now
            XLog.w(
                "compat copy failed (rc=${result.exitCode}), fallback to content://; " +
                    "建议关掉兼容模式，或在 App 侧用 root 把图复制进卡包目录",
                result.stderr.take(200).takeIf { it.isNotBlank() }?.let { RuntimeException(it) },
            )
        }
        return false
    }
}
