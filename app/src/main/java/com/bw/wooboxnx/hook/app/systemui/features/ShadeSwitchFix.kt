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

    private var maskFade: android.animation.ValueAnimator? = null

    /**
     * 进入的那一页照旧设满模糊（模糊半径、底色都由系统自己算），
     * 只把两页渐变遮罩的 alpha 做成淡入淡出，消掉「啪一下全黑」。
     */
    private fun crossFadeMask(cl: ClassLoader, toControlCenter: Boolean) {
        val dep = cl.loadClass("com.android.systemui.Dependency")
        val cwmCls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowManager")
        val cc = XposedHelpers.callStaticMethod(dep, "get", cwmCls)
        val csCls = cl.loadClass("com.android.systemui.statusbar.phone.CentralSurfaces")
        val cs = XposedHelpers.callStaticMethod(dep, "get", csCls)
        val nc = XposedHelpers.callMethod(cs, "getNotificationPanelViewController")

        // 先让进入页拿到满模糊（这一步和系统原逻辑一致）
        if (toControlCenter) XposedHelpers.callMethod(cc, "setBlurRatio", 1.0f)
        else XposedHelpers.callMethod(nc, "setBlurRatio", 1.0f)

        val gb = cl.loadClass("com.zte.utils.GaussBlur")
        val ccMask = runCatching {
            XposedHelpers.getStaticObjectField(gb, "mCCGaussMaskDrawable") as? android.graphics.drawable.Drawable
        }.getOrNull()
        val ncMask = runCatching {
            XposedHelpers.getStaticObjectField(gb, "mNCGaussMaskDrawable") as? android.graphics.drawable.Drawable
        }.getOrNull()
        val incoming = if (toControlCenter) ccMask else ncMask
        val outgoing = if (toControlCenter) ncMask else ccMask
        if (incoming == null && outgoing == null) return

        maskFade?.cancel()
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 240L }
        anim.addUpdateListener { a ->
            val f = a.animatedValue as Float
            // 两页的遮罩是叠在一起的，简单的线性交叉淡入会让中间那一下变亮（≈0.75）。
            // 用 pow<1 的曲线让两个 alpha 都尽快靠近 1，合成后始终接近满暗，避免「暗-亮-暗」。
            val k = 0.35f
            val aIn = Math.pow(f.toDouble(), k.toDouble()).toFloat()
            val aOut = Math.pow((1f - f).toDouble(), k.toDouble()).toFloat()
            runCatching { incoming?.alpha = (aIn * 255).toInt() }
            runCatching { outgoing?.alpha = (aOut * 255).toInt() }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                runCatching { incoming?.alpha = 255 }
                runCatching { outgoing?.alpha = 0 }
            }
        })
        anim.start()
        maskFade = anim
    }

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

            // 切换越过阈值时，ROM 会一次性把两页的「模糊 + 遮罩透明度」改掉（switchBlur），
            // 进入的那页瞬间变成全黑 —— 就是你看到的那段没有任何过渡的突兀黑。
            // 这里跳过原方法：模糊照旧设满，但把两页渐变遮罩的透明度做成 220ms 交叉淡入淡出。
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.policy.NCSwitchController")
                XposedBridge.hookAllMethods(cls, "switchBlur", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                        val toControlCenter = param.args.getOrNull(0) as? Boolean ?: return
                        runCatching { crossFadeMask(cl, toControlCenter) }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: switchBlur hook 失败", it, logInRelease = true)
            }

            // 通知页切到快速设置时，系统是「先收起通知页，100ms 后再打开快速设置」，
            // 中间会露出一段桌面/黑底。把延迟去掉，让两页重叠，过渡更连续。
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelController")
                XposedBridge.hookAllMethods(cls, "openPanelDelayed", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((param.args.getOrNull(0) as? Long ?: 0L) > 0L) param.args[0] = 0L
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: openPanelDelayed hook 失败", it, logInRelease = true)
            }

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }
}
