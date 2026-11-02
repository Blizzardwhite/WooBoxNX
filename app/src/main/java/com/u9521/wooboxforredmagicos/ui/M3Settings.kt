package com.u9521.wooboxforredmagicos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.u9521.wooboxforredmagicos.R

// ---------------- 色板 ----------------
object M3Color {
    const val SURFACE = 0xFF111318.toInt()
    const val SURFACE_CONT = 0xFF1D2024.toInt()
    const val PRIMARY = 0xFFAFC6FF.toInt()
    const val PRIMARY_CONT = 0xFF26477F.toInt()
    const val ON_PRIMARY = 0xFF002E69.toInt()
    const val ON_PRIMARY_CONT = 0xFFD8E2FF.toInt()
    const val ON_SURFACE = 0xFFE2E2E9.toInt()
    const val ON_SURFACE_VAR = 0xFFC4C6D0.toInt()
    const val OUTLINE_VAR = 0xFF44474F.toInt()
}

// ---------------- 偏好 ----------------
object M3Prefs {
    @SuppressLint("WorldReadableFiles")
    fun sp(c: Context): SharedPreferences = try {
        @Suppress("DEPRECATION")
        c.getSharedPreferences("WooboxConfig", Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        c.getSharedPreferences("viewonly", Context.MODE_PRIVATE)
    }
}

// ---------------- 数据模型 ----------------
class Row(
    val key: String? = null,
    val title: String = "",
    val summary: String? = null,
    val kind: Int = SWITCH,
    val min: Int = 0,
    val max: Int = 100,
    val def: Int = 0,
    val action: String? = null
) {
    companion object {
        const val SWITCH = 0
        const val SLIDER = 1
        const val ACTION = 2
    }
}

class Group(val title: String?, val rows: List<Row>)

class Category(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: Int,
    val groups: List<Group>
)

object SettingsModel {

    fun categories(): List<Category> = listOf(
        Category("camera", "屏下显示区域", "弯月 · 跟随深色模式", 0, emptyList()),
        Category("status", "状态栏", "双击锁屏、图标、网速", 1, listOf(
            Group("交互", listOf(
                Row(key = "status_bar_double_tap_to_sleep", title = "双击状态栏锁屏"),
                Row(key = "statusbar_font_restore", title = "恢复时钟日期图标字体"),
                Row(key = "collapsed_status_bar_clock_display_seconds", title = "下拉状态栏时钟显秒")
            )),
            Group("电量", listOf(
                Row(key = "hide_battery_percentage_icon", title = "隐藏电量百分比", summary = "需要系统开启百分比外显"),
                Row(key = "show_charge_indicator_on_power_plugin", title = "连接电源时显示充电图标"),
                Row(key = "show_battery_meter", title = "显示电池图标"),
                Row(key = "show_charge_indicator_inside", title = "充电图标显示在电池内"),
                Row(key = "show_charge_indicator_outside", title = "充电图标显示在电池外")
            )),
            Group("图标", listOf(
                Row(key = "hide_wifi_activity_icon", title = "隐藏 WIFI 箭头"),
                Row(key = "hide_mobile_activity_icon", title = "隐藏移动数据箭头"),
                Row(key = "hide_vpn_icon", title = "隐藏 VPN 图标"),
                Row(key = "status_bar_bluetooth_icon_acts_global", title = "未连接设备时隐藏蓝牙图标")
            )),
            Group("网速", listOf(
                Row(key = "status_bar_network_speed_refresh_speed", title = "网速秒刷新"),
                Row(key = "speed_unit_hide_per_second", title = "隐藏 /s 单位"),
                Row(key = "status_bar_network_speed_dual_row_size", title = "双排网速字号", kind = Row.SLIDER, min = 6, max = 8, def = 6),
                Row(key = "status_bar_network_speed_dual_row_width", title = "双排网速宽度", kind = Row.SLIDER, min = 28, max = 39, def = 35),
                Row(key = "status_bar_network_speed_dual_row_digit_len", title = "双排网速位数", kind = Row.SLIDER, min = 0, max = 3, def = 3),
                Row(key = "low_speed_hide_kilo_bytes", title = "低速隐藏阈值", kind = Row.SLIDER, min = -1, max = 10, def = -1)
            ))
        )),
        Category("notify", "通知", "原生通知图标", 2, listOf(
            Group(null, listOf(
                Row(key = "use_aosp_notify", title = "使用原生通知图标", summary = "状态栏通知变成原生样式"),
                Row(key = "aosp_icon_size_dp", title = "通知图标大小", kind = Row.SLIDER, min = 0, max = 64, def = 16),
                Row(key = "align_fan_refreshrate_aosp", title = "风扇/刷新率图标对齐", summary = "配合原生通知样式使用")
            ))
        )),
        Category("qs", "快速设置面板", "行列自定义", 3, listOf(
            Group(null, listOf(
                Row(key = "qs_header_shortcut_redirect_calendar", title = "点击日期跳转第三方日历"),
                Row(key = "qs_header_shortcut_redirect_search", title = "点击搜索打开浏览器"),
                Row(key = "qs_show_carrier", title = "快速设置显示运营商"),
                Row(key = "qs_show_search", title = "快速设置显示搜索按钮")
            )),
            Group("自定义行列", listOf(
                Row(key = "qs_custom_rows", title = "行数", kind = Row.SLIDER, min = 1, max = 9, def = 3),
                Row(key = "qs_custom_rows_landscape", title = "行数（横屏）", kind = Row.SLIDER, min = 1, max = 4, def = 1),
                Row(key = "qs_custom_columns", title = "列数", kind = Row.SLIDER, min = 1, max = 9, def = 5),
                Row(key = "qs_custom_columns_landscape", title = "列数（横屏）", kind = Row.SLIDER, min = 1, max = 10, def = 7),
                Row(key = "qs_custom_columns_editor", title = "编辑模式列数", kind = Row.SLIDER, min = 1, max = 9, def = 4),
                Row(key = "qs_custom_columns_landscape_editor", title = "编辑模式列数（横屏）", kind = Row.SLIDER, min = 1, max = 10, def = 6)
            ))
        )),
        Category("lock", "锁屏", "指纹图标改圆环", 4, listOf(
            Group(null, listOf(
                Row(key = "lockscreen_allow_adjust_volume", title = "允许在锁屏调整音量"),
                Row(key = "fp_ring", title = "锁屏指纹图标改成圆环")
            ))
        )),
        Category("other", "其他", "手势、剪贴板、旧版设置", 5, listOf(
            Group(null, listOf(
                Row(key = "gesture_use_default_digital_assist", title = "手势打开默认数字助理"),
                Row(key = "no_vibrate_volKey_Long_press", title = "禁用长按调节音量的振动"),
                Row(key = "unhide_clipboard_overlay", title = "恢复剪贴板浮窗"),
                Row(key = "aosp_singlehandmode_offest", title = "单手模式偏移", kind = Row.SLIDER, min = 0, max = 200, def = 0),
                Row(key = "legacy", title = "其他作用域设置（旧版界面）", summary = "启动器、系统设置、安装器、NFC 等", kind = Row.ACTION, action = "legacy")
            ))
        ))
    )

    fun byId(id: String): Category? = categories().firstOrNull { it.id == id }
}

// ---------------- 通用 UI 工具 ----------------
object M3Ui {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun text(c: Context, s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextColor(color)
            textSize = sizeSp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    fun card(c: Context, radiusDp: Int = 16): MaterialCardView =
        MaterialCardView(c).apply {
            radius = dp(c, radiusDp).toFloat()
            setCardBackgroundColor(M3Color.SURFACE_CONT)
            strokeWidth = 0
            cardElevation = 0f
            setContentPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
        }

    fun circle(c: Context, sizeDp: Int, color: Int): View {
        val v = View(c)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        v.background = bg
        v.layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        return v
    }
}

// ---------------- 主页 ----------------
class SettingsHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = M3Color.SURFACE
        window.navigationBarColor = M3Color.SURFACE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3Color.SURFACE)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@SettingsHomeActivity, 20), M3Ui.dp(this@SettingsHomeActivity, 24),
                M3Ui.dp(this@SettingsHomeActivity, 20), M3Ui.dp(this@SettingsHomeActivity, 24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 标题
        content.addView(M3Ui.text(this, "WooBox", 34f, M3Color.ON_SURFACE, true))
        content.addView(M3Ui.text(this, "RedMagic Z60 Ultra", 14f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 4), 0, 0)
        })

        // 状态 chips
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 12), 0, M3Ui.dp(this@SettingsHomeActivity, 4))
        }
        chips.addView(chip("● 运行中", M3Color.ON_PRIMARY_CONT, M3Color.PRIMARY_CONT))
        chips.addView(chip("作用域 2 个", M3Color.ON_SURFACE_VAR, Color.TRANSPARENT))
        chips.addView(chip("v1.0.6", M3Color.ON_SURFACE_VAR, Color.TRANSPARENT))
        content.addView(chips)

        // 分类卡片
        SettingsModel.categories().forEach { cat ->
            content.addView(categoryCard(cat), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = M3Ui.dp(this@SettingsHomeActivity, 12) })
        }

        // 底部按钮
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 20), 0, 0)
        }
        val b1 = tonalButton("备份设置")
        val b2 = tonalButton("恢复配置")
        val lp = LinearLayout.LayoutParams(0, M3Ui.dp(this, 52), 1f)
        btns.addView(b1, lp)
        btns.addView(b2, LinearLayout.LayoutParams(0, M3Ui.dp(this, 52), 1f).apply {
            leftMargin = M3Ui.dp(this@SettingsHomeActivity, 12)
        })
        content.addView(btns)
    }

    private fun tonalButton(label: String): MaterialButton =
        MaterialButton(this).apply {
            text = label
            backgroundTintList = ColorStateList.valueOf(M3Color.PRIMARY_CONT)
            setTextColor(M3Color.ON_PRIMARY_CONT)
            cornerRadius = M3Ui.dp(this@SettingsHomeActivity, 24)
            setOnClickListener { openLegacy() }
        }

    private fun chip(label: String, fg: Int, bg: Int): TextView {
        val tv = M3Ui.text(this, label, 13f, fg)
        tv.setPadding(M3Ui.dp(this, 14), M3Ui.dp(this, 7), M3Ui.dp(this, 14), M3Ui.dp(this, 7))
        val d = GradientDrawable()
        d.cornerRadius = M3Ui.dp(this, 16).toFloat()
        d.setColor(bg)
        if (bg == Color.TRANSPARENT) {
            d.setStroke(M3Ui.dp(this, 1), M3Color.OUTLINE_VAR)
        }
        tv.background = d
        (tv.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = M3Ui.dp(this, 8)
        return tv.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = M3Ui.dp(this@SettingsHomeActivity, 8) }
        }
    }

    private fun categoryCard(cat: Category): View {
        val card = M3Ui.card(this, 16).apply {
            setContentPadding(M3Ui.dp(this@SettingsHomeActivity, 16), M3Ui.dp(this@SettingsHomeActivity, 16),
                M3Ui.dp(this@SettingsHomeActivity, 16), M3Ui.dp(this@SettingsHomeActivity, 16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (cat.id == "camera") {
                    startActivity(Intent(this@SettingsHomeActivity, CameraSettingsActivity::class.java))
                } else {
                    startActivity(Intent(this@SettingsHomeActivity, CategoryActivity::class.java).apply {
                        putExtra("id", cat.id)
                    })
                }
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(iconCircle(cat.icon))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@SettingsHomeActivity, 16), 0, 0, 0)
        }
        texts.addView(M3Ui.text(this, cat.title, 17f, M3Color.ON_SURFACE, true))
        texts.addView(M3Ui.text(this, cat.subtitle, 13f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 2), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(M3Ui.text(this, "›", 26f, M3Color.ON_SURFACE_VAR))
        card.addView(row)
        return card
    }

    private fun iconCircle(type: Int): View {
        val size = M3Ui.dp(this, 44)
        val v = IconView(this, type)
        v.layoutParams = LinearLayout.LayoutParams(size, size)
        return v
    }

    private fun openLegacy() {
        runCatching {
            startActivity(Intent().setClassName(packageName,
                "com.u9521.wooboxforredmagicos.activity.SettingsActivity"))
        }
    }
}

// ---------------- 分类详情页 ----------------
class CategoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = M3Color.SURFACE
        window.navigationBarColor = M3Color.SURFACE

        val cat = SettingsModel.byId(intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        val prefs = M3Prefs.sp(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3Color.SURFACE)
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(M3Ui.dp(this@CategoryActivity, 8), M3Ui.dp(this@CategoryActivity, 16),
                M3Ui.dp(this@CategoryActivity, 16), M3Ui.dp(this@CategoryActivity, 8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(M3Color.ON_SURFACE)
            textSize = 30f
            setPadding(M3Ui.dp(this@CategoryActivity, 12), 0, M3Ui.dp(this@CategoryActivity, 12), M3Ui.dp(this@CategoryActivity, 2))
            setOnClickListener { finish() }
        })
        bar.addView(M3Ui.text(this, cat.title, 22f, M3Color.ON_SURFACE, true))
        root.addView(bar)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@CategoryActivity, 16), 0, M3Ui.dp(this@CategoryActivity, 16),
                M3Ui.dp(this@CategoryActivity, 24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        cat.groups.forEach { group ->
            if (!group.title.isNullOrEmpty()) {
                content.addView(M3Ui.text(this, group.title, 13f, M3Color.ON_SURFACE_VAR, true).apply {
                    setPadding(M3Ui.dp(this@CategoryActivity, 4), M3Ui.dp(this@CategoryActivity, 16),
                        M3Ui.dp(this@CategoryActivity, 4), M3Ui.dp(this@CategoryActivity, 8))
                })
            } else if (content.childCount > 0) {
                content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, M3Ui.dp(this@CategoryActivity, 12)) })
            }
            val card = M3Ui.card(this).apply {
                setContentPadding(M3Ui.dp(this@CategoryActivity, 16), M3Ui.dp(this@CategoryActivity, 4),
                    M3Ui.dp(this@CategoryActivity, 16), M3Ui.dp(this@CategoryActivity, 4))
            }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            group.rows.forEachIndexed { index, row ->
                inner.addView(buildRow(prefs, row))
                if (index != group.rows.lastIndex) {
                    inner.addView(View(this).apply {
                        setBackgroundColor(M3Color.OUTLINE_VAR)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                            leftMargin = M3Ui.dp(this@CategoryActivity, 0)
                        }
                    })
                }
            }
            card.addView(inner)
            content.addView(card)
        }
    }

    private fun buildRow(prefs: SharedPreferences, row: Row): View {
        return when (row.kind) {
            Row.SLIDER -> {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, M3Ui.dp(this@CategoryActivity, 12), 0, M3Ui.dp(this@CategoryActivity, 8))
                }
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                head.addView(M3Ui.text(this, row.title, 16f, M3Color.ON_SURFACE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val valueText = M3Ui.text(this, "", 14f, M3Color.PRIMARY)
                head.addView(valueText)
                box.addView(head)
                val slider = Slider(this).apply {
                    valueFrom = row.min.toFloat()
                    valueTo = row.max.toFloat()
                    stepSize = 1f
                    value = prefs.getInt(row.key, row.def).toFloat().coerceIn(row.min.toFloat(), row.max.toFloat())
                    valueText.text = value.toInt().toString()
                    addOnChangeListener { _, v, _ ->
                        valueText.text = v.toInt().toString()
                        prefs.edit().putInt(row.key, v.toInt()).apply()
                    }
                }
                box.addView(slider)
                box
            }
            Row.ACTION -> {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, M3Ui.dp(this@CategoryActivity, 16), 0, M3Ui.dp(this@CategoryActivity, 16))
                    isClickable = true
                    addView(LinearLayout(this@CategoryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(M3Ui.text(this@CategoryActivity, row.title, 16f, M3Color.ON_SURFACE))
                        if (row.summary != null) addView(M3Ui.text(this@CategoryActivity, row.summary!!, 12f, M3Color.ON_SURFACE_VAR))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(M3Ui.text(this@CategoryActivity, "›", 24f, M3Color.ON_SURFACE_VAR))
                    setOnClickListener {
                        if (row.action == "legacy") {
                            runCatching {
                                startActivity(Intent().setClassName(packageName,
                                    "com.u9521.wooboxforredmagicos.activity.SettingsActivity"))
                            }
                        }
                    }
                }
            }
            else -> {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, M3Ui.dp(this@CategoryActivity, 14), 0, M3Ui.dp(this@CategoryActivity, 14))
                }
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(M3Ui.text(this, row.title, 16f, M3Color.ON_SURFACE))
                if (!row.summary.isNullOrEmpty()) {
                    texts.addView(M3Ui.text(this, row.summary, 12f, M3Color.ON_SURFACE_VAR).apply {
                        setPadding(0, M3Ui.dp(this@CategoryActivity, 2), 0, 0)
                    })
                }
                line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val sw = MaterialSwitch(this).apply {
                    isChecked = prefs.getBoolean(row.key, false)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(row.key, checked).apply()
                    }
                }
                line.addView(sw)
                line
            }
        }
    }
}

// ---------------- 屏下显示区域 ----------------
class CameraSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = M3Color.SURFACE
        window.navigationBarColor = M3Color.SURFACE
        val prefs = M3Prefs.sp(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3Color.SURFACE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(M3Ui.dp(this@CameraSettingsActivity, 8), M3Ui.dp(this@CameraSettingsActivity, 16),
                M3Ui.dp(this@CameraSettingsActivity, 16), M3Ui.dp(this@CameraSettingsActivity, 8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(M3Color.ON_SURFACE)
            textSize = 30f
            setPadding(M3Ui.dp(this@CameraSettingsActivity, 12), 0, M3Ui.dp(this@CameraSettingsActivity, 12), M3Ui.dp(this@CameraSettingsActivity, 2))
            setOnClickListener { finish() }
        })
        bar.addView(M3Ui.text(this, "屏下显示区域", 22f, M3Color.ON_SURFACE, true))
        root.addView(bar)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@CameraSettingsActivity, 16), 0, M3Ui.dp(this@CameraSettingsActivity, 16),
                M3Ui.dp(this@CameraSettingsActivity, 24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 预览卡
        val previewCard = M3Ui.card(this, 20).apply {
            setContentPadding(M3Ui.dp(this@CameraSettingsActivity, 12), M3Ui.dp(this@CameraSettingsActivity, 12),
                M3Ui.dp(this@CameraSettingsActivity, 12), M3Ui.dp(this@CameraSettingsActivity, 12))
        }
        val preview = CameraPreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, M3Ui.dp(this@CameraSettingsActivity, 150))
            styleProvider = { com.u9521.wooboxforredmagicos.activity.MoonStyle.get(this@CameraSettingsActivity) }
        }
        previewCard.addView(preview)
        content.addView(previewCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = M3Ui.dp(this@CameraSettingsActivity, 8) })

        // 分段选择
        val seg = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(0, M3Ui.dp(this@CameraSettingsActivity, 14), 0, 0)
            addView(segButton("弯月", 0))
            addView(segButton("图标", 1))
            addView(segButton("剪影", 2))
            check(if (com.u9521.wooboxforredmagicos.activity.MoonStyle.get(this@CameraSettingsActivity) == 2) R.id.tag_2
            else if (com.u9521.wooboxforredmagicos.activity.MoonStyle.get(this@CameraSettingsActivity) == 1) R.id.tag_1
            else R.id.tag_0)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val style = when (checkedId) {
                    R.id.tag_1 -> 1
                    R.id.tag_2 -> 2
                    else -> 0
                }
                com.u9521.wooboxforredmagicos.activity.MoonStyle.set(this@CameraSettingsActivity, style)
                preview.invalidate()
            }
        }
        content.addView(seg)

        // 开关卡
        val card = M3Ui.card(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            Triple("moon_notch", "启用", "在摄像头位置绘制遮罩"),
            Triple("moon_allow_light", "亮色模式也显示", "默认只在深色模式显示"),
            Triple("camera_battery", "显示电量", "替代弯月显示充电状态")
        ).forEachIndexed { i, t ->
            inner.addView(switchRow(prefs, t.first, t.second, t.third))
            if (i < 2) inner.addView(View(this).apply {
                setBackgroundColor(M3Color.OUTLINE_VAR)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
        }
        card.addView(inner)
        content.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = M3Ui.dp(this@CameraSettingsActivity, 14) })

        // 名单
        val listCard = M3Ui.card(this).apply {
            isClickable = true
            setContentPadding(M3Ui.dp(this@CameraSettingsActivity, 16), M3Ui.dp(this@CameraSettingsActivity, 16),
                M3Ui.dp(this@CameraSettingsActivity, 16), M3Ui.dp(this@CameraSettingsActivity, 16))
            setOnClickListener {
                startActivity(Intent(this@CameraSettingsActivity,
                    com.u9521.wooboxforredmagicos.activity.MoonAppListActivity::class.java))
            }
        }
        val listRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(M3Ui.text(this@CameraSettingsActivity, "自定义应用名单", 16f, M3Color.ON_SURFACE, true))
            addView(M3Ui.text(this@CameraSettingsActivity, "指定哪些应用显示弯月/图标", 12f, M3Color.ON_SURFACE_VAR))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listRow.addView(M3Ui.text(this@CameraSettingsActivity, "›", 24f, M3Color.ON_SURFACE_VAR))
        listCard.addView(listRow)
        content.addView(listCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = M3Ui.dp(this@CameraSettingsActivity, 12) })

        content.addView(M3Ui.text(this,
            "剪影模式只有黑白两色，暗色图标也能看清，也不会让屏下区域在低亮度下发绿。",
            12f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(M3Ui.dp(this@CameraSettingsActivity, 4), M3Ui.dp(this@CameraSettingsActivity, 12), 0, 0)
        })
    }

    private fun segButton(label: String, tag: Int): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            id = when (tag) { 1 -> R.id.tag_1; 2 -> R.id.tag_2; else -> R.id.tag_0 }
            layoutParams = LinearLayout.LayoutParams(0, M3Ui.dp(this@CameraSettingsActivity, 44), 1f)
        }

    private fun switchRow(prefs: SharedPreferences, key: String, title: String, summary: String): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@CameraSettingsActivity, 14), 0, M3Ui.dp(this@CameraSettingsActivity, 14))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(M3Ui.text(this, title, 16f, M3Color.ON_SURFACE))
        texts.addView(M3Ui.text(this, summary, 12f, M3Color.ON_SURFACE_VAR))
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
        })
        return line
    }
}
