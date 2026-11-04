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
 * 根因在 com.zte.controlcenter.CCDepthController.updateBlurCallback：
 *   1) 切换期间每帧对「通知根视图」调用 applyBlur(root, 0)，把模糊清零 → 一帧清晰一帧模糊
 *   2) 切换期间把壁纸缩放强制设成 1.0，结束后再跳回原值 → 壁纸一跳
 *   3) 每帧都重新创建 RenderEffect，没有去重
 *
 * 这里做三件事：
 *   A. 切换期间如果收到半径 0，沿用该 root 上一帧的非零半径
 *   B. 切换期间壁纸缩放沿用切换前的值，不再强制 1.0
 *   C. 同一 root 在 300ms 内半径/透明度没变时跳过重复应用
 */
object ShadeBlurFix : HookRegister() {

    private var controller: Any? = null
    private val lastRadius = HashMap<Int, Int>()
    private val lastApplied = HashMap<Int, Pair<Int, Boolean>>()
    private val lastAppliedAt = HashMap<Int, Long>()
    private var lastZoom = 1f

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
                        val root = param.args.getOrNull(0) ?: return
                        var radius = param.args.getOrNull(1) as? Int ?: return
                        val opaque = param.args.getOrNull(2) as? Boolean ?: false
                        val key = System.identityHashCode(root)

                        if (radius > 0) {
                            lastRadius[key] = radius
                        } else if (isSwitching()) {
                            val prev = lastRadius[key] ?: 0
                            if (prev > 0) {
                                param.args[1] = prev
                                radius = prev
                            }
                        }

                        val now = android.os.SystemClock.elapsedRealtime()
                        val prev = lastApplied[key]
                        val prevAt = lastAppliedAt[key] ?: 0L
                        if (prev != null && prev.first == radius && prev.second == opaque &&
                            now - prevAt < 300L
                        ) {
                            // 半径没变，跳过重复创建 RenderEffect
                            param.result = null
                            return
                        }
                        lastApplied[key] = radius to opaque
                        lastAppliedAt[key] = now
                    }
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
                            if (v == 1f && lastZoom != 1f) param.args[0] = lastZoom
                        } else {
                            lastZoom = v
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
