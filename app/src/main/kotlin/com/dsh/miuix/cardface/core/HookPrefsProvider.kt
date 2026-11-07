package com.dsh.miuix.cardface.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import java.util.LinkedHashMap

/**
 * RemotePreferences 协议常量（对齐 LSPosed 的 RemotePreferencesProvider 模板 /
 * 反编译原版 MyPreferenceProvider.java）。
 *
 * 协议：
 *  - URI：`content://<authority>/<prefsName>` 或 `content://<authority>/<prefsName>/<key>`
 *  - query 列：`key`、`type`、`value`
 *  - type：0=null 1=String 2=StringSet 3=int 4=long 5=float 6=boolean
 *  - StringSet 序列化：元素转义 `\` 与 `;` 后用 `;` 连接（尾部也带一个 `;`）
 *  - 写入用 insert / bulkInsert / update / delete，提交后 notifyChange 通知 hook 侧观察者
 */
object HookPrefsProtocol {

    /** 必须是 <modulePackage>.preference（LSPosed 约定）。 */
    const val AUTHORITY = "com.dsh.miuix.cardface.preference"

    /** 暴露给卡包进程的 prefs 分组。 */
    val PREF_NAMES = arrayOf("settings", "card_urls")

    const val COLUMN_KEY = "key"
    const val COLUMN_TYPE = "type"
    const val COLUMN_VALUE = "value"

    val DEFAULT_COLUMNS = arrayOf(COLUMN_KEY, COLUMN_TYPE, COLUMN_VALUE)

    const val TYPE_NULL = 0
    const val TYPE_STRING = 1
    const val TYPE_STRING_SET = 2
    const val TYPE_INT = 3
    const val TYPE_LONG = 4
    const val TYPE_FLOAT = 5
    const val TYPE_BOOLEAN = 6

    fun typeOf(value: Any?): Int = when (value) {
        null -> TYPE_NULL
        is String -> TYPE_STRING
        is Set<*> -> TYPE_STRING_SET
        is Int -> TYPE_INT
        is Long -> TYPE_LONG
        is Float -> TYPE_FLOAT
        is Boolean -> TYPE_BOOLEAN
        else -> throw AssertionError("Unknown preference type: ${value.javaClass}")
    }

    /** 与反编译原版 er.Y 一致：`\` 与 `;` 转义，元素间用 `;`，末尾也带 `;`。 */
    fun encodeStringSet(set: Set<String>?): String? {
        if (set == null) return null
        val sb = StringBuilder()
        for (item in set) {
            sb.append(item.replace("\\", "\\\\").replace(";", "\\;"))
            sb.append(';')
        }
        return sb.toString()
    }

    /** 与反编译原版 er.i 一致。 */
    fun decodeStringSet(raw: String?): Set<String> {
        if (raw == null) return emptySet()
        val out = LinkedHashSet<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' -> {
                    i++
                    if (i < raw.length) sb.append(raw[i])
                }
                c == ';' -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) {
            // 与 LSPosed 实现一致：尾部还有残留字符说明数据损坏，忽略残留而不是报错。
            Log.w("HookPrefsProvider", "serialized string set has trailing chars")
        }
        return out
    }
}

/**
 * 模块侧的 RemotePreferences ContentProvider（authority = [HookPrefsProtocol.AUTHORITY]）。
 *
 * AndroidManifest 里注册（由 Lead 合并）：
 * ```xml
 * <provider
 *     android:name="com.dsh.miuix.cardface.core.HookPrefsProvider"
 *     android:authorities="com.dsh.miuix.cardface.preference"
 *     android:exported="true" />
 * ```
 *
 * 客户端有两类：
 *  - 卡包进程（hook）：xposed/ProviderPrefs.kt 通过 ContentResolver 读写 settings / card_urls；
 *  - 未来如果用 libxposed service 同步 LSPosed 数据库，App 侧同样写这两个 SharedPreferences。
 */
class HookPrefsProvider : ContentProvider(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = LinkedHashMap<String, SharedPreferences>()

    override fun onCreate(): Boolean {
        val ctx: Context = context ?: return false
        for (name in HookPrefsProtocol.PREF_NAMES) {
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            sp.registerOnSharedPreferenceChangeListener(this)
            prefs[name] = sp
        }
        // 测试模式要返回一张固定测试图：provider 初始化时顺手保证它存在（不需要 root）。
        try {
            ensureTestImage(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "ensure test image failed: $t")
        }
        return true
    }

    // ---------- query ----------

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val parsed = parse(uri) ?: return null
        val sp = prefs[parsed.prefName] ?: return null
        val columns = projection ?: HookPrefsProtocol.DEFAULT_COLUMNS
        val cursor = MatrixCursor(columns)
        val all = try {
            sp.all
        } catch (t: Throwable) {
            Log.w(TAG, "read prefs ${parsed.prefName} failed: $t")
            return cursor
        }
        if (parsed.key != null) {
            val value = all[parsed.key]
            cursor.addRow(row(columns, parsed.key, value))
        } else {
            for ((key, value) in all) {
                cursor.addRow(row(columns, key, value))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    // ---------- insert / bulkInsert ----------

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) return null
        val parsed = parse(uri) ?: return null
        val sp = prefs[parsed.prefName] ?: return null
        var key = values.getAsString(HookPrefsProtocol.COLUMN_KEY)?.takeIf { it.isNotEmpty() }
        if (parsed.key != null && key != null && parsed.key != key) {
            Log.w(TAG, "Conflicting keys specified in URI and ContentValues")
            return null
        }
        if (key == null) key = parsed.key
        val editor = sp.edit()
        if (!applyValue(editor, key, values)) return null
        return if (editor.commit()) buildUri(parsed.prefName, key) else null
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        if (values.isEmpty()) return 0
        val parsed = parse(uri) ?: return 0
        val sp = prefs[parsed.prefName] ?: return 0
        if (parsed.key != null) {
            Log.w(TAG, "Cannot bulk insert with single key URI")
            return 0
        }
        val editor = sp.edit()
        for (cv in values) {
            val key = cv.getAsString(HookPrefsProtocol.COLUMN_KEY)?.takeIf { it.isNotEmpty() }
            if (!applyValue(editor, key, cv)) return 0
        }
        return if (editor.commit()) values.size else 0
    }

    // ---------- delete / update ----------

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val parsed = parse(uri) ?: return 0
        val sp = prefs[parsed.prefName] ?: return 0
        val editor = sp.edit()
        if (parsed.key != null) editor.remove(parsed.key) else editor.clear()
        return if (editor.commit()) 1 else 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = if (values == null) {
        delete(uri, selection, selectionArgs)
    } else {
        if (insert(uri, values) != null) 1 else 0
    }

    // ---------- 变更通知 ----------

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val name = prefs.entries.firstOrNull { it.value === sharedPreferences }?.key ?: return
        try {
            context?.contentResolver?.notifyChange(buildUri(name, key), null)
        } catch (t: Throwable) {
            Log.w(TAG, "notifyChange failed for $name/$key: $t")
        }
    }

    // ---------- 工具 ----------

    private data class ParsedUri(val prefName: String, val key: String?)

    private fun parse(uri: Uri): ParsedUri? {
        val segments = uri.pathSegments
        if (segments.isEmpty()) {
            Log.w(TAG, "Invalid URI: $uri")
            return null
        }
        val name = segments[0]
        if (prefs[name] == null) {
            Log.w(TAG, "Unknown preference file name: $name")
            return null
        }
        return ParsedUri(name, segments.getOrNull(1))
    }

    private fun buildUri(prefName: String, key: String?): Uri {
        val builder = Uri.parse("content://${HookPrefsProtocol.AUTHORITY}").buildUpon().appendPath(prefName)
        if (!key.isNullOrEmpty()) builder.appendPath(key)
        return builder.build()
    }

    private fun row(columns: Array<out String>, key: String, value: Any?): Array<Any?> {
        val out = arrayOfNulls<Any?>(columns.size)
        for (i in columns.indices) {
            when (columns[i]) {
                HookPrefsProtocol.COLUMN_KEY -> out[i] = key
                HookPrefsProtocol.COLUMN_TYPE -> out[i] = HookPrefsProtocol.typeOf(value)
                HookPrefsProtocol.COLUMN_VALUE -> out[i] = when (value) {
                    is Boolean -> if (value) 1 else 0
                    is Set<*> -> HookPrefsProtocol.encodeStringSet(value.filterIsInstance<String>().toSet())
                    else -> value
                }
                else -> throw IllegalArgumentException("Invalid column name: ${columns[i]}")
            }
        }
        return out
    }

    /** 把 ContentValues 的 key/type/value 写进 editor；返回 false 表示数据非法。 */
    private fun applyValue(editor: SharedPreferences.Editor, key: String?, cv: ContentValues): Boolean {
        val type = cv.getAsInteger(HookPrefsProtocol.COLUMN_TYPE)
        if (type == null) {
            Log.w(TAG, "Invalid or no preference type specified")
            return false
        }
        val raw = cv.get(HookPrefsProtocol.COLUMN_VALUE)
        if (type == HookPrefsProtocol.TYPE_NULL && raw != null) {
            Log.w(TAG, "Expected null, got non-null value")
            return false
        }
        if (key == null) {
            if (type == HookPrefsProtocol.TYPE_NULL) {
                editor.clear()
                return true
            }
            Log.w(TAG, "Attempting to insert preference with null or empty key")
            return false
        }
        return try {
            when (type) {
                HookPrefsProtocol.TYPE_NULL -> editor.remove(key)
                HookPrefsProtocol.TYPE_STRING -> {
                    editor.putString(key, raw as? String ?: return false)
                }
                HookPrefsProtocol.TYPE_STRING_SET -> {
                    val set = when (raw) {
                        is String -> HookPrefsProtocol.decodeStringSet(raw)
                        is Set<*> -> raw.filterIsInstance<String>().toSet()
                        null -> return false
                        else -> return false
                    }
                    editor.putStringSet(key, set)
                }
                HookPrefsProtocol.TYPE_INT -> editor.putInt(key, (raw as Number).toInt())
                HookPrefsProtocol.TYPE_LONG -> editor.putLong(key, (raw as Number).toLong())
                HookPrefsProtocol.TYPE_FLOAT -> editor.putFloat(key, (raw as Number).toFloat())
                HookPrefsProtocol.TYPE_BOOLEAN -> {
                    val flag = when (raw) {
                        is Boolean -> raw
                        is Number -> raw.toInt() != 0
                        else -> return false
                    }
                    editor.putBoolean(key, flag)
                }
                else -> {
                    Log.w(TAG, "Unknown type: $type")
                    return false
                }
            }
            true
        } catch (t: ClassCastException) {
            Log.w(TAG, "Expected type $type, got $raw", t)
            false
        }
    }

    /**
     * 生成测试模式的固定测试图（960x606 PNG，放 files/images/cardface-test.png）。
     * hook 侧 test_mode 返回 content://.../images/cardface-test.png，不需要 root。
     */
    private fun ensureTestImage(context: Context) {
        val store = ImageStore(context)
        if (store.find(TEST_IMAGE_NAME) != null) return
        val width = 960
        val height = 606
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.rgb(47, 107, 255), Color.rgb(148, 87, 255), Color.rgb(255, 122, 89)),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

            val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
            canvas.drawCircle(width * 0.82f, height * 0.18f, height * 0.42f, stripe)
            canvas.drawCircle(width * 0.12f, height * 0.88f, height * 0.30f, stripe)

            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 96f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("测试卡面", width * 0.09f, height * 0.50f, title)

            val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 255, 255, 255)
                textSize = 42f
            }
            canvas.drawText("CARD FACE TEST", width * 0.095f, height * 0.62f, subtitle)
            canvas.drawText("测试模式生效中 · 关闭后恢复原图", width * 0.095f, height * 0.72f, subtitle)
            store.save(bitmap, TEST_IMAGE_NAME)
            Log.i(TAG, "test image created: ${store.find(TEST_IMAGE_NAME)?.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "draw test image failed: $t")
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "HookPrefsProvider"
        const val TEST_IMAGE_NAME = "cardface-test.png"
    }
}
