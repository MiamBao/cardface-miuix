package com.dsh.miuix.cardface.xposed

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.dsh.miuix.cardface.core.HookPrefsProtocol

/**
 * hook 进程访问模块 App 的 RemotePreferences ContentProvider。
 *
 * 注意：libxposed API 101 的 `getRemotePreferences()` 在**被 hook 的进程里是只读的**
 * （LSPosed 文档明确写了 read-only），所以「写回 last_hook_at / all_card_url_set」
 * 必须像原版 DIY NFC卡面 的 n60 一样，直接通过 ContentResolver 访问模块自己的 provider。
 *
 * provider 的 URI 约定（与 core/HookPrefsProvider.kt 一致）：
 *   content://com.dsh.miuix.cardface.preference/<prefs>
 *   content://com.dsh.miuix.cardface.preference/<prefs>/<key>
 */
class ProviderPrefs(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /** 读取某个分组的所有键值；provider 不可访问（未安装/未授权/包可见性限制）时返回 null。 */
    fun readAll(group: String): Map<String, Any?>? = try {
        val uri = groupUri(group)
        val cursor = appContext.contentResolver.query(
            uri,
            arrayOf(HookPrefsProtocol.COLUMN_KEY, HookPrefsProtocol.COLUMN_TYPE, HookPrefsProtocol.COLUMN_VALUE),
            null,
            null,
            null,
        )
        if (cursor == null) {
            XLog.w("provider query returned null: $group")
            null
        } else {
            cursor.use { c ->
                val keyIdx = c.getColumnIndexOrThrow(HookPrefsProtocol.COLUMN_KEY)
                val typeIdx = c.getColumnIndexOrThrow(HookPrefsProtocol.COLUMN_TYPE)
                val valueIdx = c.getColumnIndexOrThrow(HookPrefsProtocol.COLUMN_VALUE)
                val out = LinkedHashMap<String, Any?>()
                while (c.moveToNext()) {
                    val key = c.getString(keyIdx) ?: continue
                    when (val type = c.getInt(typeIdx)) {
                        HookPrefsProtocol.TYPE_NULL -> Unit
                        HookPrefsProtocol.TYPE_STRING -> out[key] = c.getString(valueIdx)
                        HookPrefsProtocol.TYPE_STRING_SET ->
                            out[key] = HookPrefsProtocol.decodeStringSet(c.getString(valueIdx))
                        HookPrefsProtocol.TYPE_INT -> out[key] = c.getInt(valueIdx)
                        HookPrefsProtocol.TYPE_LONG -> out[key] = c.getLong(valueIdx)
                        HookPrefsProtocol.TYPE_FLOAT -> out[key] = c.getFloat(valueIdx)
                        HookPrefsProtocol.TYPE_BOOLEAN -> out[key] = c.getInt(valueIdx) != 0
                        else -> XLog.w("provider unknown type $type for key $key")
                    }
                }
                out
            }
        }
    } catch (t: Throwable) {
        XLog.w("provider read failed: $group", t)
        null
    }

    fun putString(group: String, key: String, value: String): Boolean =
        bulkWrite(group, mapOf(key to HookPrefsProtocol.TYPE_STRING to value))

    fun putBoolean(group: String, key: String, value: Boolean): Boolean =
        bulkWrite(group, mapOf(key to HookPrefsProtocol.TYPE_BOOLEAN to (if (value) 1 else 0)))

    fun putLong(group: String, key: String, value: Long): Boolean =
        bulkWrite(group, mapOf(key to HookPrefsProtocol.TYPE_LONG to value))

    fun putStringSet(group: String, key: String, value: Set<String>): Boolean =
        bulkWrite(group, mapOf(key to HookPrefsProtocol.TYPE_STRING_SET to HookPrefsProtocol.encodeStringSet(value)))

    /** 批量写入（一次 bulkInsert，provider 侧一次 commit）。 */
    fun bulkWrite(group: String, values: Map<Pair<String, Int>, Any?>): Boolean {
        if (values.isEmpty()) return true
        val rows = values.entries.map { (keyType, value) ->
            ContentValues(4).apply {
                put(HookPrefsProtocol.COLUMN_KEY, keyType.first)
                put(HookPrefsProtocol.COLUMN_TYPE, keyType.second)
                when (value) {
                    null -> putNull(HookPrefsProtocol.COLUMN_VALUE)
                    is String -> put(HookPrefsProtocol.COLUMN_VALUE, value)
                    is Int -> put(HookPrefsProtocol.COLUMN_VALUE, value)
                    is Long -> put(HookPrefsProtocol.COLUMN_VALUE, value)
                    is Float -> put(HookPrefsProtocol.COLUMN_VALUE, value)
                    is Boolean -> put(HookPrefsProtocol.COLUMN_VALUE, if (value) 1 else 0)
                    else -> put(HookPrefsProtocol.COLUMN_VALUE, value.toString())
                }
            }
        }.toTypedArray()
        return try {
            val count = appContext.contentResolver.bulkInsert(groupUri(group), rows)
            if (count != rows.size) {
                XLog.w("provider bulkInsert wrote $count/${rows.size} rows for $group")
            }
            count == rows.size
        } catch (t: Throwable) {
            XLog.w("provider write failed: $group", t)
            false
        }
    }

    /** 删除某个 key；key 为 null 时清空整个分组。 */
    fun delete(group: String, key: String?): Boolean = try {
        val uri = if (key == null) groupUri(group) else groupUri(group).buildUpon().appendPath(key).build()
        appContext.contentResolver.delete(uri, null, null) > 0
    } catch (t: Throwable) {
        XLog.w("provider delete failed: $group/$key", t)
        false
    }

    private fun groupUri(group: String): Uri =
        Uri.parse("content://${HookPrefsProtocol.AUTHORITY}/$group")
}
