package com.bw.wooboxnx.hook.app.systemui.features

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 修复通知中心 / 快速设置左右切换时壁纸缩放跳变。
 *
 * CCDepthController 在切换期间会调用
 *   WallpaperController.setNotificationShadeZoom(1f)
 * 把壁纸缩放强行拉回「未展开」的状态，切换结束再跳回原值，
 * 所以每次切换壁纸都会明显放大/缩小一次。
 *
 * 修正：切换期间沿用切换前的缩放值，不再跳。
 */
object ShadeZoomFix : HookRegister() {

    private var controller: Any? = null
    private var lastStableZoom = 1f

    private fun isSwitching(): Boolean = runCatching {
        XposedHelpers.callMethod(controller, "getNcSwitching") as? Boolean ?: false
    }.getOrDefault(false)

    override fun init() = hasEnable("fix_shade_zoom") {
        runCatching {
            val cl = getDefaultCL()

            val cc = cl.loadClass("com.zte.controlcenter.CCDepthController")
            XposedBridge.hookAllConstructors(cc, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    controller = param.thisObject
                }
            })

            val wallpaper = cl.loadClass("com.android.systemui.util.WallpaperController")
            val setZoom = wallpaper.getDeclaredMethod(
                "setNotificationShadeZoom",
                Float::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(setZoom, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val v = param.args.getOrNull(0) as? Float ?: return
                        if (isSwitching()) {
                            param.args[0] = lastStableZoom
                        } else {
                            lastStableZoom = v
                        }
                    }
                }
            })

            Log.px("I", "ShadeZoomFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeZoomFix init failed", it, logInRelease = true)
        }
    }
}
