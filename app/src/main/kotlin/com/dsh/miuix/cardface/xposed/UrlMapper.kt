package com.dsh.miuix.cardface.xposed

/**
 * `replaceUrl` 的纯逻辑：不依赖任何 Android API，可在 JVM 单元测试里覆盖
 * （见 app/src/test/kotlin/com/dsh/miuix/cardface/xposed/UrlMapperTest.kt）。
 *
 * 行为对齐原版 DIY NFC卡面 v2.4 的 Hook.replaceUrl / updateCardUrlList：
 *  1. 小图过滤（仅 MIUI 且 show_all_cards=false）：w270h480 / door-card-img/logo / Mibi
 *  2. 黑名单：只影响「是否记入卡面列表」，不影响替换本身
 *  3. 测试模式：返回内置测试图
 *  4. settings 里查 url -> 替换值；查不到返回空串（保留原图）
 *  5. 替换值是 http(s) -> 原样返回
 *  6. 否则视为图库文件名 -> content://（不需要 root）
 *  7. 兼容模式且拿到卡包目录 -> 交给调用方 root 复制，返回 file:// 路径
 */
object UrlMapper {

    const val IMAGE_AUTHORITY = "com.dsh.miuix.cardface.images"

    /** 不需要 root 的图片地址前缀（ImageProvider，只读）。 */
    const val LOCAL_IMAGE_URI_PREFIX = "content://$IMAGE_AUTHORITY/images/"

    /** 测试模式用的内置测试图文件名（App 侧会在 files/images 下生成）。 */
    const val TEST_IMAGE_NAME = "cardface-test.png"
    const val TEST_IMAGE_URI = LOCAL_IMAGE_URI_PREFIX + TEST_IMAGE_NAME

    /** 原版过滤掉的三种小图标特征。 */
    const val SMALL_ICON_W270H480 = "w270h480"
    const val SMALL_ICON_DOOR_LOGO = "/door-card-img/logo/"
    const val SMALL_ICON_MIBI = "/Mibi/"

    /** replaceUrl 一次调用需要的外部状态快照。 */
    data class Env(
        /** 当前卡包是否为 MIUI 卡包（com.miui.tsmclient）。小图过滤只对 MIUI 生效。 */
        val isMiui: Boolean = false,
        /** settings.show_all_cards，true 时不过滤小图。 */
        val showAllCards: Boolean = false,
        /** settings.test_mode，返回固定测试图。 */
        val testMode: Boolean = false,
        /** settings.compat_copy，兼容模式（root 复制到卡包目录，返回 file://）。 */
        val compatMode: Boolean = false,
        /** card_urls.black_card_url_set。 */
        val blacklist: Set<String> = emptySet(),
        /** settings 里的 url -> 图库文件名 / http 地址。 */
        val mappings: Map<String, String> = emptyMap(),
        /** 卡包私有目录下的 images 目录，例如 /data/data/com.miui.tsmclient/files/images。 */
        val walletImagesDir: String = "",
        /** 卡包 files/images 下已经存在的文件名；存在则直接用 file://（与原版一致，最稳）。 */
        val existingWalletImages: Set<String> = emptySet(),
    )

    /** 兼容模式需要调用方把图库文件复制到卡包目录。 */
    data class CopyRequest(
        val localName: String,
        /** 目标绝对路径：<walletImagesDir>/<localName>。 */
        val destPath: String,
    )

    /**
     * @property url 要交给卡包加载的地址；空串表示「保留原图」。
     * @property trackUrl 是否把原始 URL 记录进 card_urls/all_card_url_set。
     * @property copy 非空表示需要 root 复制文件（兼容模式）。
     */
    data class Result(
        val url: String,
        val trackUrl: Boolean,
        val copy: CopyRequest? = null,
    ) {
        val replaced: Boolean get() = url.isNotEmpty()
    }

    fun isSmallIconUrl(url: String): Boolean =
        url.contains(SMALL_ICON_W270H480) ||
            url.contains(SMALL_ICON_DOOR_LOGO) ||
            url.contains(SMALL_ICON_MIBI)

    private val SIZE_SEGMENT = Regex("/w\\d+h\\d+(?=[/?#]|$)")

    /** 去掉 URL 里的 `w1080h1920` 尺寸段，用于缩略图/原图变体之间做兜底匹配。 */
    fun normalizedUrl(url: String): String = url.replace(SIZE_SEGMENT, "")

    /** 先精确查映射，再按去掉尺寸段的 URL 兜底匹配。 */
    fun lookupMapping(url: String, mappings: Map<String, String>): String? {
        mappings[url]?.let { return it }
        val normalized = normalizedUrl(url)
        if (normalized == url) return null
        return mappings.entries.firstOrNull { normalizedUrl(it.key) == normalized }?.value
    }

    fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    /**
     * 图库文件名是否可安全拼进 content:// / 文件路径。
     * 拒绝空串、路径分隔符、..、控制字符。
     */
    fun isSafeImageName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name == "." || name == ".." || name.contains("..")) return false
        return name.none { it.code < 0x20 }
    }

    /** 是否把 URL 记入卡面列表（小图 / 黑名单不记）。 */
    fun shouldTrack(url: String, env: Env): Boolean {
        if (env.isMiui && !env.showAllCards && isSmallIconUrl(url)) return false
        if (env.blacklist.contains(url)) return false
        return url.isNotEmpty()
    }

    /** replaceUrl 纯逻辑；空 url 结果表示保留原图。 */
    fun map(url: String, env: Env): Result {
        if (url.isEmpty()) return Result("", trackUrl = false)
        val track = shouldTrack(url, env)

        // 1. 测试模式优先，保证「打开测试模式 -> 一定能看到测试图」。
        if (env.testMode) {
            return Result(TEST_IMAGE_URI, track)
        }

        // 2. 查映射（含缩略图 URL 兜底）；没有映射 -> 保留原图。
        val replacement = lookupMapping(url, env.mappings)
        if (replacement.isNullOrEmpty()) return Result("", track)

        // 3. 网络地址原样交给卡包。
        if (isHttpUrl(replacement)) return Result(replacement, track)

        // 4. 文件名：优先 content://（不需要 root）。
        if (!isSafeImageName(replacement)) {
            // 可能是脏数据（旧版本写入的路径），不要拼出越权路径。
            return Result("", track)
        }

        // 5. 卡包目录里已经有这张图：直接 file://（最稳，原版就是这么干的）。
        if (env.walletImagesDir.isNotEmpty() && env.existingWalletImages.contains(replacement)) {
            return Result("file://${env.walletImagesDir.trimEnd('/')}/$replacement", track)
        }

        // 6. 兼容模式：这次调用时复制到卡包 files/images 并返回 file://。
        if (env.compatMode && env.walletImagesDir.isNotEmpty()) {
            val dir = env.walletImagesDir.trimEnd('/')
            return Result(
                url = "file://$dir/$replacement",
                trackUrl = track,
                copy = CopyRequest(localName = replacement, destPath = "$dir/$replacement"),
            )
        }

        return Result(LOCAL_IMAGE_URI_PREFIX + replacement, track)
    }
}
