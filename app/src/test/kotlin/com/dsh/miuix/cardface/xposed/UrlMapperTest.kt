package com.dsh.miuix.cardface.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UrlMapper（replaceUrl 纯逻辑）的 JVM 单元测试。
 * 不依赖 Android，`gradlew :app:testDebugUnitTest` 可直接跑。
 */
class UrlMapperTest {

    private fun env(
        isMiui: Boolean = true,
        showAllCards: Boolean = false,
        testMode: Boolean = false,
        compatMode: Boolean = false,
        blacklist: Set<String> = emptySet(),
        mappings: Map<String, String> = emptyMap(),
        walletImagesDir: String = "",
    ) = UrlMapper.Env(
        isMiui = isMiui,
        showAllCards = showAllCards,
        testMode = testMode,
        compatMode = compatMode,
        blacklist = blacklist,
        mappings = mappings,
        walletImagesDir = walletImagesDir,
    )

    // ---------- 小图过滤 ----------

    @Test
    fun `small icon urls are not tracked on miui`() {
        val patterns = listOf(
            "https://cdn.example.com/card/w270h480/xx.png",
            "https://cdn.example.com/door-card-img/logo/abc.png",
            "https://cdn.example.com/Mibi/abc.png",
        )
        for (url in patterns) {
            val result = UrlMapper.map(url, env())
            assertFalse("$url should not be tracked", result.trackUrl)
        }
    }

    @Test
    fun `small icon urls are tracked when show_all_cards is on`() {
        val url = "https://cdn.example.com/card/w270h480/xx.png"
        assertTrue(UrlMapper.map(url, env(showAllCards = true)).trackUrl)
    }

    @Test
    fun `small icon still gets replaced when mapping exists`() {
        val url = "https://cdn.example.com/card/w270h480/xx.png"
        val result = UrlMapper.map(
            url,
            env(mappings = mapOf(url to "mine.png")),
        )
        assertEquals(UrlMapper.LOCAL_IMAGE_URI_PREFIX + "mine.png", result.url)
        assertFalse(result.trackUrl)
    }

    // ---------- 黑名单 ----------

    @Test
    fun `blacklisted url is not tracked but replacement still works`() {
        val url = "https://cdn.example.com/card/big.png"
        val result = UrlMapper.map(
            url,
            env(blacklist = setOf(url), mappings = mapOf(url to "mine.png")),
        )
        assertFalse(result.trackUrl)
        assertEquals(UrlMapper.LOCAL_IMAGE_URI_PREFIX + "mine.png", result.url)
    }

    @Test
    fun `normal url is tracked`() {
        val url = "https://cdn.example.com/card/big.png"
        assertTrue(UrlMapper.map(url, env()).trackUrl)
    }

    // ---------- 测试模式 ----------

    @Test
    fun `test mode returns builtin test image`() {
        val result = UrlMapper.map("https://cdn.example.com/card/big.png", env(testMode = true))
        assertEquals(UrlMapper.TEST_IMAGE_URI, result.url)
        assertTrue(result.trackUrl)
    }

    @Test
    fun `test mode wins over mapping`() {
        val url = "https://cdn.example.com/card/big.png"
        val result = UrlMapper.map(url, env(testMode = true, mappings = mapOf(url to "mine.png")))
        assertEquals(UrlMapper.TEST_IMAGE_URI, result.url)
    }

    // ---------- 映射查找 ----------

    @Test
    fun `no mapping keeps original image`() {
        val result = UrlMapper.map("https://cdn.example.com/card/big.png", env())
        assertEquals("", result.url)
        assertFalse(result.replaced)
    }

    @Test
    fun `blank mapping value keeps original image`() {
        val url = "https://cdn.example.com/card/big.png"
        assertEquals("", UrlMapper.map(url, env(mappings = mapOf(url to ""))).url)
    }

    @Test
    fun `http replacement passes through untouched`() {
        val url = "https://cdn.example.com/card/big.png"
        val remote = "http://example.com/replacement.png"
        val result = UrlMapper.map(url, env(mappings = mapOf(url to remote)))
        assertEquals(remote, result.url)
        assertNull(result.copy)
    }

    @Test
    fun `https replacement passes through untouched`() {
        val url = "https://cdn.example.com/card/big.png"
        val remote = "https://example.com/replacement.png"
        assertEquals(remote, UrlMapper.map(url, env(mappings = mapOf(url to remote))).url)
    }

    // ---------- 本地文件名 ----------

    @Test
    fun `local file name maps to content uri without root`() {
        val url = "https://cdn.example.com/card/big.png"
        val result = UrlMapper.map(url, env(mappings = mapOf(url to "card-20260101-120000.png")))
        assertEquals(
            "content://com.dsh.miuix.cardface.images/images/card-20260101-120000.png",
            result.url,
        )
        assertNull("默认不需要复制文件", result.copy)
    }

    @Test
    fun `compat mode maps to wallet file path and requests copy`() {
        val url = "https://cdn.example.com/card/big.png"
        val result = UrlMapper.map(
            url,
            env(
                compatMode = true,
                mappings = mapOf(url to "mine.png"),
                walletImagesDir = "/data/data/com.miui.tsmclient/files/images/",
            ),
        )
        assertEquals("file:///data/data/com.miui.tsmclient/files/images/mine.png", result.url)
        assertEquals(
            "/data/data/com.miui.tsmclient/files/images/mine.png",
            result.copy?.destPath,
        )
        assertEquals("mine.png", result.copy?.localName)
    }

    @Test
    fun `compat mode falls back to content uri when wallet dir unknown`() {
        val url = "https://cdn.example.com/card/big.png"
        val result = UrlMapper.map(
            url,
            env(compatMode = true, mappings = mapOf(url to "mine.png"), walletImagesDir = ""),
        )
        assertEquals(UrlMapper.LOCAL_IMAGE_URI_PREFIX + "mine.png", result.url)
        assertNull(result.copy)
    }

    @Test
    fun `unsafe image name is rejected`() {
        val url = "https://cdn.example.com/card/big.png"
        for (bad in listOf("../evil.png", "a/b.png", "..", ".", "a\\b.png")) {
            val result = UrlMapper.map(url, env(mappings = mapOf(url to bad)))
            assertEquals("unsafe name should be ignored: $bad", "", result.url)
        }
    }

    @Test
    fun `empty url is never tracked or replaced`() {
        val result = UrlMapper.map("", env(testMode = true))
        assertEquals("", result.url)
        assertFalse(result.trackUrl)
    }

    // ---------- 工具函数 ----------

    @Test
    fun `isSafeImageName accepts normal file names`() {
        assertTrue(UrlMapper.isSafeImageName("card-20260101-120000.png"))
        assertTrue(UrlMapper.isSafeImageName("中文名.png"))
        assertFalse(UrlMapper.isSafeImageName(""))
        assertFalse(UrlMapper.isSafeImageName("a/b"))
        assertFalse(UrlMapper.isSafeImageName("../x"))
    }

    @Test
    fun `isSmallIconUrl matches the three original patterns`() {
        assertTrue(UrlMapper.isSmallIconUrl("x/w270h480/y"))
        assertTrue(UrlMapper.isSmallIconUrl("x/door-card-img/logo/y"))
        assertTrue(UrlMapper.isSmallIconUrl("x/Mibi/y"))
        assertFalse(UrlMapper.isSmallIconUrl("https://cdn.example.com/card/big.png"))
    }
}
