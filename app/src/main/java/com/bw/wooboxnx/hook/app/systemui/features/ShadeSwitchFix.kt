package com.bw.wooboxnx.hook.app.systemui.features

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 左右切换通知中心 / 快速设置时，壁纸缩放会被系统强制改值（右侧快速下滑时一帧从 1.0 跳到 0.0），
 * 表现为壁纸突然放大/缩小一次。这里把变化限制成指数缓动，和左侧通知中心的曲线接近。
 *
 * 注意：只做这一件事。换页时的黑闪来自系统「先收起一页、再打开另一页」的两窗口设计，
 * 试过冻结模糊、遮罩淡入淡出、延时收起、黑场过渡、瞬时切换、截图冻结等方案，
 * 都会带来别的问题（白闪 / 模糊残留 / 面板不可用），所以保持系统原样。
 */
object ShadeSwitchFix : HookRegister() {

    private var upSwipe = false
    private var dragLastY = 0f
    private var dragLastTs = 0L
    private var dragVel = 0f
    private var lastExpandHeight = 0f
    private var closingByDrag = false
    private val panelAnims = ArrayList<java.lang.ref.WeakReference<android.animation.ValueAnimator>>()
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

            // 两块面板的模糊都做速率限制：系统在快速下拉时会把模糊半径一帧设到目标值，
            // 这里按固定速度推进（约 500ms 从 0 到满），让模糊是渐变的。
            // 目标为 0 时立即生效，不会残留；补帧逻辑保证一定走到目标值。
            runCatching {
                val bu = cl.loadClass("com.android.systemui.statusbar.BlurUtils")
                XposedBridge.hookAllMethods(bu, "applyBlur", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { smoothBlur(param) }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: applyBlur hook 失败", it, logInRelease = true)
            }


            // 快速设置收起时有些路径走的是「立即收起」，所以看不到过渡。
            // 这里统一改成走 250ms 的收起动画。
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(cls, "collapsePanel", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val first = param.args.getOrNull(0) as? Boolean ?: return
                        if (!first) param.args[0] = true
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: collapsePanel hook 失败", it, logInRelease = true)
            }

            // 有些收起路径直接调 collapsePanelImmediately()，看起来就是"啪"一下没了。
            // 改成走同一套 250ms 收起动画。
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(cls, "collapsePanelImmediately", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val inst = param.thisObject
                        val listener = runCatching {
                            XposedHelpers.getObjectField(inst, "mCollapseListener")
                        }.getOrNull() ?: return
                        param.result = null
                        runCatching {
                            XposedHelpers.callMethod(inst, "createAndStartAnimator", 0, listener)
                        }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: 立即收起 hook 失败", it, logInRelease = true)
            }

            // 上滑拖动交给系统原生处理（面板跟手）；只额外记录速度
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(cls, "handleMotionEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val e = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
                        // dispatchTouchEvent 会把 mIsGetSelfEvent 置 true，导致：
                        //   1. 跟手分支被跳过
                        //   2. 高度到 0 时立刻触发 onAnimationEnd（瞬间收起）
                        // 每次事件都置回 false，让跟手逻辑始终生效。
                        runCatching {
                            XposedHelpers.setBooleanField(param.thisObject, "mIsGetSelfEvent", false)
                        }
                        when (e.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                dragLastY = e.rawY
                                dragLastTs = 0L
                                dragVel = 0f
                                closingByDrag = false
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (dragLastTs > 0L) {
                                    val dt = now - dragLastTs
                                    if (dt in 2L..80L) dragVel = (e.rawY - dragLastY) / (dt / 1000f)
                                }
                                if (e.rawY - dragLastY < -30f) closingByDrag = true
                                dragLastY = e.rawY
                                dragLastTs = now

                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                // 快速向上甩动：系统会走自己的“甩出去”动画（一帧收掉）。
                                // 这里接管，改用和慢速拖动一样的 300ms 收起动画。
                                if (dragVel < -800f) {
                                    param.result = true
                                    runCatching { XposedHelpers.callMethod(param.thisObject, "collapsePanel", true) }
                                }
                            }
                        }
                    }
                })
            }
            // 从桌面下拉时，手势不一定经过 handleMotionEvent，速度采不到 → 动画吃最大时长。
            // 这里在窗口的 dispatchTouchEvent 里也采一次速度，保证两条路径一致。
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(cls, "dispatchTouchEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val e = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
                        when (e.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                dragLastY = e.rawY
                                dragLastTs = 0L
                                dragVel = 0f
                                closingByDrag = false
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val now = android.os.SystemClock.elapsedRealtime()
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
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: dispatchTouchEvent hook 失败", it, logInRelease = true)
            }

            // 黑色半透明渐变遮罩：系统直接置 0/1，这里做成渐入渐出。
            // 注意只改 drawable 的 alpha，不改系统方法的入参，避免影响展开状态机。
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
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: 遮罩 hook 失败", it, logInRelease = true)
            }

            // 点空白处收起时系统走的是「立即收起」，没有动画。改成走动画收起。
            runCatching {
                val cls = cl.loadClass("com.android.systemui.shade.ShadeControllerImpl")
                XposedBridge.hookAllMethods(cls, "collapseShade", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val animated = param.args.getOrNull(0) as? Boolean ?: return
                        if (!animated) param.args[0] = true
                    }
                })
                // 点空白处走的是 instantCollapse()，直接消失。改成走动画收起。
                runCatching {
                    val npvc = cl.loadClass("com.android.systemui.shade.NotificationPanelViewController")
                    XposedBridge.hookAllMethods(npvc, "instantCollapse", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                            runCatching { XposedHelpers.callMethod(param.thisObject, "collapse", true, 0f) }
                        }
                    })
                }
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: 通知中心立即收起 hook 失败", it, logInRelease = true)
            }

            // 只在上滑拖动收起这条路径上推迟隐藏内容：其他路径（打开、切换、点空白）完全不受影响
            runCatching {
                val viewCls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(viewCls, "updateExpandHeight", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        lastExpandHeight = (param.args.getOrNull(0) as? Float) ?: 0f
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!closingByDrag) return
                        val inst = param.thisObject
                        val th = runCatching {
                            XposedHelpers.getIntField(inst, "THRESHOLD")
                        }.getOrDefault(105)
                        if (lastExpandHeight > th * 0.6f) return
                        closingByDrag = false
                        runCatching {
                            val content = XposedHelpers.getObjectField(inst, "mContent")
                            val showing = XposedHelpers.getBooleanField(inst, "mContentShowing")
                            if (showing) {
                                XposedHelpers.callMethod(content, "hideContent")
                                XposedHelpers.setBooleanField(inst, "mContentShowing", false)
                            }
                        }
                    }
                })
                val contentCls = cl.loadClass("com.zte.controlcenter.ControlPanelContentView")
                XposedBridge.hookAllMethods(contentCls, "hideContent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (closingByDrag) param.result = null
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: 拖动收起隐藏 hook 失败", it, logInRelease = true)
            }

            // 快速甩动收起走的是 hideControlCenterWindow()（直接隐藏窗口，无动画）。
            // 只要面板还有高度，就改成走 300ms 收起动画。
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(cls, "hideControlCenterWindow", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val inst = param.thisObject
                        val h = runCatching {
                            XposedHelpers.getFloatField(inst, "mExpandHeight")
                        }.getOrDefault(0f)
                        if (h <= 1f) return
                        param.result = null
                        runCatching { XposedHelpers.callMethod(inst, "collapsePanel", true) }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: hideControlCenterWindow hook 失败", it, logInRelease = true)
            }

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }

    // ---------------------------------------------------------------- 模糊平滑

    private class BlurState(val root: Any) {
        var utils: Any? = null
        var applied = -1
        var target = 0
        var opaque = false
        var lastTs = 0L
    }

    private val blurStates = HashMap<Any, BlurState>()
    private var ticking = false
    private var inTicker = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 每秒最多变化多少像素（满值 200px，约 500ms） */
    // 快速设置用更快的淡入，通知中心保持之前"合适"的速度
    private val rateCc = 900f
    private val rateNc = 400f

    private val ticker = object : Runnable {
        override fun run() {
            var pending = false
            for (s in blurStates.values.toList()) {

                val d = s.target - s.applied
                if (kotlin.math.abs(d) <= 3) {
                    s.applied = s.target
                    continue
                }
                val rate = if (isCcRoot(s.root)) rateCc else rateNc
                val step = (rate * 0.016f).toInt().coerceAtLeast(2)
                s.applied += if (d > 0) step else -step
                inTicker = true
                runCatching {
                    val u = s.utils
                    if (u != null) XposedHelpers.callMethod(u, "applyBlur", s.root, s.applied, s.opaque)
                }
                inTicker = false
                pending = true
            }
            ticking = pending
            if (pending) handler.postDelayed(this, 16L)
        }
    }

    /** 系统动画倍速（开发者选项里的 5x 等），用来让我们的固定时长也跟着缩放 */
    private fun animScale(view: android.view.View?): Float = runCatching {
        android.provider.Settings.Global.getFloat(
            view!!.context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
    }.getOrDefault(1f).coerceIn(1f, 10f)

    private fun isCcRoot(root: Any): Boolean = runCatching {
        (XposedHelpers.getObjectField(root, "mView") as? android.view.View)?.javaClass?.name?.contains("ControlPanel") == true
    }.getOrDefault(false)

    private fun smoothBlur(param: XC_MethodHook.MethodHookParam) {
        if (inTicker) return
        val root = param.args.getOrNull(0) ?: return
        val radius = param.args.getOrNull(1) as? Int ?: return
        val opaque = param.args.getOrNull(2) as? Boolean ?: false

        // 只处理通知面板 / 快速设置这两个窗口
        val name = runCatching {
            (XposedHelpers.getObjectField(root, "mView") as? android.view.View)?.javaClass?.name
        }.getOrNull() ?: return
        if (!name.contains("NotificationShadeWindowView") && !name.contains("ControlPanelWindowView")) return

        val state = blurStates.getOrPut(root) { BlurState(root) }
        state.utils = param.thisObject
        val now = android.os.SystemClock.elapsedRealtime()

        // 左右切换期间不做平滑：那段时间两页会互相交接模糊，
        // 再按速率推进就会看到背景模糊在慢慢变化。
        if (isSwitching()) {
            state.applied = radius
            state.target = radius
            state.opaque = opaque
            state.lastTs = now
            return
        }

        if (state.applied < 0) state.applied = radius
        state.target = radius
        state.opaque = opaque

        val dt = if (state.lastTs == 0L) 16L else (now - state.lastTs).coerceIn(4L, 64L)
        state.lastTs = now
        val base = if (name.contains("ControlPanel")) rateCc else rateNc
        var rate = base / animScale(runCatching { XposedHelpers.getObjectField(root, "mView") as? android.view.View }.getOrNull())
        // 收起方向（目标变小）加快，保证模糊在面板消失前淡完
        if (radius < state.applied) rate *= 2.5f
        val step = (rate * dt / 1000f).toInt().coerceAtLeast(2)
        val d = radius - state.applied
        if (kotlin.math.abs(d) <= step) {
            state.applied = radius
        } else {
            state.applied += if (d > 0) step else -step
            param.args[1] = state.applied
            if (!ticking) {
                ticking = true
                handler.postDelayed(ticker, 16L)
            }
        }
    }
    // ---------------------------------------------------------------- 遮罩渐入渐出

    private var gaussBlurCls: Class<*>? = null
    private var ncApplied = -1f
    private var ncGoal = 0f
    private var ccApplied = -1f
    private var ccGoal = 0f
    private var ncActive = false
    private var ccActive = false
    private var maskLastTs = 0L
    private var masking = false

    private val maskFadeMs = 260f

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
            masking = busy
            if (busy) handler.postDelayed(this, 16L)
        }
    }

    private fun trackMask(isCc: Boolean, param: XC_MethodHook.MethodHookParam) {
        val f = param.args.getOrNull(1) as? Float ?: return
        val goal = f.coerceIn(0f, 1f)
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = if (maskLastTs == 0L) 16L else (now - maskLastTs).coerceIn(4L, 64L)
        maskLastTs = now
        val step = (dt / maskFadeMs).coerceAtLeast(0.005f)

        if (isSwitching()) {
            if (isCc) { ccApplied = goal; ccGoal = goal; ccActive = false } else { ncApplied = goal; ncGoal = goal; ncActive = false }
            pushMask(isCc)
            return
        }

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

        if (!masking && (ncActive || ccActive)) {
            masking = true
            handler.postDelayed(maskTicker, 16L)
        }
    }

    /** 只改对应那一层遮罩，绝不同时动两块，避免两层叠加 */
    private fun pushMask(isCc: Boolean) {
        val cls = gaussBlurCls ?: return
        val v = if (isCc) ccApplied else ncApplied
        if (v < 0f) return
        val field = if (isCc) "mCCGaussMaskDrawable" else "mNCGaussMaskDrawable"
        runCatching {
            val d = XposedHelpers.getStaticObjectField(cls, field) as? android.graphics.drawable.Drawable ?: return
            d.alpha = (v.coerceIn(0f, 1f) * 255).toInt()
        }
    }
}