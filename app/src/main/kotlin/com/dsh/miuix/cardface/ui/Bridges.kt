package com.dsh.miuix.cardface.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.dsh.miuix.cardface.core.BitmapPipeline
import com.dsh.miuix.cardface.core.AppPrefs
import com.dsh.miuix.cardface.core.CardSync
import com.dsh.miuix.cardface.core.ImageStore
import com.dsh.miuix.cardface.core.HookCache
import com.dsh.miuix.cardface.core.PackageOps
import com.dsh.miuix.cardface.core.WalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图像处理薄封装：界面里的所有高质量缩放 / 裁剪 / 圆角 / 标志合成只从这里进出，
 * 实际实现是 core/BitmapPipeline（EXIF 转正 → Lanczos3 缩放感知 + 线性光 → 圆角 → 标志）。
 *
 * 界面层只依赖这一个对象，后续换实现（例如更高分辨率或换缩放算法）不用动界面代码。
 */
object CardImaging {

    const val TARGET_W = 960
    const val TARGET_H = 606
    const val TARGET_RATIO = 160f / 101f
    const val CORNER_RADIUS_PX = 40f

    data class Options(
        val roundCorners: Boolean = true,
        val logoAsset: String? = null,
    )

    /** 解码一张原图（EXIF 转正 + inSampleSize 降采样），供裁剪页显示与保存使用。 */
    suspend fun loadSource(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching { BitmapPipeline.decode(context, uri, maxDim) }.getOrNull()
        }

    /** 裁剪 + 缩放到 960×606 + 圆角 + 卡面标志；失败返回 null，不抛异常。 */
    suspend fun buildCard(context: Context, src: Bitmap, crop: RectF?, opts: Options): Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching {
                BitmapPipeline.buildCard(
                    context = context,
                    src = src,
                    crop = crop,
                    opts = BitmapPipeline.Options(
                        targetW = TARGET_W,
                        targetH = TARGET_H,
                        roundRadiusPx = if (opts.roundCorners) CORNER_RADIUS_PX else 0f,
                        linearLight = true,
                        logoAsset = opts.logoAsset,
                    ),
                )
            }.getOrNull()
        }

    /** 阅读用的尺寸说明，设置页 / 编辑页展示同一份口径。 */
    const val OUTPUT_DESCRIPTION = "输出 960×606 PNG · Lanczos3 高质量缩放"
}

/**
 * 设备能力桥：打开卡包 / LSPosed、隐藏桌面图标、清缓存、复制文本。
 *
 * 部分能力在 core 里还没有对应实现（core/RootShell.kt、core/PackageOps.kt 由另一名同事落地），
 * 这里用 TODO 标出，接线时只需替换方法体。
 */
object DeviceBridge {

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    fun walletLabel(kind: WalletKind?): String = kind?.label ?: "未安装"

    fun openWallet(context: Context, kind: WalletKind?): Boolean = PackageOps.openWallet(context, kind)

    fun openLsposed(context: Context): Boolean = PackageOps.openLsposed(context)

    fun openQq(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage("com.tencent.mobileqq") ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    }

    /** 替换值是网络地址（http/https）还是图库文件名。 */
    fun isRemote(replacement: String?): Boolean =
        replacement?.startsWith("http://") == true || replacement?.startsWith("https://") == true

    fun copyText(context: Context, text: String, label: String = "卡面工坊"): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)

    /** 隐藏/恢复桌面图标：只动 activity-alias，LSPosed 的模块设置入口不受影响。 */
    fun setLauncherAliasVisible(context: Context, visible: Boolean): Boolean =
        PackageOps.setLauncherVisible(context, visible)

    /** 清图片缓存（Coil 磁盘缓存 / 缩略图都在 cacheDir 下）。 */
    fun clearImageCaches(context: Context): Boolean = HookCache.clearImageCaches(context)

    /** 清除 hook 点缓存并强杀卡包（下个进程会重新定位方法）。需要 root。 */
    fun clearHookCache(context: Context, kind: WalletKind?): Boolean =
        HookCache.clearHookCacheAndRestart(context, kind)

    /** 强制停止卡包后重新拉起，让卡包重新读替换图。 */
    fun restartWallet(context: Context, kind: WalletKind?): Boolean {
        // 先把替换图复制进卡包目录，再重启；复制是 root 同步操作（1~2 张图，约百毫秒）。
        runCatching { CardSync.syncAll(context, AppPrefs(context), ImageStore(context)) }
        return PackageOps.restartWallet(context, kind)
    }
}
