package com.dsh.miuix.cardface.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 101 模块入口。
 *
 * 由 APK 根目录 `META-INF/xposed/java_init.list` 指定：
 *   `com.dsh.miuix.cardface.xposed.HookEntry`
 *
 * 只在两个卡包进程里工作：
 *  - com.miui.tsmclient  -> ostype 0（MIUI 卡包）
 *  - com.finshell.wallet -> ostype 1（ColorOS 卡包）
 * 其它包直接返回。
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XLog.attach(this)
        XLog.i(
            "module loaded: api=${apiVersion}, framework=${frameworkName} ${frameworkVersion}, " +
                "process=${param.processName}",
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        val osType = when (packageName) {
            MethodLocator.MIUI_PACKAGE -> MethodLocator.OS_MIUI
            MethodLocator.COLOROS_PACKAGE -> MethodLocator.OS_COLOROS
            else -> {
                XLog.d("skip unsupported package: $packageName")
                return
            }
        }
        XLog.attach(this)
        XLog.i("package ready: $packageName (osType=$osType, firstPackage=${param.isFirstPackage})")
        try {
            HookEngine(this, osType, param.classLoader, param.applicationInfo).start()
        } catch (t: Throwable) {
            // 模块异常绝不能拖垮卡包。
            XLog.e("hook start failed for $packageName", t)
        }
    }
}
