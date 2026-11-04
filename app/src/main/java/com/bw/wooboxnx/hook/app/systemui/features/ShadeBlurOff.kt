package com.bw.wooboxnx.hook.app.systemui.features

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 排查用：把通知中心 / 快速设置的背景模糊全部关掉。
 * 打开后 BlurUtils.applyBlur 一律按半径 0 处理。
 */
object ShadeBlurOff : HookRegister() {
    override fun init() = hasEnable("shade_blur_off") {
        runCatching {
            val cl = getDefaultCL()
            val blurUtils = cl.loadClass("com.android.systemui.statusbar.BlurUtils")
            val viewRootImpl = Class.forName("android.view.ViewRootImpl", false, cl)
            val applyBlur = blurUtils.getDeclaredMethod(
                "applyBlur",
                viewRootImpl,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(applyBlur, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { param.args[1] = 0 }
                }
            })
            Log.px("I", "ShadeBlurOff hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeBlurOff init failed", it, logInRelease = true)
        }
    }
}
