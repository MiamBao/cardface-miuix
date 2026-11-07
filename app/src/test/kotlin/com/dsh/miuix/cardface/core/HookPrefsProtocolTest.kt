package com.dsh.miuix.cardface.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RemotePreferences 协议（StringSet 序列化 / 类型映射）的单元测试。
 * 纯逻辑，不依赖 Android 运行时。
 */
class HookPrefsProtocolTest {

    @Test
    fun `encode string set matches lsposed format`() {
        assertEquals("a;b;", HookPrefsProtocol.encodeStringSet(linkedSetOf("a", "b")))
    }

    @Test
    fun `empty set encodes to empty string`() {
        assertEquals("", HookPrefsProtocol.encodeStringSet(emptySet()))
    }

    @Test
    fun `null set encodes to null`() {
        assertNull(HookPrefsProtocol.encodeStringSet(null))
    }

    @Test
    fun `semicolon and backslash are escaped`() {
        val set = linkedSetOf("a;b", "c\\d")
        val encoded = HookPrefsProtocol.encodeStringSet(set)!!
        assertEquals("a\\;b;c\\\\d;", encoded)
        assertEquals(set, HookPrefsProtocol.decodeStringSet(encoded))
    }

    @Test
    fun `decode string set round trips urls`() {
        val set = linkedSetOf(
            "https://cdn.example.com/a.png",
            "https://cdn.example.com/b.png?x=1&y=2",
        )
        assertEquals(set, HookPrefsProtocol.decodeStringSet(HookPrefsProtocol.encodeStringSet(set)))
    }

    @Test
    fun `decode empty or null returns empty set`() {
        assertTrue(HookPrefsProtocol.decodeStringSet(null).isEmpty())
        assertTrue(HookPrefsProtocol.decodeStringSet("").isEmpty())
    }

    @Test
    fun `type codes match lsposed provider protocol`() {
        assertEquals(0, HookPrefsProtocol.typeOf(null))
        assertEquals(1, HookPrefsProtocol.typeOf("x"))
        assertEquals(2, HookPrefsProtocol.typeOf(emptySet<String>()))
        assertEquals(3, HookPrefsProtocol.typeOf(1))
        assertEquals(4, HookPrefsProtocol.typeOf(1L))
        assertEquals(5, HookPrefsProtocol.typeOf(1f))
        assertEquals(6, HookPrefsProtocol.typeOf(true))
    }

    @Test
    fun `authority and pref names are fixed`() {
        assertEquals("io.github.miambao.cardface.preference", HookPrefsProtocol.AUTHORITY)
        assertEquals(listOf("settings", "card_urls"), HookPrefsProtocol.PREF_NAMES.toList())
    }
}
