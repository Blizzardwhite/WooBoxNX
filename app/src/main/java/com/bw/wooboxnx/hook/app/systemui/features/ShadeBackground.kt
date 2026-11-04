package com.bw.wooboxnx.hook.app.systemui.features

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference

/**
 * 面板底色：把「模糊壁纸 + 黑色渐变遮罩」合成为一张不透明底图，两块面板共用。
 *
 * 通知中心和快速设置是两个窗口，左右切换时两个窗口会同时参与合成。
 * 只要两边铺的是同一张完全不透明的图，无论怎么叠、怎么交叉淡入，画面都一模一样，
 * 所以不会出现切换时突然变黑的问题。
 *
 * 底图在 1/4 尺寸上生成（壁纸裁剪 → 叠渐变 → 模糊 → 放大绘制），开销很小。
 */
object ShadeBackground : HookRegister() {

    private const val TAG = "woobox_shade_bg"
    private const val FALLBACK = 0xFF1C1C20.toInt()

    private var containerId = 0
    private val main = Handler(Looper.getMainLooper())
    private var bg: Bitmap? = null
    private var building = false
    private var receiverRegistered = false
    private val views = ArrayList<WeakReference<ImageView>>()

    override fun init() = hasEnable("shade_scrim") {
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
                            runCatching { attach(root) }
                        }
                    })
                }.onFailure {
                    Log.px("W", "ShadeBackground skip $name", it, logInRelease = true)
                }
            }

            // 去掉面板自带的底色/渐变，只保留我们这一层
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
                            if (topA >= 0xC0 && bottomA <= 0x40) param.args[0] = null
                        }
                    }
                }
            })

            Log.px("I", "ShadeBackground hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeBackground init failed", it, logInRelease = true)
        }
    }

    private fun attach(root: ViewGroup) {
        if (root.findViewWithTag<View>(TAG) != null) return

        val iv = ImageView(root.context).apply {
            tag = TAG
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(FALLBACK)
            bg?.let { setImageBitmap(it) }
        }
        runCatching { root.addView(iv, 0, ViewGroup.LayoutParams(-1, -1)) }
        views.add(WeakReference(iv))

        ensureBackground(root.context)

        // 窗口自身可能带渐变底色，它会透出来
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                root.removeOnLayoutChangeListener(this)
                runCatching { root.background = null }
                runCatching { (root.rootView as? View)?.background = null }
                // 窗口里的 ScrimView 会在切换过程中额外叠一层暗色，直接隐藏
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

    /** 第一次用到时在后台线程生成底图，壁纸变化后重新生成。 */
    private fun ensureBackground(ctx: Context) {
        registerReceiverOnce(ctx)
        if (bg != null || building) return
        building = true
        val app = ctx.applicationContext
        Thread {
            val bmp = runCatching { build(app) }.getOrNull()
            main.post {
                building = false
                if (bmp == null) {
                    Log.px("W", "ShadeBackground: 壁纸不可用，退回纯色", logInRelease = true)
                    return@post
                }
                bg = bmp
                views.forEach { ref ->
                    ref.get()?.let { v ->
                        v.background = null
                        v.setImageBitmap(bmp)
                    }
                }
                Log.px("I", "ShadeBackground: 底图就绪 ${bmp.width}x${bmp.height}", logInRelease = true)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun registerReceiverOnce(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        runCatching {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    bg = null
                    c?.let { ensureBackground(it) }
                }
            }, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))
        }.onFailure { Log.px("W", "ShadeBackground: 注册壁纸监听失败", it) }
    }

    /** 生成 1/4 尺寸的不透明底图：壁纸居中裁剪 → 叠黑色渐变 → 模糊。 */
    private fun build(ctx: Context): Bitmap? {
        val d = WallpaperManager.getInstance(ctx).drawable ?: return null
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels / 4).coerceAtLeast(64)
        val h = (dm.heightPixels / 4).coerceAtLeast(64)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF101013.toInt())

        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw > 0 && ih > 0) {
            val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
            val dw = (iw * scale).toInt()
            val dh = (ih * scale).toInt()
            val left = (w - dw) / 2
            val top = (h - dh) / 2
            d.setBounds(left, top, left + dw, top + dh)
            d.draw(c)
        }

        // 黑色渐变遮罩：顶部接近实心，底部留出模糊壁纸
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                0xF2000000.toInt(),
                0xCC000000.toInt(),
                0x8C000000.toInt(),
                0x52000000
            ),
            floatArrayOf(0f, 0.28f, 0.66f, 1f),
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
