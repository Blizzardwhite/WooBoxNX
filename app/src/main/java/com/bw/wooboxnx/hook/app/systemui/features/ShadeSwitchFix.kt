package com.bw.wooboxnx.hook.app.systemui.features

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.animation.ValueAnimator
import android.widget.ImageView
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 通知面板 / 快速设置面板的动画手感修整。
 *
 *  1. 壁纸缩放缓动（右侧快速下滑时系统会把缩放一帧跳到 0）
 *  2. 模糊半径按速率平滑（快速下拉时系统会一帧设到目标值）
 *  3. 黑色渐变遮罩做了渐入渐出（系统是直接置 0/1）
 *  4. 快速设置：收起时不让内容提前隐藏，磁贴在 60% 高度时淡出，
 *     窗口延迟 200ms 再隐藏，避免"瞬间没掉"或"控件往下滑"
 *  5. 通知中心：点空白处收起（instantCollapse）改成动画收起
 *
 * 左右切换（ncSwitching）期间全部跳过，避免两页互相打架导致闪烁。
 */
object ShadeSwitchFix : HookRegister() {

    private val main = Handler(Looper.getMainLooper())

    private var controller: Any? = null
    private var gaussBlurCls: Class<*>? = null
    private var ccZoomCall = false

    // ---- 壁纸缩放
    private var lastStableZoom = 1f
    private var zoomAtSwitchStart = -1f
    private var switchEndedAt = 0L
    private var appliedZoom = 1f
    private var zoomAnimating = false
    private var lastZoomTs = 0L

    // ---- 触摸追踪
    private var sawDown = false
    private var dragLastY = 0f
    private var dragLastTs = 0L
    private var dragVel = 0f
    private var closingByDrag = false
    private var bypassHide = false
    private var lastExpandHeight = 0f
    private var widgetsHidden = false
    private var hideToken = 0
    private var ccWindowView: WeakReference<View>? = null

    // ---- 模糊平滑
    private class BlurState(val root: Any) {
        var utils: Any? = null
        var applied = -1
        var target = 0
        var opaque = false
        var lastTs = 0L
        var isCc = false
    }

    private val blurStates = HashMap<Any, BlurState>()
    private var blurTicking = false
    private var inBlurTicker = false

    private val rateCc = 400f
    private val rateNc = 400f

    // ---- 遮罩渐变
    private var ncApplied = -1f
    private var ncGoal = 0f
    private var ccApplied = -1f
    private var ccGoal = 0f
    private var ncActive = false
    private var ccActive = false
    private var maskLastTs = 0L
    private var maskTicking = false

    private val maskFadeMs = 50f

    private fun isSwitching(): Boolean = runCatching {
        XposedHelpers.callMethod(controller, "getNcSwitching") as? Boolean ?: false
    }.getOrDefault(false)

    /** 系统动画倍速（开发者选项 5x 等），让我们的固定时长也跟着缩放 */
    private fun animScale(view: View?): Float = runCatching {
        android.provider.Settings.Global.getFloat(
            view!!.context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
    }.getOrDefault(1f).coerceIn(1f, 10f)

    override fun init() = hasEnable("fix_shade_switch") {
        runCatching {
            val cl = getDefaultCL()

            // 切换状态来源
            runCatching {
                val cc = cl.loadClass("com.zte.controlcenter.CCDepthController")
                XposedBridge.hookAllConstructors(cc, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        controller = param.thisObject
                    }
                })
                // 标记：下一次 setNotificationShadeZoom 是快速设置发起的
                XposedBridge.hookAllMethods(cc, "updateBlurCallback", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        ccZoomCall = true
                    }
                })
            }

            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? View)?.let { ccWindowView = WeakReference(it) }
                    }
                })
            }
            hookWallpaperZoom(cl)
            hookBlurSmoothing(cl)
            hookMaskFade(cl)
            hookQuickSettingsClose(cl)
            hookNotificationCenter(cl)

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }

    // ================================================================ 壁纸缩放

    private fun hookWallpaperZoom(cl: ClassLoader) {
        runCatching {
            val wallpaper = cl.loadClass("com.android.systemui.util.WallpaperController")
            val setZoom = wallpaper.getDeclaredMethod(
                "setNotificationShadeZoom", Float::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(setZoom, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        var v = param.args.getOrNull(0) as? Float ?: return
                        if (ccZoomCall) {
                            ccZoomCall = false
                            // 套用通知中心的做法：缩放由展开比例决定（zoom = 1 - 展开比），
                            // 不再使用快速设置按原始半径算出的值
                            runCatching {
                                val exp = XposedHelpers.getFloatField(controller, "shadeExpansion") as Float
                                v = 1f - exp.coerceIn(0f, 1f)
                            }
                        }
                        val now = SystemClock.elapsedRealtime()
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
                        val rawDt = (now - lastZoomTs) / 1000f
                        val dt = if (lastZoomTs == 0L || rawDt > 0.05f) 0.016f else rawDt
                        lastZoomTs = now

                        if (!zoomAnimating && kotlin.math.abs(delta) > 0.3f) zoomAnimating = true
                        val limited = if (zoomAnimating) {
                            val alpha = 1f - kotlin.math.exp(-dt / 0.10f)
                            val next = appliedZoom + (target - appliedZoom) * alpha
                            if (kotlin.math.abs(target - next) < 0.005f) {
                                zoomAnimating = false
                                target
                            } else next
                        } else target
                        appliedZoom = limited
                        param.args[0] = limited
                    }
                }
            })
        }.onFailure { Log.px("W", "ShadeSwitchFix: 壁纸缩放 hook 失败", it, logInRelease = true) }
    }

    // ================================================================ 模糊平滑

    private fun hookBlurSmoothing(cl: ClassLoader) {
        runCatching {
            val bu = cl.loadClass("com.android.systemui.statusbar.BlurUtils")
            XposedBridge.hookAllMethods(bu, "applyBlur", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { smoothBlur(param) }
                }
            })
        }.onFailure { Log.px("W", "ShadeSwitchFix: applyBlur hook 失败", it, logInRelease = true) }
    }

    private fun smoothBlur(param: XC_MethodHook.MethodHookParam) {
        if (inBlurTicker) return
        val root = param.args.getOrNull(0) ?: return
        val radius = param.args.getOrNull(1) as? Int ?: return
        val opaque = param.args.getOrNull(2) as? Boolean ?: false

        val view = runCatching {
            XposedHelpers.getObjectField(root, "mView") as? View
        }.getOrNull() ?: return
        val name = view.javaClass.name
        val isCc = name.contains("ControlPanel")
        if (!isCc && !name.contains("NotificationShadeWindowView")) return

        val state = blurStates.getOrPut(root) { BlurState(root) }
        state.utils = param.thisObject
        state.isCc = isCc
        val now = SystemClock.elapsedRealtime()

        // 左右切换期间不干预
        if (isSwitching()) {
            state.applied = radius
            state.target = radius
            state.opaque = opaque
            state.lastTs = now
            return
        }

        // 目标为 0（已完全收起/隐藏）：同步把平滑状态归零，
        // 这样下一次展开一定从 0 开始渐变，也不会在拖动收起时被误清零
        if (radius <= 0) {
            state.applied = 0
            state.target = 0
            state.opaque = opaque
            state.lastTs = now
            return
        }

        if (state.applied < 0) state.applied = radius
        state.target = radius
        state.opaque = opaque

        var rate = (if (isCc) rateCc else rateNc) / animScale(view)
        // 收起方向（目标变小）加快，保证模糊在面板消失前淡完
        if (radius < state.applied) rate *= 2.5f

        val dt = if (state.lastTs == 0L) 16L else (now - state.lastTs).coerceIn(4L, 64L)
        state.lastTs = now
        val step = (rate * dt / 1000f).toInt().coerceAtLeast(2)
        val d = radius - state.applied
        if (kotlin.math.abs(d) <= step) {
            state.applied = radius
        } else {
            state.applied += if (d > 0) step else -step
            param.args[1] = state.applied
            if (!blurTicking) {
                blurTicking = true
                main.postDelayed(blurTicker, 16L)
            }
        }
    }

    private val blurTicker = object : Runnable {
        override fun run() {
            var pending = false
            for (s in blurStates.values.toList()) {
                val d = s.target - s.applied
                if (kotlin.math.abs(d) <= 3) {
                    s.applied = s.target
                    continue
                }
                var rate = (if (s.isCc) rateCc else rateNc)
                if (d < 0) rate *= 2.5f
                val step = (rate * 0.016f).toInt().coerceAtLeast(2)
                s.applied += if (d > 0) step else -step
                inBlurTicker = true
                runCatching {
                    val u = s.utils
                    if (u != null) XposedHelpers.callMethod(u, "applyBlur", s.root, s.applied, s.opaque)
                }
                inBlurTicker = false
                pending = true
            }
            blurTicking = pending
            if (pending) main.postDelayed(this, 16L)
        }
    }

    // ================================================================ 遮罩渐变

    private fun hookMaskFade(cl: ClassLoader) {
        runCatching {
            val gb = cl.loadClass("com.zte.utils.GaussBlur")
            gaussBlurCls = gb
            XposedBridge.hookAllMethods(gb, "updateShadePanelBlur", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { trackMask(false, param) }
                }
            })
            XposedBridge.hookAllMethods(gb, "updateControlPanelBlur", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { trackMask(true, param) }
                }
            })
        }.onFailure { Log.px("W", "ShadeSwitchFix: 遮罩 hook 失败", it, logInRelease = true) }
    }

    private fun trackMask(isCc: Boolean, param: XC_MethodHook.MethodHookParam) {
        val f = param.args.getOrNull(1) as? Float ?: return
        val goal = f.coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()

        if (isSwitching()) {
            if (isCc) { ccApplied = goal; ccGoal = goal; ccActive = false } else { ncApplied = goal; ncGoal = goal; ncActive = false }
            pushMask(isCc)
            return
        }

        val dt = if (maskLastTs == 0L) 16L else (now - maskLastTs).coerceIn(4L, 64L)
        maskLastTs = now
        val step = (dt / maskFadeMs).coerceAtLeast(0.005f)

        if (isCc) {
            if (ccApplied < 0f) ccApplied = goal
            ccGoal = goal
            val d = ccGoal - ccApplied
            ccApplied = if (kotlin.math.abs(d) <= step) ccGoal else ccApplied + if (d > 0) step else -step
            ccActive = kotlin.math.abs(ccGoal - ccApplied) > 0.01f
            pushMask(true)
        } else {
            if (ncApplied < 0f) ncApplied = goal
            ncGoal = goal
            val d = ncGoal - ncApplied
            ncApplied = if (kotlin.math.abs(d) <= step) ncGoal else ncApplied + if (d > 0) step else -step
            ncActive = kotlin.math.abs(ncGoal - ncApplied) > 0.01f
            pushMask(false)
        }

        if (!maskTicking && (ncActive || ccActive)) {
            maskTicking = true
            main.postDelayed(maskTicker, 16L)
        }
    }

    private val maskTicker = object : Runnable {
        override fun run() {
            var busy = false
            val step = 16f / maskFadeMs
            if (ncActive) {
                val d = ncGoal - ncApplied
                if (kotlin.math.abs(d) <= 0.01f) {
                    ncApplied = ncGoal
                    ncActive = false
                } else {
                    ncApplied = (ncApplied + if (d > 0) step else -step).coerceIn(0f, 1f)
                    busy = true
                }
                pushMask(false)
            }
            if (ccActive) {
                val d = ccGoal - ccApplied
                if (kotlin.math.abs(d) <= 0.01f) {
                    ccApplied = ccGoal
                    ccActive = false
                } else {
                    ccApplied = (ccApplied + if (d > 0) step else -step).coerceIn(0f, 1f)
                    busy = true
                }
                pushMask(true)
            }
            maskTicking = busy
            if (busy) main.postDelayed(this, 16L)
        }
    }

    /** 只改对应那一层遮罩，绝不同时动两块 */
    private fun pushMask(isCc: Boolean) {
        val cls = gaussBlurCls ?: return
        val v = if (isCc) ccApplied else ncApplied
        if (v < 0f) return
        val field = if (isCc) "mCCGaussMaskDrawable" else "mNCGaussMaskDrawable"
        runCatching {
            val d = XposedHelpers.getStaticObjectField(cls, field) as? Drawable ?: return
            d.alpha = (v.coerceIn(0f, 1f) * 255).toInt()
        }
    }

    // ================================================================ 快速设置收起

    private fun hookQuickSettingsClose(cl: ClassLoader) {
        // 高度动画时长：展开按速度、收起固定 300ms（跟随动画倍速）
        runCatching {
            val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
            XposedBridge.hookAllMethods(cls, "createHeightChangeAnimator", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = param.args.getOrNull(0) as? Int ?: 0
                    val anim = XposedHelpers.getObjectField(param.thisObject, "mHeightChangeAnimator")
                        as? ValueAnimator ?: return
                    val scale = animScale(param.thisObject as? View)
                    val base = if (target != 0) {
                        val v = kotlin.math.abs(dragVel)
                        if (v < 100f) 150L else (330f - v / 30f).toInt().coerceIn(150, 330).toLong()
                    } else 200L
                    anim.duration = (base * scale).toLong()
                }
            })
        }

        // 所有"立即收起"都改成动画收起
        runCatching {
            val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
            XposedBridge.hookAllMethods(cls, "collapsePanel", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val first = param.args.getOrNull(0) as? Boolean ?: return
                    if (!first) param.args[0] = true
                }
            })
            XposedBridge.hookAllMethods(cls, "collapsePanelImmediately", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val inst = param.thisObject
                    val listener = runCatching {
                        XposedHelpers.getObjectField(inst, "mCollapseListener")
                    }.getOrNull() ?: return
                    param.result = null
                    runCatching { XposedHelpers.callMethod(inst, "createAndStartAnimator", 0, listener) }
                }
            })
            // 窗口隐藏后立刻把内容 alpha 恢复，避免下次打开空白。
            // 不做延迟隐藏：那样窗口会残留一小段时间，挡住桌面触摸。
            XposedBridge.hookAllMethods(cls, "hideControlCenterWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val inst = param.thisObject
                        val content = XposedHelpers.getObjectField(inst, "mContent") as? View
                        content?.animate()?.cancel()
                        content?.alpha = 1f
                    }
                    // 窗口已完全隐藏：把模糊平滑状态归零，
                    // 保证下次展开从 0 开始渐变
                    blurStates.values.forEach { if (it.isCc) it.applied = 0 }
                }
            })        }.onFailure { Log.px("W", "ShadeSwitchFix: 收起 hook 失败", it, logInRelease = true) }

        // 触摸事件：速度追踪 + 快速甩动接管
        runCatching {
            val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
            XposedBridge.hookAllMethods(cls, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val e = param.args.getOrNull(0) as? MotionEvent ?: return
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            dragLastY = e.rawY
                            dragLastTs = 0L
                            dragVel = 0f
                            closingByDrag = false
                            sawDown = true
                            // 注意：这里不能动 hideToken。
                            // 收起后窗口还会存在一小会儿，此时在桌面上的滑动也会走到这里，
                            // 如果取消待执行的隐藏，窗口就会一直挡着桌面。
                            // 模糊状态也不能在这里清零：面板已经打开时按下是要"收起"，
                            // 清零会让画面先突然变清晰（变实）再重新变糊。
                            ccApplied = -1f
                            ccActive = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val now = SystemClock.elapsedRealtime()
                            if (dragLastTs > 0L) {
                                val dt = now - dragLastTs
                                if (dt in 2L..80L) dragVel = (e.rawY - dragLastY) / (dt / 1000f)
                            }
                            if (e.rawY - dragLastY < -30f) closingByDrag = true
                            dragLastY = e.rawY
                            dragLastTs = now
                        }
                    }
                }
            })
            XposedBridge.hookAllMethods(cls, "handleMotionEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val e = param.args.getOrNull(0) as? MotionEvent ?: return
                    // 让原生跟手分支始终生效
                    runCatching {
                        XposedHelpers.setBooleanField(param.thisObject, "mIsGetSelfEvent", false)
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (sawDown && dragVel < -800f) {
                            param.result = true
                            runCatching { XposedHelpers.callMethod(param.thisObject, "collapsePanel", true) }
                        }
                        sawDown = false
                    }
                }
            })
        }.onFailure { Log.px("W", "ShadeSwitchFix: 触摸 hook 失败", it, logInRelease = true) }

        // 拖动收起：内容延迟隐藏 + 60% 时触发磁贴淡出
        runCatching {
            val viewCls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
            val contentCls = cl.loadClass("com.zte.controlcenter.ControlPanelContentView")
            XposedBridge.hookAllMethods(viewCls, "updateExpandHeight", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val h = (param.args.getOrNull(0) as? Float) ?: 0f
                    // 高度在变大 = 正在展开：清掉收起状态的标记，
                    // 否则从状态栏下拉时（CC 窗口收不到 DOWN）会残留上一轮的收起状态，
                    // 导致打开后内容不显示。
                    if (h > lastExpandHeight) {
                        closingByDrag = false
                        widgetsHidden = false
                        // 展开时复位快速设置的模糊/遮罩平滑状态，
                        // 避免上一次收起残留的 0 值导致"下拉没有模糊/遮罩"
                        if (lastExpandHeight <= 0.5f) {
                            blurStates.values.forEach { if (it.isCc) it.applied = 0 }
                            ccApplied = -1f
                            ccActive = false
                            // 新的一次展开开始：作废上一轮还没执行的延迟隐藏
                            hideToken++
                        }
                    }
                    lastExpandHeight = h
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!closingByDrag) return
                    val inst = param.thisObject
                    val th = runCatching { XposedHelpers.getIntField(inst, "THRESHOLD") }.getOrDefault(105)
                    if (lastExpandHeight > th * 0.6f) return
                    closingByDrag = false
                    if (widgetsHidden) return
                    widgetsHidden = true
                    runCatching {
                        val content = XposedHelpers.getObjectField(inst, "mContent")
                        val panelView = XposedHelpers.getObjectField(content, "mControlCenterPanelView")
                        val pvc = XposedHelpers.callMethod(panelView, "getPanelViewController")
                        val anim = XposedHelpers.callMethod(pvc, "getPanelAnimator")
                        runCatching { XposedHelpers.setBooleanField(anim, "showing", true) }
                        XposedHelpers.callMethod(anim, "animateShowPanel", false)
                        XposedHelpers.setBooleanField(inst, "mContentShowing", false)
                    }
                }
            })
            XposedBridge.hookAllMethods(contentCls, "hideContent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (closingByDrag) param.result = null
                }
            })
        }.onFailure { Log.px("W", "ShadeSwitchFix: 内容隐藏 hook 失败", it, logInRelease = true) }

        // 收起时不要复位控件位移（会产生"往下滑"的动画）
        runCatching {
            val animCls = cl.loadClass("com.zte.controlcenter.animation.ControlCenterPanelAnimator")
            XposedBridge.hookAllMethods(animCls, "startBackAnimOnUp", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (closingByDrag || bypassHide) param.result = null
                }
            })
            XposedBridge.hookAllMethods(animCls, "updateOverExpandHeight", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (closingByDrag || bypassHide) param.result = null
                }
            })
        }.onFailure { Log.px("W", "ShadeSwitchFix: 控件位移 hook 失败", it, logInRelease = true) }
    }

    // ================================================================ 通知中心

    private fun hookNotificationCenter(cl: ClassLoader) {
        runCatching {
            val cls = cl.loadClass("com.android.systemui.shade.ShadeControllerImpl")
            XposedBridge.hookAllMethods(cls, "collapseShade", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val animated = param.args.getOrNull(0) as? Boolean ?: return
                    if (!animated) param.args[0] = true
                    // 收起动画期间放行触摸，桌面可以马上操作
                    setNcTouchable(false)
                }
            })
        }
        runCatching {
            val npvc = cl.loadClass("com.android.systemui.shade.NotificationPanelViewController")
            XposedBridge.hookAllMethods(npvc, "instantCollapse", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    runCatching { XposedHelpers.callMethod(param.thisObject, "collapse", true, 0f) }
                    setNcTouchable(false)
                }
            })
            // 其它收起路径（back / 点空白 / 甩动）
            XposedBridge.hookAllMethods(npvc, "collapse", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    setNcTouchable(false)
                }
            })
            XposedBridge.hookAllMethods(npvc, "fling", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val expand = param.args.getOrNull(1) as? Boolean ?: return
                    if (!expand) setNcTouchable(false)
                }
            })
            // 通知中心收起动画固定 220ms（与快速设置一致）
            XposedBridge.hookAllMethods(npvc, "flingToHeight", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val expand = param.args.getOrNull(1) as? Boolean ?: return@runCatching
                        if (expand) return@runCatching
                        val anim = XposedHelpers.getObjectField(param.thisObject, "mHeightAnimator")
                            as? android.animation.ValueAnimator ?: return@runCatching
                        val scale = animScale(param.thisObject as? View)
                        anim.duration = (200f * scale).toLong()
                    }
                }
            })
            // 面板完全展开时恢复触摸
            XposedBridge.hookAllMethods(npvc, "onHeightUpdated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val f = XposedHelpers.getFloatField(param.thisObject, "mExpandedFraction")
                        if (f >= 0.99f) setNcTouchable(true)
                    }
                }
            })
        }.onFailure { Log.px("W", "ShadeSwitchFix: 通知中心收起 hook 失败", it, logInRelease = true) }
    }

    // ---------------------------------------------------------------- 收起时放行触摸

    private var ccTouchToken = 0
    private var ncTouchToken = 0

    /** 快速设置窗口是否接收触摸；放行后 260ms 自动恢复，避免切换页面时触摸漏到桌面 */
    private fun setCcTouchable(touchable: Boolean) {
        ccTouchToken++
        val token = ccTouchToken
        runCatching {
            val inst = ccWindowView?.get() ?: return@runCatching
            val wm = XposedHelpers.getObjectField(inst, "mControlPanelWindowManager") ?: return@runCatching
            val lp = XposedHelpers.getObjectField(wm, "mLpChanged") as? android.view.WindowManager.LayoutParams ?: return@runCatching
            val flag = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val flags = if (touchable) lp.flags and flag.inv() else lp.flags or flag
            if (flags != lp.flags) {
                lp.flags = flags
                XposedHelpers.callMethod(wm, "apply")
            }
        }
        if (!touchable) {
            // 通知中心窗口在下面一层，也会吃掉触摸，一起放行
            runCatching { setNcTouchable(false) }
            main.postDelayed({
                if (token == ccTouchToken) setCcTouchable(true)
            }, 50L)
        }
    }

    /** 通知中心窗口是否接收触摸；放行后 260ms 自动恢复 */
    private fun setNcTouchable(touchable: Boolean) {
        ncTouchToken++
        val token = ncTouchToken
        runCatching {
            val ctrlCls = getDefaultCL().loadClass("com.android.systemui.statusbar.NotificationShadeWindowController")
            val dep = getDefaultCL().loadClass("com.android.systemui.Dependency")
            val ctrl = XposedHelpers.callStaticMethod(dep, "get", ctrlCls) ?: return@runCatching
            XposedHelpers.callMethod(ctrl, "setNotTouchable", !touchable)
        }
        if (!touchable) {
            main.postDelayed({
                if (token == ncTouchToken) setNcTouchable(true)
            }, 50L)
        }
    }
}
