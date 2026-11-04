package com.bw.wooboxnx.hook.app.systemui.features

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 修复通知中心 / 快速设置左右切换时背景模糊闪烁。
 *
 * CCDepthController 在切换期间会：
 *   applyBlur(当前页 root, 200)
 *   applyBlur(另一页 root, 0)   ← 每帧交换对象
 * 导致两页的背景在「模糊 / 清晰」之间来回跳。
 *
 * 修正：切换期间任何半径为 0 的调用，都替换成上一次的正值，两页都保持模糊。
 */
object ShadeBlurFix : HookRegister() {

    private var controller: Any? = null
    private var lastPositiveRadius = 0

    private fun isSwitching(): Boolean = runCatching {
        XposedHelpers.callMethod(controller, "getNcSwitching") as? Boolean ?: false
    }.getOrDefault(false)

    override fun init() = hasEnable("fix_shade_blur") {
        runCatching {
            val cl = getDefaultCL()

            val cc = cl.loadClass("com.zte.controlcenter.CCDepthController")
            XposedBridge.hookAllConstructors(cc, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    controller = param.thisObject
                }
            })

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
                    runCatching {
                        val radius = param.args.getOrNull(1) as? Int ?: return
                        if (radius > 0) {
                            lastPositiveRadius = radius
                            return
                        }
                        if (lastPositiveRadius > 0 && isSwitching()) {
                            param.args[1] = lastPositiveRadius
                        }
                    }
                }
            })

            Log.px("I", "ShadeBlurFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeBlurFix init failed", it, logInRelease = true)
        }
    }
}
