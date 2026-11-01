package com.u9521.wooboxforredmagicos.hook.app.systemui.features

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.u9521.wooboxforredmagicos.util.hasEnable
import com.u9521.wooboxforredmagicos.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlin.math.sin

/**
 * 摄像头位置显示电量（独立功能）。
 *
 * - 常驻显示（竖屏）
 * - 圆内绿色液体按电量高低填充，表面带波浪浮动
 * - 充电时在数字左侧显示闪电
 * - 底色/文字颜色跟随状态栏图标，并按明暗强度渐变
 */
object CameraBattery : HookRegister() {
    private const val FAKE_NOTCH_CLASS = "com.zte.feature.fake.FakeNotchFeature"
    private const val BAR_TRANSITIONS_CLASS = "com.android.systemui.statusbar.phone.BarTransitions"
    private const val DARK_ICON_DISPATCHER_CLASS =
        "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl"

    private const val SIZE_PX = 50
    private const val TOP_PX = 35
    private const val FADE_IN_MS = 280L
    private const val WINDOW_TYPE = 2024
    private const val WINDOW_FLAGS = 545259832
    private const val WAVE_INTERVAL_MS = 40L

    private var batteryView: ImageView? = null
    private var shown = false
    private var waveRunning = false
    private var percent = -1
    private var charging = false

    /** 0 = 浅色图标（黑底），1 = 深色图标（白底） */
    private var darkAmount = 0f

    /** 状态栏图标的实际颜色（由系统算出，含渐变过程） */
    private var iconColor = -1

    private var receiver: BroadcastReceiver? = null
    private var featureRef: Any? = null

    private val waveAnim = object : Runnable {
        override fun run() {
            val view = batteryView ?: return
            if (!shown) {
                waveRunning = false
                return
            }
            (view.drawable as? BatteryDrawable)?.let {
                it.phase += 0.26f
                view.invalidate()
            }
            view.postDelayed(this, WAVE_INTERVAL_MS)
        }
    }

    override fun init() = hasEnable("camera_battery") {
        val classLoader = getDefaultCL()

        // 顶部横条透明
        runCatching {
            val barTransitions = XposedHelpers.findClass(BAR_TRANSITIONS_CLASS, classLoader)
            XposedBridge.hookAllMethods(barTransitions, "setAlwaysOpaque", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    param?.args?.let { if (it.isNotEmpty()) it[0] = false }
                }
            })
        }

        // 跟随状态栏图标的明暗强度（系统自带 0~1 动画，这里直接用来做渐变）
        runCatching {
            val dispatcher = XposedHelpers.findClass(DARK_ICON_DISPATCHER_CLASS, classLoader)
            XposedBridge.hookAllMethods(dispatcher, "applyDarkIntensity", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val obj = param?.thisObject ?: return
                    val intensity = (param.args.getOrNull(0) as? Float)?.coerceIn(0f, 1f) ?: return
                    darkAmount = intensity
                    // applyDarkIntensity 里已经用 ArgbEvaluator 算好了 mIconTint
                    val tint = runCatching {
                        XposedHelpers.getIntField(obj, "mIconTint")
                    }.getOrDefault(-1)
                    if (tint != -1) iconColor = tint

                    (batteryView?.drawable as? BatteryDrawable)?.let {
                        it.darkAmount = darkAmount
                        if (tint != -1) it.iconColor = tint
                        batteryView?.invalidate()
                    }
                }
            })
        }

        // 状态变化时刷新显隐
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
        val parent = XposedHelpers.getObjectField(feature, "topHideBarParent") as? View
        val context = parent?.context
            ?: (XposedHelpers.getObjectField(feature, "circleViewParent") as? View)?.context
            ?: return

        ensureView(context)

        val rotation = runCatching {
            XposedHelpers.getIntField(feature, "mRotation")
        }.getOrDefault(0)

        // 常驻显示，只在横屏时隐藏（摄像头跑到侧边了）
        val shouldShow = rotation == 0

        batteryView?.let { view ->
            if (shouldShow && !shown) {
                shown = true
                view.animate().cancel()
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
                if (!waveRunning) {
                    waveRunning = true
                    view.postDelayed(waveAnim, WAVE_INTERVAL_MS)
                }
            } else if (!shouldShow && shown) {
                shown = false
                view.animate().cancel()
                view.animate().alpha(0f).setDuration(200L).withEndAction {
                    if (!shown) batteryView?.visibility = View.GONE
                }.start()
            }
        }
    }

    private fun ensureView(context: Context) {
        if (batteryView != null) return

        val view = ImageView(context).apply {
            setImageDrawable(BatteryDrawable(SIZE_PX.toFloat()).also {
                it.darkAmount = darkAmount
                it.iconColor = iconColor
                it.percent = percent
                it.charging = charging
            })
            setForceDarkAllowed(false)
            alpha = 0f
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            SIZE_PX,
            SIZE_PX,
            WINDOW_TYPE,
            WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = TOP_PX
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setTitle("WooBoxBattery")
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.addView(view, params) }
        batteryView = view

        ensureBatteryReceiver(context)
    }

    private fun ensureBatteryReceiver(context: Context) {
        if (receiver != null) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching {
            context.registerReceiver(null, filter)?.let { applyBattery(it) }
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                applyBattery(intent)
            }
        }
        runCatching { context.registerReceiver(r, filter) }
        receiver = r
    }

    private fun applyBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        percent = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        (batteryView?.drawable as? BatteryDrawable)?.let {
            it.percent = percent
            it.charging = charging
            batteryView?.invalidate()
        }
    }

    /** 圆形液体电量：绿色水位 + 波浪，充电时加闪电 */
    private class BatteryDrawable(private val baseSizePx: Float) : Drawable() {
        var percent: Int = -1
        var charging: Boolean = false
        var darkAmount: Float = 0f
        var iconColor: Int = -1
        var phase: Float = 0f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#34C759")
        }
        private val waterBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#8034C759")
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(null, 600, false)
            color = Color.WHITE
        }
        private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(null, 600, false)
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }

        private fun blend(from: Int, to: Int, t: Float): Int {
            val tt = t.coerceIn(0f, 1f)
            val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * tt).toInt()
            val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * tt).toInt()
            val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * tt).toInt()
            val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * tt).toInt()
            return Color.argb(a, r, g, b)
        }

        private fun waterPath(
            cx: Float, cy: Float, radius: Float,
            levelY: Float, amplitude: Float, phaseOffset: Float
        ): Path {
            val path = Path()
            path.moveTo(cx - radius, cy + radius)
            path.lineTo(cx - radius, levelY)
            var x = cx - radius
            while (x <= cx + radius) {
                val y = levelY + sin((x - cx) / radius * 2.6f + phase + phaseOffset) * amplitude
                path.lineTo(x, y)
                x += 2f
            }
            path.lineTo(cx + radius, cy + radius)
            path.close()
            return path
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val d = minOf(baseSizePx, b.width().toFloat(), b.height().toFloat())
            if (d <= 0f) return

            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val radius = d / 2f - 1f

            val bgColor = blend(Color.BLACK, Color.WHITE, darkAmount)
            val fgBase = if (iconColor != -1) iconColor
            else blend(Color.WHITE, Color.BLACK, darkAmount)
            // 浅色背景下（深色图标）把前景往底色方向柔化，避免比状态栏图标显得更黑
            val fgColor = if (darkAmount > 0.5f) blend(fgBase, bgColor, 0.25f) else fgBase

            // 底色圆
            bgPaint.color = bgColor
            canvas.drawCircle(cx, cy, radius, bgPaint)

            // 液体（裁剪在圆内）
            val level = percent.coerceIn(0, 100)
            val levelY = cy + radius - 2f * radius * level / 100f
            val amplitude = radius * 0.10f
            canvas.save()
            canvas.clipPath(Path().apply {
                addCircle(cx, cy, radius, Path.Direction.CW)
            })
            canvas.drawPath(
                waterPath(cx, cy, radius, levelY, amplitude * 1.4f, 1.6f),
                waterBackPaint
            )
            canvas.drawPath(
                waterPath(cx, cy, radius, levelY, amplitude, 0f),
                waterPaint
            )
            canvas.restore()

            // 外圈描边
            borderPaint.color = fgColor
            canvas.drawCircle(cx, cy, radius - 1.5f, borderPaint)

            val value = if (percent >= 0) percent.toString() else "--"
            fgPaint.color = fgColor
            textPaint.color = fgColor
            textStrokePaint.color = bgColor

            if (charging) {
                // 闪电在上，百分比在下
                val bw = radius * 0.42f
                val bh = radius * 0.58f
                val bx = cx - bw / 2f
                val by = cy - radius * 0.80f
                val bolt = Path().apply {
                    moveTo(bx + bw * 0.58f, by)
                    lineTo(bx + bw * 0.08f, by + bh * 0.56f)
                    lineTo(bx + bw * 0.44f, by + bh * 0.56f)
                    lineTo(bx + bw * 0.32f, by + bh)
                    lineTo(bx + bw * 0.92f, by + bh * 0.42f)
                    lineTo(bx + bw * 0.54f, by + bh * 0.42f)
                    close()
                }
                canvas.drawPath(bolt, fgPaint)

                textPaint.textSize = radius * 0.86f
                textStrokePaint.textSize = radius * 0.86f
                val textY = cy + radius * 0.40f
                canvas.drawText(value, cx, textY, textPaint)
            } else {
                textPaint.textSize = radius * 1.00f
                textStrokePaint.textSize = radius * 1.00f
                val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(value, cx, textY, textPaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            waterPaint.alpha = alpha
            waterBackPaint.alpha = alpha
            borderPaint.alpha = alpha
            fgPaint.alpha = alpha
            textPaint.alpha = alpha
            textStrokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            waterPaint.colorFilter = colorFilter
            waterBackPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
            fgPaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
            textStrokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
