package com.dsh.miuix.cardface.core

import android.content.Context
import java.io.File

/**
 * hook 点缓存与图片缓存清理。
 *
 * hook 点缓存是卡包进程 files/ 下的两行文本（类名、方法名），
 * 本模块缓存文件名：cardface.method.0.txt / cardface.method.1.txt / cardface.method.island.txt，
 * 同时兼容原版 DIY NFC卡面 的 zhizi42.diycard.* 文件名。
 *
 * 删除卡包私有目录里的文件需要 root；没有 root 时返回 false 并给出原因，不崩溃。
 */
object HookCache {

    private const val TAG = "cardface.cache"

    /** 本模块写在卡包 files/ 下的缓存文件。 */
    val CACHE_FILE_NAMES = listOf(
        "cardface.method.0.txt",
        "cardface.method.1.txt",
        "cardface.method.island.txt",
    )

    /** 原版 App 留下的缓存文件，一并清理。 */
    val LEGACY_FILE_NAMES = listOf(
        "zhizi42.diycard.method.txt",
        "zhizi42.diycard.method.1.txt",
        "zhizi42.diycard.HyperSuperLand.method.txt",
    )

    data class HookCacheInfo(
        val packageName: String,
        val filesDir: String,
        val existingFiles: List<String>,
        val rootAvailable: Boolean,
    )

    fun walletFilesDir(packageName: String): String = "/data/data/$packageName/files"

    fun cachePaths(packageName: String): List<String> {
        val dir = walletFilesDir(packageName)
        return (CACHE_FILE_NAMES + LEGACY_FILE_NAMES).map { "$dir/$it" }
    }

    /** 列出卡包 files/ 下现有的 hook 点缓存（需要 root 才能读别的 App 私有目录）。 */
    fun inspect(packageName: String): HookCacheInfo {
        val dir = walletFilesDir(packageName)
        val result = RootShell.listFiles(dir)
        if (!result.ok) {
            return HookCacheInfo(packageName, dir, emptyList(), rootAvailable = false)
        }
        val known = (CACHE_FILE_NAMES + LEGACY_FILE_NAMES).toSet()
        val existing = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in known }
            .toList()
        return HookCacheInfo(packageName, dir, existing, rootAvailable = true)
    }

    /**
     * 清除 hook 点缓存（调用方通常还会强杀卡包，见 [clearHookCacheAndRestart]）。
     * @return true 表示所有目标文件都已删除（原本就不存在也算成功）。
     */
    fun clearHookCache(packageName: String): Boolean = clearHookCacheDetailed(packageName).ok

    fun clearHookCacheDetailed(packageName: String): RootShell.Result {
        val paths = cachePaths(packageName)
        val result = RootShell.deleteFiles(paths)
        if (!result.ok) {
            android.util.Log.w(
                TAG,
                "clearHookCache($packageName) failed rc=${result.exitCode} rootUnavailable=${result.rootUnavailable}",
            )
        } else {
            android.util.Log.i(TAG, "hook cache cleared for $packageName: ${paths.joinToString()}")
        }
        return result
    }

    /** 清缓存 + 强杀卡包（下个进程会重新用 DexKit 定位并写缓存）。 */
    fun clearHookCacheAndRestart(context: Context, kind: WalletKind?): Boolean {
        val pkg = kind?.packageName ?: return false
        val cleared = clearHookCache(pkg)
        PackageOps.forceStopWallet(context, kind)
        return cleared
    }

    /** 清本应用图片缓存（Coil 磁盘缓存 / 缩略图 / 裁剪临时文件都在 cacheDir 下）。 */
    fun clearImageCaches(context: Context): Boolean = try {
        context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        true
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "clearImageCaches failed: $t")
        false
    }

    data class ClearSummary(
        val hookCacheCleared: Boolean,
        val imageCacheCleared: Boolean,
        val rootAvailable: Boolean,
        val message: String,
    )

    /** 设置页「清除卡面缓存」一条龙。 */
    fun clearAll(context: Context, kind: WalletKind?): ClearSummary {
        val rootAvailable = RootShell.isAvailable()
        val hookCleared = if (kind == null) {
            false
        } else {
            clearHookCacheAndRestart(context, kind)
        }
        val imageCleared = clearImageCaches(context)
        val message = buildString {
            if (kind == null) append("未安装卡包，跳过 hook 缓存；") else append("hook 缓存已清除；")
            if (!rootAvailable) append("未授予 root，卡包私有目录无法清理；")
            append(if (imageCleared) "图片缓存已清除" else "图片缓存清理失败")
        }
        return ClearSummary(hookCleared, imageCleared, rootAvailable, message)
    }

    /** 本应用图库目录（可作为「图片缓存」清理的补充）。 */
    fun galleryDir(context: Context): File = File(context.filesDir, "images")
}
