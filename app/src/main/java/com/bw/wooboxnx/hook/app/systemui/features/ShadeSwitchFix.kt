package com.bw.wooboxnx.hook.app.systemui.features

import android.graphics.Canvas
import android.view.View
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 左右切换通知中心 / 快速设置时的两个已知问题：
 *   1. 壁纸缩放被强制改值 → 壁纸放大/缩小一次
 *   2. 两页各自的黑色遮罩同时存在 → 叠在一起瞬间变黑
 *
 * 处理：切换期间固定壁纸缩放，并让通知页的 ScrimView 暂停绘制（只留一层遮罩）。
 */
object ShadeSwitchFix : HookRegister() {

    private var controller: Any? = null
    private var lastStableZoom = 1f

    private fun isSwitching(): Boolean = runCatching {
        XposedHelpers.callMethod(controller, "getNcSwitching") as? Boolean ?: false
    }.getOrDefault(false)

    override fun init() = hasEnable("fix_shade_switch") {
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

            val scrim = cl.loadClass("com.android.systemui.scrim.ScrimView")
            val onDraw = scrim.getDeclaredMethod("onDraw", Canvas::class.java)
            XposedBridge.hookMethod(onDraw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!isSwitching()) return
                        val v = param.thisObject as? View ?: return
                        val root = v.rootView?.javaClass?.simpleName.orEmpty()
                        if (root.contains("NotificationShade")) {
                            param.result = null
                        }
                    }
                }
            })

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }
}
