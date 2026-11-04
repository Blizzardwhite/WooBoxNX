package com.bw.wooboxnx.hook.app.systemui.features

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 面板底色：模糊背景 + 黑色渐变遮罩。
 *
 * 把「模糊画面 + 渐变」合成一张底图，两块面板共用同一张：
 *   - 每次下拉开始截一张当前屏幕，模糊后作为底图（和真实模糊一致）
 *   - 两页用同一张不透明底图，左右切换时怎么叠都不会变色
 *   - 透明度跟随系统遮罩的展开比例，所以展开/收起有淡入淡出
 */
object ShadeBackground : HookRegister() {

    private const val TAG = "woobox_shade_bg"
    private const val FALLBACK = 0xFF101013.toInt()

    private var containerId = 0
    private val main = Handler(Looper.getMainLooper())

    private var bg: Bitmap? = null
    private var capturing = false
    private var lastCaptureAt = 0L
    private var lastTouchAt = 0L

    private class Entry(val view: WeakReference<ImageView>, val isCc: Boolean)

    private val entries = ArrayList<Entry>()
    private var ncFraction = 1f
    private var ccFraction = 1f
    private var switching = false
    private var appCtx: WeakReference<Context>? = null

    override fun init() = hasEnable("shade_scrim") {
        // 窗口创建
        runCatching {
            listOf(
                "com.android.systemui.shade.NotificationShadeWindowView",
                "com.zte.controlcenter.ControlPanelWindowView"
            ).forEach { name ->
                runCatching {
                    val cls = getDefaultCL().loadClass(name)
                    XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val root = param.thisObject as? ViewGroup ?: return
                            if (containerId == 0) {
                                containerId = runCatching {
                                    root.resources.getIdentifier(
                                        "notification_container_parent", "id", "com.android.systemui"
                                    )
                                }.getOrDefault(0)
                            }
                            runCatching { attach(root, name.contains("ControlPanel")) }
                        }
                    })
                }.onFailure {
                    Log.px("W", "ShadeBackground skip $name", it, logInRelease = true)
                }
            }
        }

        // 去掉面板自带的渐变遮罩（黑 → 半透明黑），只留我们这一层
        runCatching {
            XposedBridge.hookAllMethods(View::class.java, "setBackground", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val v = param.thisObject as? View ?: return
                        if (containerId != 0 && v.id == containerId) {
                            param.args[0] = null
                            return
                        }
                        val d = param.args.getOrNull(0) as? android.graphics.drawable.GradientDrawable ?: return
                        val colors = d.colors ?: return
                        if (colors.size >= 2) {
                            val topA = colors[0] ushr 24
                            val bottomA = colors[colors.size - 1] ushr 24
                            if (topA >= 0xC0 && bottomA <= 0x60) param.args[0] = null
                        }
                    }
                }
            })
        }

        // 通知面板展开比例（0 = 收起，1 = 展开）
        runCatching {
            val cls = getDefaultCL().loadClass("com.android.systemui.shade.ShadeExpansionStateManager")
            XposedBridge.hookAllMethods(cls, "onPanelExpansionChanged", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val f = param.args.getOrNull(0) as? Float ?: return
                    onFraction(false, f)
                }
            })
        }.onFailure { Log.px("W", "ShadeBackground: 展开 hook 失败", it, logInRelease = true) }

        // 快速设置面板展开比例
        runCatching {
            val cls = getDefaultCL().loadClass("com.zte.controlcenter.CCDepthController")
            XposedBridge.hookAllMethods(cls, "onPanelExpansionChange", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val f = param.args.getOrNull(0) as? Float ?: return
                    onFraction(true, f)
                }
            })
        }

        // 下拉（点状态栏）时截一张当前屏幕
        runCatching {
            val cls = getDefaultCL().loadClass("com.android.systemui.statusbar.window.StatusBarWindowView")
            XposedBridge.hookAllMethods(cls, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val e = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTouchAt > 700L) {
                            lastTouchAt = now
                            capture()
                        }
                    }
                }
            })
        }.onFailure { Log.px("W", "ShadeBackground: 状态栏触摸 hook 失败", it, logInRelease = true) }

        // 左右切换状态
        runCatching {
            val cls = getDefaultCL().loadClass("com.zte.controlcenter.CCDepthController")
            XposedBridge.hookAllMethods(cls, "setNcSwitching", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    switching = param.args.getOrNull(0) as? Boolean ?: false
                    applyAlpha()
                }
            })
        }

        Log.px("I", "ShadeBackground hooked", logInRelease = true)
    }

    // ---------------------------------------------------------------- 窗口

    private fun attach(root: ViewGroup, isCc: Boolean) {
        if (root.findViewWithTag<View>(TAG) != null) return
        appCtx = WeakReference(root.context.applicationContext)

        val iv = ImageView(root.context).apply {
            tag = TAG
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(FALLBACK)
            alpha = currentAlpha(isCc)
            bg?.let { setImageBitmap(it) }
        }
        runCatching { root.addView(iv, 0, ViewGroup.LayoutParams(-1, -1)) }
        entries.add(Entry(WeakReference(iv), isCc))


        ensureBaseImage(root.context)

        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                root.removeOnLayoutChangeListener(this)
                runCatching { root.background = null }
                runCatching { (root.rootView as? View)?.background = null }
                // 窗口里的 ScrimView 会在切换过程中额外叠一层暗色
                runCatching {
                    fun hideScrims(v: View) {
                        if (v.javaClass.simpleName.contains("ScrimView")) {
                            v.visibility = View.GONE
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) hideScrims(v.getChildAt(i))
                    }
                    hideScrims(root)
                }
            }
        })
    }

    // ---------------------------------------------------------------- 展开比例

    private fun onFraction(isCc: Boolean, raw: Float) {
        val f = raw.coerceIn(0f, 1f)
        val prev = if (isCc) ccFraction else ncFraction
        if (isCc) ccFraction = f else ncFraction = f
        applyAlpha()
        if (f > 0.02f && prev <= 0.02f) maybeCapture()
    }

    private fun maybeCapture() {
        if (SystemClock.elapsedRealtime() - lastCaptureAt > 900L) capture()
    }

    private fun currentAlpha(isCc: Boolean): Float =
        (if (switching) 1f else if (isCc) ccFraction else ncFraction).coerceIn(0f, 1f)

    private fun applyAlpha() {
        entries.forEach { e ->
            runCatching { e.view.get()?.alpha = currentAlpha(e.isCc) }
        }
    }

    // ---------------------------------------------------------------- 底图

    private fun ensureBaseImage(ctx: Context) {
        if (bg != null || capturing) return
        Thread {
            val bmp = runCatching { build(ctx, null) }.getOrNull() ?: return@Thread
            main.post {
                if (bg == null) {
                    bg = bmp
                    entries.forEach { e -> e.view.get()?.setImageBitmap(bmp) }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 下拉开始时重新截屏生成底图。 */
    private fun capture() {
        if (capturing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < 400L) return
        val ctx = entries.firstNotNullOfOrNull { it.view.get()?.context } ?: appCtx?.get() ?: return
        lastCaptureAt = now
        capturing = true

        val hidden = entries.mapNotNull { it.view.get() }.filter { it.visibility == View.VISIBLE }
        hidden.forEach { it.visibility = View.INVISIBLE }

        main.postDelayed({
            Thread {
                val bmp = runCatching { build(ctx, takeShot(ctx)) }.getOrNull()
                main.post {
                    if (bmp != null) {
                        bg = bmp
                        entries.forEach { e -> e.view.get()?.setImageBitmap(bmp) }
                    }
                    hidden.forEach { it.visibility = View.VISIBLE }
                    capturing = false
                }
            }.apply { isDaemon = true }.start()
        }, 24L)
    }

    private fun takeShot(ctx: Context): Bitmap? {
        val raw = runCatching {
            val cls = getDefaultCL().loadClass("com.zte.utils.GaussBlur")
            XposedHelpers.callStaticMethod(cls, "takeScreenshot", ctx, 4) as? Bitmap
        }.getOrNull() ?: return null
        // 截图返回的是 HARDWARE 位图，软件绘制/取像素前要先转一份
        val shot = runCatching {
            if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
        }.getOrNull() ?: return null
        // 失败时 ROM 会给一张纯白图
        return runCatching { if (isBlank(shot)) null else shot }.getOrNull()
    }

    private fun isBlank(bmp: Bitmap): Boolean {
        val pts = listOf(
            0 to 0,
            bmp.width / 2 to bmp.height / 4,
            bmp.width / 2 to bmp.height / 2,
            bmp.width / 3 to bmp.height * 3 / 4,
            bmp.width - 1 to bmp.height - 1
        )
        return pts.all { (x, y) -> bmp.getPixel(x, y) == -1 }
    }

    private fun build(ctx: Context, shot: Bitmap?): Bitmap? {
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels / 4).coerceAtLeast(64)
        val h = (dm.heightPixels / 4).coerceAtLeast(64)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF101013.toInt())

        if (shot != null) {
            c.drawBitmap(shot, Rect(0, 0, shot.width, shot.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val d = runCatching { WallpaperManager.getInstance(ctx).drawable }.getOrNull()
            if (d != null) {
                val iw = d.intrinsicWidth
                val ih = d.intrinsicHeight
                if (iw > 0 && ih > 0) {
                    val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
                    val dw = (iw * scale).toInt()
                    val dh = (ih * scale).toInt()
                    val left = (w - dw) / 2
                    val top = (h - dh) / 2
                    d.setBounds(left, top, left + dw, top + dh)
                    runCatching { d.draw(c) }
                }
            }
        }

        // 黑色渐变遮罩：和系统一致，顶部纯黑 → 底部 35% 黑
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF000000.toInt(), 0x59000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        return blur(bmp, 8, 2)

    }

    private fun blur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxH(a, b, w, h, radius)
            boxV(b, a, w, h, radius)
        }
        src.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa / d) shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val o = src[row + (x - r).coerceIn(0, w - 1)]
                val n = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sa += (n ushr 24) - (o ushr 24)
                sr += ((n shr 16) and 0xFF) - ((o shr 16) and 0xFF)
                sg += ((n shr 8) and 0xFF) - ((o shr 8) and 0xFF)
                sb += (n and 0xFF) - (o and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa / d) shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val o = src[(y - r).coerceIn(0, h - 1) * w + x]
                val n = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sa += (n ushr 24) - (o ushr 24)
                sr += ((n shr 16) and 0xFF) - ((o shr 16) and 0xFF)
                sg += ((n shr 8) and 0xFF) - ((o shr 8) and 0xFF)
                sb += (n and 0xFF) - (o and 0xFF)
            }
        }
    }
}
