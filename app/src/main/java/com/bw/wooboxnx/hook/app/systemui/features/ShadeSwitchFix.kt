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

            // 上滑收起时，系统在拖动刚开始就把内容藏掉，所以看不到过渡。
            // 这里在上滑手势里跳过系统那套，抬手时改用 250ms 收起动画。
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(cls, "handleMotionEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val e = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
                        val inst = param.thisObject
                        // 磁贴详情 / 编辑状态下不拦截，避免影响里面的滑动
                        val busy = runCatching {
                            val content = XposedHelpers.getObjectField(inst, "mContent")
                            XposedHelpers.callMethod(content, "isDetailShowing") == true ||
                                XposedHelpers.callMethod(content, "isEditShowing") == true ||
                                XposedHelpers.callMethod(content, "isControlEditShowing") == true
                        }.getOrDefault(false)
                        if (busy) return
                        when (e.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                upSwipe = false
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val downY = runCatching {
                                    XposedHelpers.getFloatField(inst, "mDownY")
                                }.getOrDefault(e.rawY)
                                val downX = runCatching {
                                    XposedHelpers.getFloatField(inst, "mDownX")
                                }.getOrDefault(e.rawX)
                                val dy = e.rawY - downY
                                val dx = e.rawX - downX
                                if (dy < -60f && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                                    upSwipe = true
                                    param.result = true
                                }
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                if (upSwipe) {
                                    param.result = true
                                    runCatching { XposedHelpers.callMethod(inst, "collapsePanel", true) }
                                    upSwipe = false
                                }
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                upSwipe = false
                            }
                        }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: 上滑手势 hook 失败", it, logInRelease = true)
            }

            // 收起动画时长单独调成 200ms（原厂 250ms）
            runCatching {
                val cls = cl.loadClass("com.zte.controlcenter.ControlPanelWindowView")
                XposedBridge.hookAllMethods(cls, "createHeightChangeAnimator", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.args.getOrNull(0) as? Int ?: return
                        val anim = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mHeightChangeAnimator")
                        }.getOrNull() as? android.animation.ValueAnimator ?: return
                        anim.duration = 300L
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: 收起时长 hook 失败", it, logInRelease = true)
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
    private val ratePxPerSec = 900f

    private val ticker = object : Runnable {
        override fun run() {
            var pending = false
            for (s in blurStates.values.toList()) {

                val d = s.target - s.applied
                if (kotlin.math.abs(d) <= 3) {
                    s.applied = s.target
                    continue
                }
                val step = (ratePxPerSec * 0.016f).toInt().coerceAtLeast(2)
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
        val step = (ratePxPerSec * dt / 1000f).toInt().coerceAtLeast(2)
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
}
