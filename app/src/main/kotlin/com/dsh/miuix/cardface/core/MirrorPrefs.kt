package com.dsh.miuix.cardface.core

import android.content.SharedPreferences

/**
 * 本地 SharedPreferences 的包装：写入时同步镜像到 LSPosed RemotePreferences（按 syncMode 控制）。
 * 读始终走本地（UI 快），远程只是给 hook 进程同步用。
 */
class MirrorPrefs(
    private val local: SharedPreferences,
    private val remoteGroup: String,
    private val mode: () -> SyncMode,
) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = local.all
    override fun getString(key: String, defValue: String?): String? = local.getString(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        local.getStringSet(key, defValues)?.toMutableSet()
    override fun getInt(key: String, defValue: Int): Int = local.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = local.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = local.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = local.getBoolean(key, defValue)
    override fun contains(key: String): Boolean = local.contains(key)
    override fun edit(): SharedPreferences.Editor = MirrorEditor(local.edit())
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = local.registerOnSharedPreferenceChangeListener(listener)
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = local.unregisterOnSharedPreferenceChangeListener(listener)

    private inner class MirrorEditor(private val localEditor: SharedPreferences.Editor) : SharedPreferences.Editor {
        private val remoteOps = ArrayList<(SharedPreferences.Editor) -> Unit>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            localEditor.putString(key, value); addRemote { it.putString(key, value) }; return this
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            localEditor.putStringSet(key, values); addRemote { it.putStringSet(key, values) }; return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            localEditor.putInt(key, value); addRemote { it.putInt(key, value) }; return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            localEditor.putLong(key, value); addRemote { it.putLong(key, value) }; return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            localEditor.putFloat(key, value); addRemote { it.putFloat(key, value) }; return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            localEditor.putBoolean(key, value); addRemote { it.putBoolean(key, value) }; return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            localEditor.remove(key); addRemote { it.remove(key) }; return this
        }
        override fun clear(): SharedPreferences.Editor {
            localEditor.clear(); clear = true; return this
        }
        override fun commit(): Boolean = finish(localEditor.commit())
        override fun apply() {
            localEditor.apply(); finish(true)
        }

        private fun addRemote(op: (SharedPreferences.Editor) -> Unit) {
            if (mode() != SyncMode.LOCAL) remoteOps.add(op)
        }

        private fun finish(localOk: Boolean): Boolean {
            if (mode() != SyncMode.LOCAL) {
                RemotePrefsBridge.apply(remoteGroup) { re ->
                    if (clear) re.clear()
                    remoteOps.forEach { it(re) }
                }
            }
            return localOk
        }
    }
}
