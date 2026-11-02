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

        // 亮色模式也显示（默认只有在深色模式下才画）
        val allowLightMode = runCatching {
            com.u9521.wooboxforredmagicos.util.XSPUtils.getBoolean("moon_allow_light", false)
        }.getOrDefault(false)

        val shouldShow = if (customApps.isNotEmpty()) {
            (inDarkMode || allowLightMode) && currentPkg in customApps &&
                statusBarState == 0 && rotation == 0
        } else {
            topSettings == 2 && statusBarState == 0 && rotation == 0
        }

        // 电量功能开启时让位，避免两个悬浮窗叠在一起
        val batteryOn = runCatching {
            com.u9521.wooboxforredmagicos.util.XSPUtils.getBoolean("camera_battery", false)
        }.getOrDefault(false)
        val visible = shouldShow && !batteryOn

        // 圆里显示什么：月亮 / 彩色应用图标 / 剪影应用图标
        val style = runCatching {
            context.contentResolver.query(
                android.net.Uri.parse(
                    com.u9521.wooboxforredmagicos.activity.MoonStorage.URI_STYLE
                ),
                null, null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0)
                else com.u9521.wooboxforredmagicos.activity.MoonStyle.MOON
            }
        }.getOrNull() ?: com.u9521.wooboxforredmagicos.activity.MoonStyle.MOON
        // 只在需要显示时更新内容：避免回桌面淡出的那一瞬间先闪出桌面应用的图标
        if (visible) updateContent(context, currentPkg, style)

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

    /** 已加载内容缓存（包名|样式|尺寸|彩色/剪影 -> 位图） */
    private val iconCache = android.util.LruCache<String, android.graphics.Bitmap>(16)
    private var contentKey: String? = null

    private fun updateContent(context: Context, pkg: String?, style: Int) {
        val view = moonView ?: return
        val drawable = view.drawable as? MoonDrawable ?: return
        val key = pkg + "|" + style
        if (key == contentKey) return
        contentKey = key

        val moonStyle = com.u9521.wooboxforredmagicos.activity.MoonStyle
        if (style == moonStyle.PLAIN_BLACK) {
            drawable.appIcon = null
            drawable.monoGlyph = null
            drawable.plainBlack = true
            return
        }
        drawable.plainBlack = false
        if (style == moonStyle.MOON || pkg == null) {
            drawable.appIcon = null
            drawable.monoGlyph = null
        } else if (style == moonStyle.MONO_ICON) {
            drawable.appIcon = null
            drawable.monoGlyph = loadAppIcon(context, pkg, true)
        } else {
            drawable.monoGlyph = null
            drawable.appIcon = loadAppIcon(context, pkg, false)
        }
    }

    private fun loadAppIcon(
        context: Context,
        pkg: String,
        mono: Boolean
    ): android.graphics.Bitmap? {
        val size = MOON_SIZE_PX
        val key = pkg + "@" + size + "@" + mono
        iconCache.get(key)?.let { return it }
        val art = runCatching {
            val icon = context.packageManager.getApplicationIcon(pkg)
            if (!mono) {
                renderToBitmap(icon, size)
            } else {
                monoIcon(icon, size)
            }
        }.getOrNull() ?: return null
        iconCache.put(key, art)
        return art
    }

    private fun renderToBitmap(drawable: android.graphics.drawable.Drawable, size: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /** 剪影在圆里占的比例（比彩色图标略小一点，留出呼吸感） */
    private const val MONO_FILL = 0.70f

    /**
     * 生成单色剪影：
     * 1. 自适应图标先用前景层的 alpha（多数应用就是 logo 形状）；
     * 2. 前景层是整块实心（例如白底圆形图标）或普通图标时，改用「跟边缘底色的色差」求字形；
     * 3. 求字形时先在 4 倍分辨率上算，再缩回目标尺寸——这样边缘是干净的抗锯齿，不会糊。
     */
    private fun monoIcon(
        icon: android.graphics.drawable.Drawable,
        size: Int
    ): android.graphics.Bitmap? {
        val hi = size * 4
        if (icon is android.graphics.drawable.AdaptiveIconDrawable) {
            val fg = renderToBitmap(icon.foreground, hi)
            alphaGlyph(fg)?.let { return fitGlyph(it, size) }
        }
        val full = renderToBitmap(icon, hi)
        alphaGlyph(full)?.let { return fitGlyph(it, size) }
        val luma = lumaGlyph(full) ?: return null
        return fitGlyph(luma, size)
    }

    /** 有足够透明区域时，直接按 alpha 上白色；实心形状返回 null */
    private fun alphaGlyph(src: android.graphics.Bitmap): android.graphics.Bitmap? {
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        var solid = 0
        for (p in px) if ((p ushr 24) >= 160) solid++
        val ratio = solid.toFloat() / px.size
        if (ratio < 0.03f || ratio > 0.55f) return null
        // 直接二值化：只留纯白，避免半透明像素变成灰色
        val outPx = IntArray(px.size)
        for (i in px.indices) {
            outPx[i] = if ((px[i] ushr 24) >= 160) Color.WHITE else 0
        }
        val out = android.graphics.Bitmap.createBitmap(
            src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        out.setPixels(outPx, 0, src.width, 0, 0, src.width, src.height)
        return out
    }

    /** 以边缘一圈的平均色为底色，色差大的算字形，占比过大自动反相 */
    private fun lumaGlyph(src: android.graphics.Bitmap): android.graphics.Bitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
        val ring = maxOf(1, minOf(w, h) / 8)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = minOf(x, w - 1 - x)
            val dy = minOf(y, h - 1 - y)
            if (dx >= ring && dy >= ring) continue
            val p = px[y * w + x]
            if ((p ushr 24) < 96) continue
            rs += (p shr 16) and 0xFF
            gs += (p shr 8) and 0xFF
            bs += p and 0xFF
            n++
        }
        if (n == 0L) return null
        val br = (rs / n).toInt()
        val bg = (gs / n).toInt()
        val bb = (bs / n).toInt()

        val mask = BooleanArray(px.size)
        var on = 0
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) < 96) continue
            val dr = ((p shr 16) and 0xFF) - br
            val dg = ((p shr 8) and 0xFF) - bg
            val db = (p and 0xFF) - bb
            mask[i] = dr * dr + dg * dg + db * db > 60 * 60
            if (mask[i]) on++
        }
        val invert = on > px.size / 2
        val glyph = if (invert) px.size - on else on
        if (glyph < px.size / 25) return null

        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val outPx = IntArray(px.size)
        for (i in px.indices) {
            val isGlyph = if (invert) !mask[i] else mask[i]
            outPx[i] = if (isGlyph) Color.WHITE else 0
        }
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /** 按字形包围盒等比缩放并居中，让 50px 下的图案清晰且大小合适 */
    private fun fitGlyph(glyph: android.graphics.Bitmap, size: Int): android.graphics.Bitmap {
        val w = glyph.width
        val h = glyph.height
        val px = IntArray(w * h)
        glyph.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 24) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return glyph

        val gw = (maxX - minX + 1).toFloat()
        val gh = (maxY - minY + 1).toFloat()
        // 高分辨率字形缩到目标尺寸，只会缩小不会放大，边缘干净
        val target = size * MONO_FILL
        val scale = minOf(target / gw, target / gh)
        val dw = gw * scale
        val dh = gh * scale
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dst = RectF(
            (size - dw) / 2f, (size - dh) / 2f,
            (size + dw) / 2f, (size + dh) / 2f
        )
        canvas.drawBitmap(
            glyph,
            android.graphics.Rect(minX, minY, maxX + 1, maxY + 1),
            dst,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return out
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
        private val iconDst = RectF()
        private val clipCircle = Path()
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val d = minOf(baseSizePx, b.width().toFloat(), b.height().toFloat()).toInt()
            if (d <= 0) return

            val bmp = ensureBitmap(d)
            val left = b.left + (b.width() - d) / 2f
            val top = b.top + (b.height() - d) / 2f
            canvas.drawBitmap(bmp, left, top, null)
        }

        private var cache: android.graphics.Bitmap? = null
        private var cacheFor: android.graphics.Bitmap? = null
        private var cachePlain = false

        /** 彩色应用图标：铺满整个圆 */
        var appIcon: android.graphics.Bitmap? = null
            set(value) {
                field = value
                cache = null
                invalidateSelf()
            }

        /** 单色剪影：黑底 + 白色字形 */
        var monoGlyph: android.graphics.Bitmap? = null
            set(value) {
                field = value
                cache = null
                invalidateSelf()
            }

        /** 纯黑遮罩：只有黑圆，不画任何内容 */
        var plainBlack: Boolean = false
            set(value) {
                field = value
                cache = null
                invalidateSelf()
            }

        private fun ensureBitmap(size: Int): android.graphics.Bitmap {
            val icon = appIcon ?: monoGlyph
            cache?.let { if (it.width == size && cacheFor === icon && cachePlain == plainBlack) return it }
            val bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888
            )
            val c = Canvas(bmp)
            val d = size.toFloat()
            val left = 0f
            val top = 0f

            clipCircle.reset()
            clipCircle.addCircle(d / 2f, d / 2f, d / 2f - 0.5f, Path.Direction.CW)

            // 黑底圆：只有画剪影/月亮时才需要；彩色图标本身铺满，不再垫黑底（避免浅色背景上露出细黑边）
            if (appIcon == null) {
                c.drawCircle(left + d / 2f, top + d / 2f, d / 2f - 1f, bgPaint)
            }

            if (icon != null) {
                // 图标直接铺满整个圆，不留黑边（圆形裁切，方图标也不会露出四角）
                iconDst.set(0f, 0f, d, d)
                val save = c.save()
                c.clipPath(clipCircle)
                c.drawBitmap(icon, null, iconDst, iconPaint)
                c.restoreToCount(save)
            } else if (!plainBlack) {
                // 兜底：画弯月 + 星星
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
            }

            cache = bmp
            cacheFor = icon
            cachePlain = plainBlack
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
