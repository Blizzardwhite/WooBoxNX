package com.u9521.wooboxforredmagicos.hook.app.systemui.features

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.u9521.wooboxforredmagicos.util.hasEnable
import com.u9521.wooboxforredmagicos.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 摄像头位置显示"月出"图标：弯月 + 一颗星。
 *
 * MyOS 在"隐藏顶部区域"时会把整条状态栏设成不透明黑，这里：
 *   1. 拦截 setAlwaysOpaque，让顶部横条恢复透明；
 *   2. 在状态栏之上放一个小悬浮窗，画黑底圆 + 图标，遮住屏下摄像头。
 */
object MoonNotch : HookRegister() {
    private const val FAKE_NOTCH_CLASS = "com.zte.feature.fake.FakeNotchFeature"
    private const val BAR_TRANSITIONS_CLASS = "com.android.systemui.statusbar.phone.BarTransitions"

    private const val MOON_SIZE_PX = 50
    private const val MOON_TOP_PX = 35
    private const val FADE_IN_MS = 280L
    private const val FADE_OUT_MS = 200L

    /** 与系统 FakeNotchFeature 的悬浮窗保持一致的类型与 flags */
    private const val WINDOW_TYPE = 2024
    private const val WINDOW_FLAGS = 545259832

    private var moonView: ImageView? = null
    private var moonShown = false
    private var currentPkg: String? = null
    private var featureRef: Any? = null

    override fun init() = hasEnable("moon_notch") {
        val classLoader = getDefaultCL()

        // 1. 不再把状态栏整条变不透明
        runCatching {
            val barTransitions = XposedHelpers.findClass(BAR_TRANSITIONS_CLASS, classLoader)
            XposedBridge.hookAllMethods(barTransitions, "setAlwaysOpaque", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    param?.args?.let { if (it.isNotEmpty()) it[0] = false }
                }
            })
        }

        // 2. 记录前台包名，自定义名单需要
        runCatching {
            val controller = XposedHelpers.findClass(
                "com.zte.activityevent.ActivityEventsController", classLoader
            )
            XposedBridge.hookAllMethods(controller, "notifyInnerCallback", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    val pkg = param?.args?.getOrNull(1) as? String ?: return
                    currentPkg = pkg
                    val feature = featureRef ?: return
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { sync(feature) }
                    }
                }
            })
        }

        // 3. 跟随 FakeNotchFeature 的状态同步
        val fakeNotch = XposedHelpers.findClass(FAKE_NOTCH_CLASS, classLoader)
        val syncHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                val feature = param?.thisObject ?: return
                featureRef = feature
                runCatching { sync(feature) }
            }
        }
        listOf("onSystemUiReady", "doCheckShow", "resetWaterDrop").forEach { method ->
            runCatching { XposedBridge.hookAllMethods(fakeNotch, method, syncHook) }
        }
    }

    @SuppressLint("PrivateApi")
    private fun sync(feature: Any) {
        // 系统自己画的黑条也去掉
        (XposedHelpers.getObjectField(feature, "topHideBarView") as? View)
            ?.setBackgroundColor(Color.TRANSPARENT)

        val parent = XposedHelpers.getObjectField(feature, "topHideBarParent") as? View
        val context = parent?.context
            ?: (XposedHelpers.getObjectField(feature, "circleViewParent") as? View)?.context
            ?: return

        ensureMoonView(context)

        val topSettings = runCatching {
            XposedHelpers.getIntField(feature, "currTopSettings")
        }.getOrDefault(0)
        val statusBarState = runCatching {
            XposedHelpers.getIntField(feature, "statusBarState")
        }.getOrDefault(0)
        val rotation = runCatching {
            XposedHelpers.getIntField(feature, "mRotation")
        }.getOrDefault(0)
        val inDarkMode = runCatching {
            XposedHelpers.getBooleanField(feature, "inDarkMode")
        }.getOrDefault(false)

        val customAppsRaw = runCatching {
            context.contentResolver.query(
                android.net.Uri.parse("content://com.u9521.wooboxforredmagicos.mod.moon/moon"),
                null, null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                com.u9521.wooboxforredmagicos.util.XSPUtils.getString("moon_apps", "")
            }.getOrNull().orEmpty()
        val customApps = customAppsRaw
            .split(";")
            .filter { it.isNotBlank() }
            .toSet()

        val shouldShow = if (customApps.isNotEmpty()) {
            inDarkMode && currentPkg in customApps && statusBarState == 0 && rotation == 0
        } else {
            topSettings == 2 && statusBarState == 0 && rotation == 0
        }

        // 电量功能开启时让位，避免两个悬浮窗叠在一起
        val batteryOn = runCatching {
            com.u9521.wooboxforredmagicos.util.XSPUtils.getBoolean("camera_battery", false)
        }.getOrDefault(false)
        val visible = shouldShow && !batteryOn

        moonView?.let { view ->
            if (visible && !moonShown) {
                moonShown = true
                view.animate().cancel()
                view.visibility = View.VISIBLE
                // 从当前透明度接着淡入，避免快速切换时先跳回全透明再亮起（闪一下）
                view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            } else if (!visible && moonShown) {
                moonShown = false
                view.animate().cancel()
                view.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
                    if (!moonShown) moonView?.visibility = View.GONE
                }.start()
            }
        }
    }

    private fun ensureMoonView(context: Context) {
        if (moonView != null) return

        val density = context.resources.displayMetrics.density
        val view = ImageView(context).apply {
            setImageDrawable(MoonDrawable(density, MOON_SIZE_PX.toFloat()))
            setForceDarkAllowed(false)
            alpha = 0f
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            MOON_SIZE_PX,
            MOON_SIZE_PX,
            WINDOW_TYPE,
            WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = MOON_TOP_PX
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setTitle("WooBoxMoon")
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.addView(view, params) }
        moonView = view
    }

    /**
     * 黑底圆 + 胖弯月（右上开口、真圆角）+ 一颗圆角大星。
     *
     * 所有尺寸都以悬浮窗边长 s 为基准（1.0 = 整个圆），方便和设计稿一一对应。
     */
    private class MoonDrawable(
        private val density: Float,
        private val baseSizePx: Float
    ) : Drawable() {
        private val fgColor = Color.parseColor("#F2F2F2")

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.FILL
        }
        private val strokeFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val crescent = Path()
        private val starPath = Path()
        private val outerRect = RectF()
        private val innerRect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val d = minOf(baseSizePx, b.width().toFloat(), b.height().toFloat()).toInt()
            if (d <= 0) return

            // 整张图标先渲染到一张位图，再一次性贴上去。
            // 弯月是「填充 + 描边」两次绘制，如果直接画在带透明度的画布上，
            // 重叠处会被叠加成更亮的一圈边（淡入淡出时能看到发光轮廓）。
            val bmp = ensureBitmap(d)
            val left = b.left + (b.width() - d) / 2f
            val top = b.top + (b.height() - d) / 2f
            canvas.drawBitmap(bmp, left, top, null)
        }

        private var cache: android.graphics.Bitmap? = null

        private fun ensureBitmap(size: Int): android.graphics.Bitmap {
            cache?.let { if (it.width == size) return it }
            val bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888
            )
            val c = Canvas(bmp)
            val d = size.toFloat()
            val left = 0f
            val top = 0f

            // 黑底圆（遮住摄像头）
            c.drawCircle(left + d / 2f, top + d / 2f, d / 2f - 1f, bgPaint)

            // 弯月外圆圆心与遮罩圆心重合
            val moonCx = left + d * 0.5f
            val moonCy = top + d * 0.5f
            // 整体放大约 18%（0.270 * 1.18），圆心不变
            val moonR = d * 0.3186f

            // ---- 弯月 ----
            drawCrescent(c, moonCx, moonCy, moonR)

            // ---- 一颗星，落在弯月缺口（右上 45°）里 ----
            val starDir = Math.toRadians(-45.0)
            drawStar(
                c,
                moonCx + (moonR * 0.62f * cos(starDir)).toFloat(),
                moonCy + (moonR * 0.62f * sin(starDir)).toFloat(),
                d * 0.1156f
            )

            cache = bmp
            return bmp
        }

        /**
         * 真正的圆角月牙：
         * 先用半径 R-k 的两个圆求出尖角月牙，再用宽度 2k 的圆头描边向外描一圈。
         * 描边外缘正好回到 R，两个尖端由圆角接头自然收圆。
         */
        private fun drawCrescent(canvas: Canvas, cx: Float, cy: Float, R: Float) {
            val k = R * 0.06f
            val r = R - k
            val off = R * 0.72f
            // 开口朝右上 45°
            val rad = Math.toRadians(-45.0)
            val dx = (off * cos(rad)).toFloat()
            val dy = (off * sin(rad)).toFloat()
            val c2x = cx + dx
            val c2y = cy + dy

            // 两个圆的交点，决定月牙的两个尖
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val mx = cx + dx / 2f
            val my = cy + dy / 2f
            val h = sqrt(max(0.0001f, r * r - dist * dist / 4f))
            val ux = -dy / dist
            val uy = dx / dist
            val p1x = mx + ux * h
            val p1y = my + uy * h
            val p2x = mx - ux * h
            val p2y = my - uy * h

            val a1 = angleDeg(p1x - cx, p1y - cy)
            val a2 = angleDeg(p2x - cx, p2y - cy)
            val b1 = angleDeg(p1x - c2x, p1y - c2y)
            val b2 = angleDeg(p2x - c2x, p2y - c2y)
            val away = normDeg(angleDeg(-dx, -dy))

            // 取远离另一个圆的那一侧弧段
            var sweepOuter = normDeg(a2 - a1)
            if (Math.abs(normDeg(a1 + sweepOuter / 2f - away)) >= 90f) sweepOuter -= 360f
            var sweepInner = normDeg(b1 - b2)
            if (Math.abs(normDeg(b2 + sweepInner / 2f - away)) >= 90f) sweepInner -= 360f

            outerRect.set(cx - r, cy - r, cx + r, cy + r)
            innerRect.set(c2x - r, c2y - r, c2x + r, c2y + r)

            crescent.reset()
            crescent.arcTo(outerRect, a1, sweepOuter, true)
            crescent.arcTo(innerRect, b2, sweepInner, false)
            crescent.close()

            canvas.drawPath(crescent, fgPaint)
            strokeFgPaint.strokeWidth = k * 2f
            canvas.drawPath(crescent, strokeFgPaint)
        }

        /** 四角星，同样用"缩小 + 圆头描边"得到圆角 */
        private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val k = r * 0.14f
            val rr = r - k
            starPath.reset()
            starPath.moveTo(cx, cy - rr)
            starPath.lineTo(cx + rr * 0.26f, cy - rr * 0.26f)
            starPath.lineTo(cx + rr, cy)
            starPath.lineTo(cx + rr * 0.26f, cy + rr * 0.26f)
            starPath.lineTo(cx, cy + rr)
            starPath.lineTo(cx - rr * 0.26f, cy + rr * 0.26f)
            starPath.lineTo(cx - rr, cy)
            starPath.lineTo(cx - rr * 0.26f, cy - rr * 0.26f)
            starPath.close()

            canvas.drawPath(starPath, fgPaint)
            strokeFgPaint.strokeWidth = k * 2f
            canvas.drawPath(starPath, strokeFgPaint)
        }

        private fun angleDeg(x: Float, y: Float): Float =
            Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()

        private fun normDeg(a: Float): Float {
            var v = a % 360f
            if (v < 0f) v += 360f
            return v
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            fgPaint.alpha = alpha
            strokeFgPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            fgPaint.colorFilter = colorFilter
            strokeFgPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
