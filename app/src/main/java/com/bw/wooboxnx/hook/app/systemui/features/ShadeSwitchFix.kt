package com.bw.wooboxnx.hook.app.systemui.features

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 通知中心 / 快速设置左右切换时的处理：
 *
 *  1. 壁纸缩放会被系统强制改值 → 用缓动补回来（原来就有的逻辑）
 *  2. 换页时前页内容已消失、后页内容还没出现，中间会露出空背景（看起来就是闪一下）
 *     → 系统开始切换时铺一层黑场，切换结束再淡出，做成「淡入淡出黑场」过渡
 *
 * 黑场只是两块窗口里各加一个不拦截触摸的 View，带强制超时兜底，
 * 不会碰模糊 / 面板状态，最坏情况也只是黑一下后自动消失。
 */
object ShadeSwitchFix : HookRegister() {

    private const val VEIL_TAG = "woobox_switch_veil"

    private var controller: Any? = null
    private var lastStableZoom = 1f
    private var zoomAtSwitchStart = -1f
    private var switchEndedAt = 0L
    private var appliedZoom = 1f
    private var animating = false
    private var lastCallTs = 0L

    private val main = Handler(Looper.getMainLooper())
    private val veils = ArrayList<WeakReference<View>>()
    private var veilVisible = false
    private var safety: Runnable? = null

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

            // 切换开始 / 结束的信号
            XposedBridge.hookAllMethods(cc, "setNcSwitching", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val on = param.args.getOrNull(0) as? Boolean ?: return
                    if (on) showVeil() else hideVeil(0L)
                }
            })

            // 给两块窗口各挂一层黑场
            listOf(
                "com.android.systemui.shade.NotificationShadeWindowView",
                "com.zte.controlcenter.ControlPanelWindowView"
            ).forEach { name ->
                runCatching {
                    val cls = cl.loadClass(name)
                    XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val root = param.thisObject as? ViewGroup ?: return
                            runCatching { attachVeil(root) }
                        }
                    })
                }
            }

            // 壁纸缩放缓动
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

                        var target = v
                        if (sw) {
                            if (zoomAtSwitchStart < 0f) zoomAtSwitchStart = lastStableZoom
                            target = zoomAtSwitchStart
                        } else if (zoomAtSwitchStart >= 0f) {
                            switchEndedAt = now
                            target = zoomAtSwitchStart
                            zoomAtSwitchStart = -1f
                        } else if (now - switchEndedAt < 400L && switchEndedAt > 0L) {
                            target = lastStableZoom
                        } else {
                            lastStableZoom = v
                        }

                        val delta = target - appliedZoom
                        val rawDt = (now - lastCallTs) / 1000f
                        val dt = if (lastCallTs == 0L || rawDt > 0.05f) 0.016f else rawDt
                        lastCallTs = now

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

            // 两页交接之间的 100ms 等待去掉，减少黑场时间
            runCatching {
                val cpc = cl.loadClass("com.zte.controlcenter.ControlPanelController")
                XposedBridge.hookAllMethods(cpc, "openPanelDelayed", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((param.args.getOrNull(0) as? Long ?: 0L) > 0L) param.args[0] = 0L
                    }
                })
            }

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }

    // ---------------------------------------------------------------- 黑场

    private fun attachVeil(root: ViewGroup) {
        if (root.findViewWithTag<View>(VEIL_TAG) != null) return
        val v = View(root.context).apply {
            tag = VEIL_TAG
            setBackgroundColor(0xFF000000.toInt())
            isClickable = false
            isFocusable = false
            alpha = 0f
            visibility = View.GONE
        }
        runCatching { root.addView(v, ViewGroup.LayoutParams(-1, -1)) }
        veils.add(WeakReference(v))
    }

    private fun liveVeils(): List<View> {
        veils.removeAll { it.get() == null }
        return veils.mapNotNull { it.get() }
    }

    private fun animScale(): Float = runCatching {
        val ctx = liveVeils().firstOrNull()?.context ?: return@runCatching 1f
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
    }.getOrDefault(1f).coerceIn(0.5f, 10f)

    private fun showVeil() {
        val views = liveVeils()
        if (views.isEmpty()) return
        veilVisible = true
        safety?.let { main.removeCallbacks(it) }
        val tIn = (110f * animScale()).toLong()
        views.forEach { v ->
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(tIn).setInterpolator(DecelerateInterpolator()).start()
        }
        // 兜底：8 秒内没收到结束信号也强制撤掉
        safety = Runnable { hideVeil(0L) }.also { main.postDelayed(it, 8000L) }
    }

    private fun hideVeil(delay: Long) {
        safety?.let { main.removeCallbacks(it) }
        safety = null
        if (!veilVisible) {
            // 就算没显示过，也保证状态是隐藏的
            liveVeils().forEach { v -> v.animate().cancel(); v.alpha = 0f; v.visibility = View.GONE }
            return
        }
        veilVisible = false
        val tOut = (180f * animScale()).toLong()
        val action = Runnable {
            liveVeils().forEach { v ->
                v.animate().cancel()
                v.animate().alpha(0f).setDuration(tOut).setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        v.alpha = 0f
                        v.visibility = View.GONE
                    }.start()
            }
        }
        if (delay > 0) main.postDelayed(action, delay) else action.run()
    }
}
