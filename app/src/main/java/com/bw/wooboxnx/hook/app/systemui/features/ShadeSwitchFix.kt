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
    private var zoomAtSwitchStart = -1f
    private var switchEndedAt = 0L
    private var appliedZoom = 1f
    private var animating = false
    private var lastCallTs = 0L

    /** 每块窗口最近一次的模糊半径，切换期间用它把半径冻住 */
    private val blurRadius = HashMap<Any, Int>()

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

            // 左右切换时，系统会把其中一页的模糊半径/遮罩透明度改掉（CCDepthController 里会把
            // 通知页模糊强制设成 0），两页一糊一清、一亮一暗，交叉淡入时就闪、还会「错位」。
            // 这里在切换期间把两块窗口的模糊半径和遮罩透明度都冻在切换前的值。
            runCatching {
                val bu = cl.loadClass("com.android.systemui.statusbar.BlurUtils")
                XposedBridge.hookAllMethods(bu, "applyBlur", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val root = param.args.getOrNull(0) ?: return
                        val radius = param.args.getOrNull(1) as? Int ?: return
                        if (!isSwitching()) {
                            if (radius > 0) blurRadius[root] = radius
                            return
                        }
                        val frozen = blurRadius[root]
                        if (frozen == null) {
                            blurRadius[root] = radius
                        } else {
                            param.args[1] = frozen
                        }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: applyBlur hook 失败", it, logInRelease = true)
            }

            // 遮罩透明度同样冻住
            runCatching {
                val gb = cl.loadClass("com.zte.utils.GaussBlur")
                listOf("updateShadePanelBlur", "updateControlPanelBlur").forEach { name ->
                    XposedBridge.hookAllMethods(gb, name, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isSwitching()) param.result = null
                        }
                    })
                }
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: 遮罩 hook 失败", it, logInRelease = true)
            }

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }
}
