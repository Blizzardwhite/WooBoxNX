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

            // 快速下滑快速设置时，它的模糊半径是一帧跳到目标的（通知面板有弹簧缓动，它没有），
            // 这里给快速设置窗口的模糊加个步进，让它在约 160ms 内平滑到位。
            runCatching {
                val bu = cl.loadClass("com.android.systemui.statusbar.BlurUtils")
                XposedBridge.hookAllMethods(bu, "applyBlur", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { smoothCcBlur(cl, param) }
                    }
                })
            }.onFailure {
                Log.px("W", "ShadeSwitchFix: applyBlur hook 失败", it, logInRelease = true)
            }

            Log.px("I", "ShadeSwitchFix hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeSwitchFix init failed", it, logInRelease = true)
        }
    }

    // ---------------------------------------------------------------- 快速设置模糊平滑

    private var ccRoot: Any? = null
    private var blurUtilsRef: Any? = null

    private var blurApplied = -1
    private var blurTarget = 0
    private var blurOpaque = false
    private var blurTicking = false
    private var blurLastTs = 0L
    private var inTicker = false

    private val ticker = object : Runnable {
        override fun run() {
            val utils = blurUtilsRef
            val root = ccRoot
            if (utils == null || root == null) {
                blurTicking = false
                return
            }
            val d = blurTarget - blurApplied
            if (kotlin.math.abs(d) <= 4) {
                blurApplied = blurTarget
                blurTicking = false
                return
            }
            blurApplied += if (d > 0) 14 else -14
            inTicker = true
            runCatching {
                XposedHelpers.callMethod(utils, "applyBlur", root, blurApplied, blurOpaque)
            }
            inTicker = false
            handler.postDelayed(this, 16L)
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun smoothCcBlur(cl: ClassLoader, param: XC_MethodHook.MethodHookParam) {
        if (inTicker) return
        val root = param.args.getOrNull(0) ?: return
        val radius = param.args.getOrNull(1) as? Int ?: return
        val opaque = param.args.getOrNull(2) as? Boolean ?: false

        // 判断是不是快速设置窗口：取 ViewRootImpl 的 mView，看类名
        val isCc = runCatching {
            val v = XposedHelpers.getObjectField(root, "mView") as? android.view.View
            v?.javaClass?.name?.contains("ControlPanel") == true
        }.getOrDefault(false)
        if (!isCc) return

        if (blurUtilsRef == null) blurUtilsRef = param.thisObject
        val now = android.os.SystemClock.elapsedRealtime()

        // 归零立即生效，避免残留
        if (radius == 0) {
            blurApplied = 0
            blurTarget = 0
            blurOpaque = opaque
            blurLastTs = now
            return
        }

        if (blurApplied < 0) blurApplied = radius
        blurTarget = radius
        blurOpaque = opaque

        val dt = if (blurLastTs == 0L) 16L else (now - blurLastTs).coerceIn(4L, 64L)
        blurLastTs = now
        val step = (800f * dt / 1000f).toInt().coerceAtLeast(4)
        val d = radius - blurApplied
        if (kotlin.math.abs(d) <= step) {
            blurApplied = radius
        } else {
            blurApplied += if (d > 0) step else -step
            param.args[1] = blurApplied
            if (!blurTicking) {
                blurTicking = true
                handler.postDelayed(ticker, 16L)
            }
        }
    }
}
