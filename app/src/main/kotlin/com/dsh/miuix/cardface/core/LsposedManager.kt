package com.dsh.miuix.cardface.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * 找到本机的 LSPosed 管理器。
 *
 * 不能用「有 `.XposedService` provider」判断管理器宿主：libxposed 每个模块 App 都会声明
 * 自己的 `<包名>.XposedService` provider，会误命中普通模块 App。
 *
 * 真正的判据是管理器活动 `org.lsposed.manager.ui.activity.MainActivity`；
 * 本机是寄生/隐藏模式，管理器活动只在宿主进程内，第三方查不到也打不开，所以失败时
 * 只做引导、绝不猜测启动别的 App。
 *
 * 性能：全量 PackageManager 查询很贵，这里做 30s 缓存，并且只在少数候选包上查 activities。
 */
object LsposedManager {

    private const val TAG = "cardface.lsposed"
    private const val MANAGER_PACKAGE = "org.lsposed.manager"
    private const val MANAGER_ACTIVITY = "org.lsposed.manager.ui.activity.MainActivity"
    private const val SERVICE_SUFFIX = ".XposedService"
    private const val CACHE_TTL_MS = 30_000L

    data class Info(
        val packageName: String,
        val label: String,
        /** 管理器活动全名；找不到具体活动时为 null。 */
        val activityName: String?,
    )

    /** 扫描出来的候选包（纯数据，便于单元测试）。 */
    data class Candidate(
        val packageName: String,
        val label: String,
        val activityNames: List<String> = emptyList(),
        val providerAuthorities: List<String> = emptyList(),
    ) {
        val managerActivity: String?
            get() = activityNames.firstOrNull { name ->
                name == MANAGER_ACTIVITY ||
                    name.endsWith(".lsposed.manager.ui.activity.MainActivity") ||
                    name.contains("org.lsposed.manager")
            }

        val hasXposedServiceProvider: Boolean
            get() = providerAuthorities.any { it.endsWith(SERVICE_SUFFIX) }
    }

    private data class ScanResult(val info: Info?, val trace: Boolean, val at: Long)

    @Volatile
    private var cache: ScanResult? = null

    /** 外观/管理器状态变化后可手动失效缓存（诊断按钮用）。 */
    fun invalidate() {
        cache = null
    }

    /** 纯选择逻辑：只有带管理器活动的包才算管理器，普通 Xposed 模块不计。 */
    fun selectManager(candidates: List<Candidate>, selfPackage: String): Candidate? {
        val usable = candidates.filter { it.packageName != selfPackage }
        usable.firstOrNull { it.packageName == MANAGER_PACKAGE }?.let { return it }
        return usable.firstOrNull { it.managerActivity != null }
    }

    fun find(context: Context): Info? = scanCached(context).info

    fun isInstalled(context: Context): Boolean = find(context) != null

    /** 是否有可被第三方直接打开的管理器（普通安装）。 */
    fun canLaunch(context: Context): Boolean = find(context)?.packageName == MANAGER_PACKAGE

    /**
     * LSPosed 框架痕迹：本机存在模块 provider，或 hook 已上报过。
     * 说明「LSPosed 在运行」，不代表能直接打开管理器。
     */
    fun hasFrameworkTrace(context: Context, lastHookAt: Long): Boolean =
        lastHookAt > 0L || scanCached(context).trace

    fun open(context: Context): Boolean {
        val info = find(context) ?: return false
        val pm = context.packageManager
        if (info.packageName != MANAGER_PACKAGE) return false
        val launch = pm.getLaunchIntentForPackage(MANAGER_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, launch)
    }

    /** 诊断输出（会实时全量扫描一次，不走缓存）。 */
    fun debugReport(context: Context): String {
        val started = System.currentTimeMillis()
        val scan = doScan(context)
        val elapsed = System.currentTimeMillis() - started
        return buildString {
            appendLine("扫描耗时：${elapsed}ms")
            appendLine("管理器：")
            if (scan.info == null) {
                appendLine("  （未找到可直接启动的管理器）")
            } else {
                appendLine("  ${scan.info.label} · ${scan.info.packageName}")
                appendLine("  activity=${scan.info.activityName ?: "(launcher)"}")
            }
            append("框架痕迹：")
            appendLine(if (scan.trace) "有（发现 .XposedService provider 或 hook 上报）" else "无")
        }
    }

    // ---------- 内部 ----------

    private fun scanCached(context: Context): ScanResult {
        val now = System.currentTimeMillis()
        cache?.let { if (now - it.at < CACHE_TTL_MS) return it }
        return doScan(context).also { cache = it }
    }

    private fun doScan(context: Context): ScanResult {
        val pm = context.packageManager
        // 1) 只查 providers（比同时查所有包的 activities 便宜很多），找 libxposed 模块痕迹。
        val moduleProviderPackages = try {
            pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
                .filterNot { it.packageName == context.packageName }
                .filter { pkg ->
                    pkg.providers.orEmpty().any { it.authority?.endsWith(SERVICE_SUFFIX) == true }
                }
                .map { it.packageName }
        } catch (t: Throwable) {
            Log.w(TAG, "getInstalledPackages(GET_PROVIDERS) failed: $t")
            emptyList()
        }
        val trace = moduleProviderPackages.isNotEmpty()

        // 2) 只在少数候选（管理器包 / 系统 shell 宿主 / 模块包）上查 activities。
        val candidateNames = LinkedHashSet<String>()
        if (isPackageInstalled(context, MANAGER_PACKAGE)) candidateNames += MANAGER_PACKAGE
        candidateNames += "com.android.shell"
        candidateNames += moduleProviderPackages.take(32)

        val candidates = candidateNames.mapNotNull { name ->
            try {
                val pkg = pm.getPackageInfo(name, PackageManager.GET_ACTIVITIES)
                val label = pkg.applicationInfo?.let { app ->
                    runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(name)
                } ?: name
                Candidate(
                    packageName = name,
                    label = label,
                    activityNames = pkg.activities.orEmpty().map { it.name },
                )
            } catch (_: Throwable) {
                null
            }
        }
        val manager = selectManager(candidates, context.packageName)
        val info = manager?.let {
            Info(
                packageName = it.packageName,
                label = it.label,
                activityName = it.managerActivity
                    ?: if (it.packageName == MANAGER_PACKAGE) MANAGER_ACTIVITY else null,
            )
        }
        return ScanResult(info, trace || info != null, System.currentTimeMillis())
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "start manager failed: $t")
        false
    }
}
