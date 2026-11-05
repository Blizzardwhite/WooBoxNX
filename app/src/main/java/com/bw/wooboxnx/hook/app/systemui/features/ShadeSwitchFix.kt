package com.bw.wooboxnx.hook.app.systemui.features

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 左右切换通知中心 / 快速设置时的两个问题：
 *
 *   1. 壁纸缩放被强制改值 → 壁纸突然放大/缩小一次（已用缓动修正）
 *   2. `NCSwitchController.switchBlur()` 会把「即将离开的那一页」的模糊设成 0，
 *      收起过程中那一页的背景会瞬间变清晰 → 看起来就是闪一下
 *
 * 第 2 点处理：switchBlur 执行完后，把两页的模糊比例都恢复成 1。
 * 只改这一瞬间的数值，不干预模糊状态机；页面真正收起后系统会自己把半径归零。
 */
object ShadeSwitchFix : HookRegister() {

    private var controller: Any? = null
    private var lastStableZoom = 1f
    private var zoomAtSwitchStart = -1f
    private var switchEndedAt = 0L
    private var appliedZoom = 1f
    private var animating = false
    private var lastCallTs = 0L

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
                        val now = android.os.SystemClock.elapsedRealtime()
                        val sw = isSwitching()

                        // 先算这次真正想要的目标值
                        var target = v
                        if (sw) {
                            if (zoomAtSwitchStart < 0f) zoomAtSwitchStart = lastStableZoom
                            target = zoomAtSwitchStart
                        } else if (zoomAtSwitchStart >= 0f) {
                            // 切换刚结束：继续压住，避免结束后被改回去
                            switchEndedAt = now
                            target = zoomAtSwitchStart
                            zoomAtSwitchStart = -1f
                        } else if (now - switchEndedAt < 400L && switchEndedAt > 0L) {
                            target = lastStableZoom
                        } else {
                            lastStableZoom = v
                        }

                        // ROM 在快速下滑时会一帧把 1.0 直接跳到 0.0，
                        // 这里限制每帧最大变化量，让它变成可见的动画
                        val delta = target - appliedZoom
                        // 间隔太久（手势之间）不能按真实时间算，否则一帧就跳完了
                        val rawDt = (now - lastCallTs) / 1000f
                        val dt = if (lastCallTs == 0L || rawDt > 0.05f) 0.016f else rawDt
                        lastCallTs = now

                        // 检测到“一帧跳变”后，用指数缓动追上目标，曲线和左侧通知中心接近
                        if (!animating && kotlin.math.abs(delta) > 0.3f) animating = true
                        val limited = if (animating) {
                            val alpha = 1f - kotlin.math.exp(-dt / 0.10f)
                            val next = appliedZoom + (target - appliedZoom) * alpha
                            if (kotlin.math.abs(target - next) < 0.005f) {
                                animating = false
                                target
                            } else {
                                next
                            }
                        } else {
                            target
                        }
                        appliedZoom = limited
                        param.args[0] = limited
                    }
                }
            })

            // 切换时不要把「离开的那一页」的模糊关掉
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.policy.NCSwitchController")
                XposedBridge.hookAllMethods(cls, "switchBlur", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val dep = cl.loadClass("com.android.systemui.Dependency")
                            val csCls = cl.loadClass("com.android.systemui.statusbar.phone.CentralSurfaces")
                            val cs = XposedHelpers.callStaticMethod(dep, "get", csCls)
                            val npvc = XposedHelpers.callMethod(cs, "getNotificationPanelViewController")
                            XposedHelpers.callMethod(npvc, "setBlurRatio", 1.0f)

                            val cwmCls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowManager")
                            val cwm = XposedHelpers.callStaticMethod(dep, "get", cwmCls)
                            XposedHelpers.callMethod(cwm, "setBlurRatio", 1.0f)
                        }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: switchBlur hook 失败", it, logInRelease = true)
            }

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }
}
