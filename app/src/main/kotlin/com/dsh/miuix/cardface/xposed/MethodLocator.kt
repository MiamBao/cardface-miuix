package com.dsh.miuix.cardface.xposed

import android.content.pm.ApplicationInfo
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method

/**
 * 目标方法定位：顺序对齐原版 Hook.positionMethod / prepareHook。
 *
 * 1. 用户配置的自定义 hook 点（settings 的 class / method / method_arg）
 * 2. 缓存文件（卡包 files/cardface.method.0.txt / .1.txt / .island.txt，两行：类名、方法名）
 * 3. DexKit 2.3.0 定位（按 HOOK-SPEC 的包名 / 源文件名 / 返回类型 / 参数类型约束）
 * 4. 反射兜底（已知类名直接扫方法；找不到只写日志，不崩溃）
 */
class MethodLocator(
    private val osType: Int,
    private val classLoader: ClassLoader,
    private val appInfo: ApplicationInfo,
) {

    private var bridge: DexKitBridge? = null

    private val walletPackage: String = when (osType) {
        OS_MIUI -> MIUI_PACKAGE
        OS_COLOROS -> COLOROS_PACKAGE
        else -> MIUI_PACKAGE
    }

    private val filesDir: File by lazy {
        val dataDir = appInfo.dataDir ?: "/data/data/$walletPackage"
        File(dataDir, "files")
    }

    /** 一次进程内定位多个 hook 点，DexKit 只初始化一次。 */
    fun findTargets(slots: List<Int>): Map<Int, Method> {
        val found = LinkedHashMap<Int, Method>()
        val pending = ArrayList<Int>()
        for (slot in slots) {
            val cached = readCache(slot)
            if (cached != null) {
                val method = resolve(
                    cached.className, cached.methodName, expectsForSlot(slot),
                )
                if (method != null) {
                    XLog.i("hook target from cache slot=$slot -> ${cached.className}#${cached.methodName}")
                    found[slot] = method
                    continue
                }
                XLog.w("stale hook cache for slot=$slot, re-locating")
            }
            pending.add(slot)
        }
        if (pending.isEmpty()) return found

        val dexKit = ensureBridge()
        if (dexKit != null) {
            for (slot in pending) {
                try {
                    val method = locateWithDexKit(dexKit, slot)
                    if (method != null) {
                        XLog.i("DexKit located hook target slot=$slot -> ${method.declaringClass.name}#${method.name}")
                        found[slot] = method
                        writeCache(slot, method)
                    }
                } catch (t: Throwable) {
                    XLog.w("DexKit locate slot=$slot failed", t)
                }
            }
        } else {
            XLog.w("DexKit unavailable, skip to reflection fallback")
        }

        for (slot in pending) {
            if (found.containsKey(slot)) continue
            val method = locateWithReflection(slot)
            if (method != null) {
                XLog.i("reflection fallback located hook target slot=$slot -> ${method.declaringClass.name}#${method.name}")
                found[slot] = method
                writeCache(slot, method)
            }
        }
        return found
    }

    /**
     * 同一 hook 点的所有候选方法。
     *
     * GLIDE 的 RequestManager 里可能有多个 `xxx(String)` 重载（混淆后名字不同），
     * 只取第一个容易打错点——原版在 MIUI#1 上就是「参数替换」，
     * 所以这里把所有单 String 参数的方法都返回，交给 HookEngine 全部装上（多装无害）。
     */
    fun findCandidates(slot: Int): List<Method> {
        if (slot != SLOT_REPLACE_ARG) return emptyList()
        val result = LinkedHashMap<String, Method>()
        readCache(slot)?.let { cached ->
            resolve(cached.className, cached.methodName, expectsForSlot(slot))?.let {
                result["${it.declaringClass.name}#${it.name}"] = it
            }
        }
        val dexKit = ensureBridge() ?: return result.values.toList()
        try {
            val classes = dexKit.findClass {
                searchPackages(GLIDE_PACKAGE)
                matcher { source("RequestManager.java") }
            }
            if (!classes.isEmpty()) {
                val methods = dexKit.findMethod {
                    searchInClass(classes)
                    matcher { paramCount(1) }
                }.filter {
                    val first = it.paramTypeNames.firstOrNull()
                    first == "java.lang.String" || first == "java.lang.Object" ||
                        first == "android.net.Uri" || first == "java.io.File"
                }
                for (data in methods) {
                    val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull() ?: continue
                    if (method.isSynthetic) continue
                    result["${method.declaringClass.name}#${method.name}"] = method
                }
                if (methods.size > 1) {
                    XLog.w("MIUI#1 candidates(${methods.size}): " + describe(methods))
                }
            }
        } catch (t: Throwable) {
            XLog.w("findCandidates(slot=$slot) failed", t)
        }
        // 反射兜底也加进来（已知 Glide RequestManager.load(String)）。
        locateWithReflection(slot)?.let {
            result["${it.declaringClass.name}#${it.name}"] = it
        }
        return result.values.toList()
    }

    /** 用户自定义 hook 点：class / method / method_arg（空 = 无参）。 */
    fun findCustom(className: String, methodName: String, argClassName: String): Method? {
        if (className.isEmpty() || methodName.isEmpty()) return null
        val clazz = try {
            classLoader.loadClass(className)
        } catch (t: Throwable) {
            XLog.e("custom hook class not found: $className", t)
            return null
        }
        val argClass: Class<*>? = when {
            argClassName.isEmpty() || argClassName == "null" -> null
            argClassName == "String" -> String::class.java
            else -> try {
                classLoader.loadClass(argClassName)
            } catch (t: Throwable) {
                XLog.e("custom hook arg class not found: $argClassName", t)
                return null
            }
        }
        val candidates = clazz.declaredMethods.filter { m ->
            m.name == methodName && when {
                argClass == null -> m.parameterCount == 0
                m.parameterCount == 1 -> m.parameterTypes[0] == argClass
                else -> false
            }
        }
        if (candidates.isEmpty()) {
            XLog.e("custom hook method not found: $className#$methodName arg=$argClassName")
            val overloads = clazz.declaredMethods.filter { it.name == methodName }
            if (overloads.isNotEmpty()) {
                XLog.e("candidates: " + overloads.joinToString { it.toGenericString() })
            }
            return null
        }
        if (candidates.size > 1) {
            XLog.w("custom hook method is ambiguous, take first of ${candidates.size}: ${candidates[0].toGenericString()}")
        }
        return candidates[0]
    }

    fun close() {
        try {
            bridge?.close()
        } catch (_: Throwable) {
        }
        bridge = null
    }

    // ---------- DexKit ----------

    private fun ensureBridge(): DexKitBridge? {
        bridge?.let { if (it.isValid) return it }
        if (!ensureNativeLoaded()) return null
        return try {
            val created = DexKitBridge.create(appInfo.sourceDir)
            bridge = created
            created
        } catch (t: Throwable) {
            XLog.e("DexKit init failed: ${appInfo.sourceDir}", t)
            null
        }
    }

    private fun locateWithDexKit(dexKit: DexKitBridge, slot: Int): Method? {
        val data = when {
            osType == OS_COLOROS -> locateColorOs(dexKit)
            slot == SLOT_URL_FROM_ARG -> locateMiui0(dexKit)
            slot == SLOT_REPLACE_ARG -> locateMiui1(dexKit)
            slot == SLOT_SUPER_ISLAND -> locateMiuiIsland(dexKit)
            else -> null
        } ?: return null
        // 优先直接用 DexKit 给的方法（精确签名），失败再按名字 + 约束解析。
        val direct = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
        if (direct != null) return direct
        return resolve(data.className, data.methodName, expectsForSlot(slot))
    }

    /** MIUI 普通卡面：com.miui.tsmclient.util 包中源文件 CustomGlideUrl.java 的类，返回 String。 */
    private fun locateMiui0(dexKit: DexKitBridge): org.luckypray.dexkit.result.MethodData? {
        val classes = dexKit.findClass {
            searchPackages(MIUI_UTIL_PACKAGE)
            matcher { source("CustomGlideUrl.java") }
        }
        if (!classes.isEmpty()) {
            // 真正的卡面入口是 CustomGlideUrl 的静态工厂 `i(String) -> Object`（混淆后方法名可能是 i）。
            // 之前错定位到无参的 `c()`（URL getter），导致卡面替换完全没走到。
            val queries = listOf<() -> org.luckypray.dexkit.result.MethodDataList>(
                {
                    dexKit.findMethod {
                        searchInClass(classes)
                        matcher { paramTypes("java.lang.String"); returnType("java.lang.Object") }
                    }
                },
                {
                    dexKit.findMethod {
                        searchInClass(classes)
                        matcher { paramTypes("java.lang.String"); paramCount(1) }
                    }
                },
                {
                    dexKit.findMethod {
                        searchInClass(classes)
                        matcher { returnType("java.lang.Object") }
                    }
                },
            )
            queries.forEach { query ->
                val result = query().filter { !it.methodName.startsWith("<") }
                if (result.size == 1) return result[0]
                if (result.size > 1) {
                    XLog.w("MIUI#0 matched ${result.size} methods: " + describe(result))
                    // 优先静态、单 String 参数、返回 Object 的工厂方法
                    val best = result.firstOrNull { data ->
                        runCatching {
                            val m = data.getMethodInstance(classLoader)
                            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                                m.parameterCount == 1 && m.parameterTypes[0] == String::class.java
                        }.getOrDefault(false)
                    } ?: result[0]
                    return best
                }
            }
        }
        XLog.w("MIUI#0: CustomGlideUrl.java classes not matched, fallback to package scan")
        val fallback = dexKit.findMethod {
            searchPackages(MIUI_UTIL_PACKAGE)
            matcher { returnType("java.lang.String"); paramCount(0) }
        }
        if (fallback.size > 1) XLog.w("MIUI#0 fallback matched ${fallback.size}: " + describe(fallback))
        return fallback.firstOrNull()
    }

    /** MIUI 参数替换：com.bumptech.glide 中源文件 RequestManager.java 的类，void(String)。 */
    private fun locateMiui1(dexKit: DexKitBridge): org.luckypray.dexkit.result.MethodData? {
        val classes = dexKit.findClass {
            searchPackages(GLIDE_PACKAGE)
            matcher { source("RequestManager.java") }
        }
        if (!classes.isEmpty()) {
            val strict = dexKit.findMethod {
                searchInClass(classes)
                matcher { returnType("void"); paramTypes("java.lang.String") }
            }
            if (strict.size == 1) return strict[0]
            if (strict.size > 1) {
                XLog.w("MIUI#1 matched ${strict.size} methods: " + describe(strict))
                return strict[0]
            }
            val anySingle = dexKit.findMethod {
                searchInClass(classes)
                matcher { returnType("void"); paramCount(1) }
            }
            if (anySingle.isNotEmpty()) {
                if (anySingle.size > 1) XLog.w("MIUI#1 single-param matched ${anySingle.size}: " + describe(anySingle))
                return anySingle[0]
            }
        }
        XLog.w("MIUI#1: RequestManager.java classes not matched, fallback to RequestManager.load(String)")
        return dexKit.findMethod {
            matcher {
                declaredClass(GLIDE_REQUEST_MANAGER)
                returnType("com.bumptech.glide.RequestBuilder")
                paramTypes("java.lang.String")
            }
        }.firstOrNull()
    }

    /** MIUI 超级岛：com.miui.tsmclient.util 中返回 String、参数为 CardInfo 的方法。 */
    private fun locateMiuiIsland(dexKit: DexKitBridge): org.luckypray.dexkit.result.MethodData? {
        val result = dexKit.findMethod {
            searchPackages(MIUI_UTIL_PACKAGE)
            matcher { returnType("java.lang.String"); paramTypes(CARD_INFO_CLASS) }
        }
        if (result.size > 1) XLog.w("MIUI#10 matched ${result.size} methods: " + describe(result))
        return result.firstOrNull()
    }

    /** ColorOS 卡包：CircleNetworkImageView 中 void(String)。 */
    private fun locateColorOs(dexKit: DexKitBridge): org.luckypray.dexkit.result.MethodData? {
        val strict = dexKit.findMethod {
            matcher {
                declaredClass(COLOROS_IMAGE_VIEW)
                returnType("void")
                paramTypes("java.lang.String")
            }
        }
        if (strict.isNotEmpty()) {
            if (strict.size > 1) XLog.w("ColorOS matched ${strict.size} methods: " + describe(strict))
            return strict[0]
        }
        val anySingle = dexKit.findMethod {
            matcher {
                declaredClass(COLOROS_IMAGE_VIEW)
                returnType("void")
                paramCount(1)
            }
        }
        if (anySingle.size > 1) XLog.w("ColorOS single-param matched ${anySingle.size}: " + describe(anySingle))
        return anySingle.firstOrNull()
    }

    private fun describe(list: Collection<org.luckypray.dexkit.result.MethodData>): String =
        list.take(8).joinToString { "${it.className}#${it.methodName}(${it.paramTypeNames.joinToString(",")})" }

    // ---------- 反射兜底 ----------

    private fun locateWithReflection(slot: Int): Method? = when {
        osType == OS_COLOROS -> scanDeclaredMethods(COLOROS_IMAGE_VIEW, null, expectsForSlot(slot))
        slot == SLOT_REPLACE_ARG -> {
            // 已知候选：Glide RequestManager.load(String) —— 语义与 MIUI#1 完全一致（替换参数）。
            // 注意 load(String) 返回 RequestBuilder 而不是 void，所以要单独给候选签名。
            scanDeclaredMethods(
                GLIDE_REQUEST_MANAGER,
                "load",
                listOf(
                    Expect("com.bumptech.glide.RequestBuilder", 1, "java.lang.String"),
                    Expect(null, 1, "java.lang.String"),
                ),
            )
        }
        slot == SLOT_URL_FROM_ARG -> scanDeclaredMethods(MIUI_CUSTOM_GLIDE_URL_GUESS, null, expectsForSlot(slot))
        else -> null
    }

    private fun scanDeclaredMethods(
        className: String,
        methodName: String?,
        expects: List<Expect>,
    ): Method? {
        val clazz = try {
            classLoader.loadClass(className)
        } catch (t: Throwable) {
            XLog.w("reflection fallback: class not found $className")
            return null
        }
        val named = clazz.declaredMethods.filter { methodName == null || it.name == methodName }
        if (named.isEmpty()) {
            XLog.w("reflection fallback: no method '$methodName' in $className")
            return null
        }
        for (expect in expects) {
            val candidates = named.filter { m ->
                (expect.returnType == null || m.returnType.name == expect.returnType) &&
                    (expect.paramCount == null || m.parameterCount == expect.paramCount) &&
                    (expect.paramType == null ||
                        (m.parameterCount >= 1 && m.parameterTypes[0].name == expect.paramType))
            }
            if (candidates.size == 1) return candidates[0]
            if (candidates.size > 1) {
                XLog.w("reflection fallback $className matched ${candidates.size}, take first")
                return candidates[0]
            }
        }
        XLog.w("reflection fallback: no signature matched in $className (name=$methodName)")
        return null
    }

    // ---------- 缓存文件 ----------

    private data class CacheEntry(val className: String, val methodName: String)

    private fun cacheFile(slot: Int): File {
        val name = when (slot) {
            SLOT_URL_FROM_ARG -> "cardface.method.0.v2.txt"
            SLOT_REPLACE_ARG -> "cardface.method.1.v2.txt"
            SLOT_SUPER_ISLAND -> "cardface.method.island.v2.txt"
            else -> "cardface.method.$slot.txt"
        }
        return File(filesDir, name)
    }

    private fun readCache(slot: Int): CacheEntry? = try {
        val file = cacheFile(slot)
        if (!file.isFile) {
            null
        } else {
            val lines = file.readLines()
            if (lines.size >= 2 && lines[0].isNotBlank() && lines[1].isNotBlank()) {
                CacheEntry(lines[0].trim(), lines[1].trim())
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        XLog.w("read hook cache slot=$slot failed", t)
        null
    }

    private fun writeCache(slot: Int, method: Method) {
        try {
            val file = cacheFile(slot)
            filesDir.mkdirs()
            file.writeText("${method.declaringClass.name}\n${method.name}\n")
            XLog.i("hook cache written: ${file.absolutePath} -> ${method.declaringClass.name}#${method.name}")
        } catch (t: Throwable) {
            XLog.w("write hook cache slot=$slot failed", t)
        }
    }

    // ---------- 期望签名 ----------

    private data class Expect(
        val returnType: String?,
        val paramCount: Int? = null,
        val paramType: String? = null,
    )

    private fun expectsForSlot(slot: Int): List<Expect> = when {
        osType == OS_COLOROS -> listOf(
            Expect("void", 1, "java.lang.String"),
            Expect("void", 1, null),
        )
        slot == SLOT_URL_FROM_ARG -> listOf(
            Expect("java.lang.String", 0, null),
            Expect("java.lang.String", 1, "java.lang.String"),
        )
        slot == SLOT_REPLACE_ARG -> listOf(
            Expect("void", 1, "java.lang.String"),
            Expect("void", 1, null),
        )
        slot == SLOT_SUPER_ISLAND -> listOf(
            Expect("java.lang.String", 1, CARD_INFO_CLASS),
            Expect("java.lang.String", 1, null),
        )
        else -> emptyList()
    }

    /** 按 类名 + 方法名 + 期望签名 取第一个匹配的 DeclaredMethod。 */
    private fun resolve(className: String, methodName: String, expects: List<Expect>): Method? {
        val clazz = try {
            classLoader.loadClass(className)
        } catch (t: Throwable) {
            XLog.w("resolve: class not found $className")
            return null
        }
        val named = clazz.declaredMethods.filter { it.name == methodName }
        if (named.isEmpty()) return null
        for (expect in expects) {
            val matched = named.filter { m ->
                (expect.returnType == null || m.returnType.name == expect.returnType) &&
                    (expect.paramCount == null || m.parameterCount == expect.paramCount) &&
                    (expect.paramType == null ||
                        (m.parameterCount >= 1 && m.parameterTypes[0].name == expect.paramType))
            }
            if (matched.isNotEmpty()) {
                if (matched.size > 1) {
                    XLog.w("resolve $className#$methodName ambiguous (${matched.size}), take first")
                }
                return matched[0]
            }
        }
        // 没有签名约束时（自定义/兜底）直接返回同名方法。
        if (expects.isEmpty()) return named[0]
        XLog.w(
            "resolve $className#$methodName signature mismatch, candidates: " +
                named.joinToString { it.toGenericString() },
        )
        return null
    }

    companion object {
        const val OS_MIUI = 0
        const val OS_COLOROS = 1

        const val SLOT_URL_FROM_ARG = 0
        const val SLOT_REPLACE_ARG = 1
        const val SLOT_SUPER_ISLAND = 10

        const val MIUI_PACKAGE = "com.miui.tsmclient"
        const val COLOROS_PACKAGE = "com.finshell.wallet"
        private const val MIUI_UTIL_PACKAGE = "com.miui.tsmclient.util"
        private const val GLIDE_PACKAGE = "com.bumptech.glide"
        private const val GLIDE_REQUEST_MANAGER = "com.bumptech.glide.RequestManager"
        private const val CARD_INFO_CLASS = "com.miui.tsmclient.entity.CardInfo"
        private const val COLOROS_IMAGE_VIEW = "com.finshell.finui.widget.imageview.CircleNetworkImageView"
        private const val MIUI_CUSTOM_GLIDE_URL_GUESS = "com.miui.tsmclient.util.CustomGlideUrl"

        @Volatile
        private var nativeTried = false

        @Volatile
        private var nativeLoaded = false

        /** LSPosed 的模块 ClassLoader 的 native 搜索路径包含 <模块 apk>!/lib/<abi>，可正常 load。 */
        @Synchronized
        private fun ensureNativeLoaded(): Boolean {
            if (nativeTried) return nativeLoaded
            nativeTried = true
            nativeLoaded = try {
                System.loadLibrary("dexkit")
                true
            } catch (t: Throwable) {
                XLog.e("load libdexkit.so failed", t)
                false
            }
            return nativeLoaded
        }
    }
}
