package com.dsh.miuix.cardface.core

/**
 * 把图库里的替换图同步到卡包私有目录（`/data/data/<卡包>/files/images/`）。
 *
 * 为什么需要：hook 在卡包进程里没法直接读我们 App 的私有图片（content:// 在部分卡包版本里
 * 会被 Glide 拒绝或受包可见性影响）。原版就是「复制到卡包目录 + 返回 file://」，
 * 这条路径最稳。复制用 root（SukiSU），失败时 hook 会自动退回 content://。
 */
object CardSync {

    /** 映射快照文件名：写在卡包 files/ 下，hook 进程冷启动时直接读，不依赖跨进程 provider。 */
    const val MAP_SNAPSHOT_NAME = "cardface.map"

    /** 把 URL -> 文件名 的映射写成快照文件复制到卡包私有目录（需要 root；失败不影响主流程）。 */
    fun writeMapSnapshot(context: android.content.Context, prefs: AppPrefs): Boolean {
        val kind = WalletApps.primary(context) ?: return false
        val entries = prefs.mappingPairs()
        val text = buildString {
            entries.forEach { (url, name) ->
                append(url).append('\t').append(name).append('\n')
            }
        }
        return try {
            val tmp = java.io.File(context.cacheDir, MAP_SNAPSHOT_NAME)
            tmp.writeText(text)
            val destDir = "/proc/1/root/data/data/${kind.packageName}/files"
            RootShell.exec(
                "mkdir -p \"$destDir\" && cp -f \"${tmp.absolutePath}\" \"$destDir/$MAP_SNAPSHOT_NAME\" && chmod 644 \"$destDir/$MAP_SNAPSHOT_NAME\"",
                timeoutMs = 8000,
            ).ok
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 卡包私有图片目录。
     *
     * 注意：SukiSU 的 su shell 有自己的 mount namespace，直接 `ls /data/data/<包名>` 会
     * 看不到卡包目录（只有 root 自己的几个包）；但 `/proc/1/root/data/data/<包名>` 是真实路径。
     * 所以复制操作统一走 `/proc/1/root` 前缀（在 su 里执行），hook 侧仍然用卡包自己的路径。
     */
    fun walletImagesDir(context: android.content.Context): String? {
        val kind = WalletApps.primary(context) ?: return null
        return "/proc/1/root/data/data/${kind.packageName}/files/images"
    }

    /** 复制单张图库图片到卡包目录；成功返回 true。 */
    fun copyImage(
        context: android.content.Context,
        store: ImageStore,
        name: String,
        timeoutMs: Long = 8000,
    ): Boolean {
        val dir = walletImagesDir(context) ?: return false
        val file = store.find(name) ?: return false
        val result = RootShell.copyIntoDir(file.absolutePath, dir, name, timeoutMs = timeoutMs)
        return result.ok
    }

    /**
     * 把所有「文件名」型映射同步一遍（启动时 / 重启卡包前调用）。
     * @return 成功复制的数量
     */
    fun syncAll(context: android.content.Context, prefs: AppPrefs, store: ImageStore): Int {
        val dir = walletImagesDir(context) ?: return 0
        var copied = 0
        prefs.mappedImageNames().forEach { name ->
            val file = store.find(name) ?: return@forEach
            if (RootShell.copyIntoDir(file.absolutePath, dir, name).ok) copied++
        }
        // 同时刷新映射快照：冷启动时 hook 先读它，避免第一屏回退默认卡面。
        writeMapSnapshot(context, prefs)
        return copied
    }
}
