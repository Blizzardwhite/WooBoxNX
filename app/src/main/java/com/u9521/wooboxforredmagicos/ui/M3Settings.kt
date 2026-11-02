package com.u9521.wooboxforredmagicos.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.u9521.wooboxforredmagicos.R

// ---------------- 色板 ----------------
object M3Color {
    var SURFACE = 0xFF000000.toInt()
    var SURFACE_CONT = 0xFF202327.toInt()
    var PRIMARY = 0xFF8BD5C6.toInt()
    var PRIMARY_CONT = 0xFF1F5B52.toInt()
    var ON_PRIMARY = 0xFF00382F.toInt()
    var ON_PRIMARY_CONT = 0xFFA7F2E4.toInt()
    var ON_SURFACE = 0xFFE2E4E2.toInt()
    var ON_SURFACE_VAR = 0xFFC4C6C5.toInt()
    var OUTLINE_VAR = 0xFF444745.toInt()

    fun apply(c: Context) {
        SURFACE = c.getColor(R.color.m3_surface)
        SURFACE_CONT = c.getColor(R.color.m3_surface_container)
        PRIMARY = c.getColor(R.color.m3_primary)
        PRIMARY_CONT = c.getColor(R.color.m3_primary_container)
        ON_PRIMARY = c.getColor(R.color.m3_on_primary)
        ON_PRIMARY_CONT = c.getColor(R.color.m3_on_primary_container)
        ON_SURFACE = c.getColor(R.color.m3_on_surface)
        ON_SURFACE_VAR = c.getColor(R.color.m3_on_surface_variant)
        OUTLINE_VAR = c.getColor(R.color.m3_outline_variant)
    }
}

// ---------------- 主题 ----------------
object M3Theme {
    fun apply(activity: Activity) {
        M3Color.apply(activity)
        val light = !isNight(activity)
        activity.window.statusBarColor = M3Color.SURFACE
        activity.window.navigationBarColor = M3Color.SURFACE
        runCatching {
            val controller = androidx.core.view.WindowCompat.getInsetsController(
                activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
    }

    fun isNight(c: Context): Boolean =
        (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
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
    val action: String? = null,
    val switchKey: String? = null,
    val defStr: String = "",
    val intValue: Boolean = false
) {
    companion object {
        const val SWITCH = 0
        const val SLIDER = 1
        const val ACTION = 2
        const val SWITCH_TEXT = 3
        const val SWITCH_SLIDER = 4
    }
}

class Group(val title: String?, val rows: List<Row>)

class Category(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: Int,
    val groups: List<Group>,
    val parent: String? = null
)

object SettingsModel {

    private fun vol(sw: String, key: String, title: String, def: Int) = Row(
        key = key, title = title, summary = "音量阶数 3 ~ 50",
        kind = Row.SWITCH_SLIDER, min = 3, max = 50, def = def, switchKey = sw
    )

    private fun mock(sw: String, key: String, title: String, defStr: String) = Row(
        key = key, title = title, kind = Row.SWITCH_TEXT, switchKey = sw, defStr = defStr
    )

    private fun page(id: String, title: String, summary: String) =
        Row(title = title, summary = summary, kind = Row.ACTION, action = "page:$id")

    private val camera = Category("camera", "屏下显示区域", "弯月 · 跟随深色模式", 0, emptyList())

    private val status = Category("status", "状态栏", "双击锁屏、图标、网速", 1, listOf(
        Group("交互", listOf(
            Row(key = "status_bar_double_tap_to_sleep", title = "双击状态栏锁屏"),
            Row(key = "statusbar_font_restore", title = "恢复时钟日期图标字体"),
            Row(key = "collapsed_status_bar_clock_display_seconds", title = "下拉状态栏时钟显秒")
        )),
        Group("电量", listOf(
            Row(key = "hide_battery_percentage_icon", title = "隐藏电量百分比", summary = "需要系统开启百分比外显"),
            Row(key = "show_charge_indicator_on_power_plugin", title = "连接电源时显示充电图标"),
            Row(key = "battery_icon_fine_tune", title = "电池显示样式调节", summary = "总开关，关闭后下方三项均不生效"),
            Row(key = "show_battery_meter", title = "显示电池图标", summary = "关闭后电池内显示的内容失效"),
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
            Row(key = "status_bar_dual_row_network_speed", title = "双排网络速度", summary = "总开关，关闭后下方设置均不生效"),
            Row(key = "speed_unit_hide_per_second", title = "隐藏 /s 单位"),
            Row(key = "status_bar_network_speed_dual_row_size", title = "双排网速字号", kind = Row.SLIDER, min = 6, max = 8, def = 6),
            Row(key = "status_bar_network_speed_dual_row_width", title = "双排网速宽度", kind = Row.SLIDER, min = 28, max = 39, def = 35),
            Row(key = "status_bar_network_speed_dual_row_digit_len", title = "双排网速小数位数", kind = Row.SLIDER, min = 0, max = 3, def = 3),
            Row(key = "low_speed_hide_kilo_bytes", title = "低速隐藏阈值", summary = "-1 表示始终显示，单位 KiB/s", kind = Row.SLIDER, min = -1, max = 10, def = -1)
        ))
    ))

    private val notify = Category("notify", "通知", "原生通知图标", 2, listOf(
        Group(null, listOf(
            Row(key = "use_aosp_notify", title = "使用原生通知图标", summary = "状态栏通知变成原生样式"),
            Row(key = "aosp_icon_size_dp", title = "通知图标大小", kind = Row.SLIDER, min = 0, max = 64, def = 16),
            Row(key = "align_fan_refreshrate_aosp", title = "风扇/刷新率图标对齐", summary = "配合原生通知样式使用")
        )),
        Group("系统通知", listOf(
            Row(key = "remove_alert_windows_notification", title = "移除上层显示通知", summary = "去掉「此应用正在其他应用上层显示」提示"),
            Row(key = "block_notification_sound_screen_on", title = "亮屏时屏蔽通知声音和振动", summary = "立即生效，无需重启")
        ))
    ))

    private val qs = Category("qs", "快速设置面板", "行列自定义", 3, listOf(
        Group(null, listOf(
            Row(key = "qs_header_shortcut_redirect_calendar", title = "点击日期跳转第三方日历"),
            Row(key = "qs_header_shortcut_redirect_search", title = "点击搜索打开浏览器"),
            Row(key = "qs_show_carrier", title = "快速设置显示运营商"),
            Row(key = "qs_show_search", title = "快速设置显示搜索按钮")
        )),
        Group("自定义行列", listOf(
            Row(key = "qs_custom_switch", title = "自定义行列数", summary = "总开关，关闭后下方设置均不生效"),
            Row(key = "qs_custom_rows", title = "行数", kind = Row.SLIDER, min = 1, max = 9, def = 3),
            Row(key = "qs_custom_rows_landscape", title = "行数（横屏）", kind = Row.SLIDER, min = 1, max = 4, def = 1),
            Row(key = "qs_custom_columns", title = "列数", kind = Row.SLIDER, min = 1, max = 9, def = 5),
            Row(key = "qs_custom_columns_landscape", title = "列数（横屏）", kind = Row.SLIDER, min = 1, max = 10, def = 7),
            Row(key = "qs_custom_columns_editor", title = "编辑模式列数", kind = Row.SLIDER, min = 1, max = 9, def = 4),
            Row(key = "qs_custom_columns_landscape_editor", title = "编辑模式列数（横屏）", kind = Row.SLIDER, min = 1, max = 10, def = 6)
        ))
    ))

    private val lock = Category("lock", "锁屏", "指纹图标改圆环", 4, listOf(
        Group(null, listOf(
            Row(key = "lockscreen_allow_adjust_volume", title = "允许在锁屏调整音量"),
            Row(key = "fp_ring", title = "锁屏指纹图标改成圆环")
        ))
    ))

    private val other = Category("other", "其他", "系统框架、应用、系统更新、调试", 5, listOf(
        Group("手势与交互", listOf(
            Row(key = "gesture_use_default_digital_assist", title = "手势打开默认数字助理"),
            Row(key = "no_vibrate_volKey_Long_press", title = "禁用长按调节音量的振动"),
            Row(key = "unhide_clipboard_overlay", title = "恢复剪贴板浮窗"),
            Row(key = "aosp_singlehandmode_offest", title = "单手模式偏移", summary = "正数向上，负数向下",
                kind = Row.SWITCH_SLIDER, switchKey = "aosp_singlehandmode_adjust", min = 0, max = 200, def = 0)
        )),
        Group(null, listOf(
            page("framework", "系统框架", "音量阶数、飞行模式、小窗、截图、WLAN"),
            page("apps", "应用与服务", "启动器、安装器、系统设置、双开、MTP"),
            page("update", "系统更新伪装", "型号、IMEI、指纹等设备信息")
        )),
        Group("调试", listOf(
            Row(key = "debug_log", title = "打印模块调试日志", summary = "重启作用域后生效"),
            Row(key = "block_system_server_log", title = "阻止系统输出日志", summary = "实验性功能，目前仅拦截 Telecom 日志")
        )),
        Group(null, listOf(
            Row(title = "旧版界面", summary = "按作用域划分的原始设置页", kind = Row.ACTION, action = "legacy")
        ))
    ))

    private val framework = Category("framework", "系统框架", "改动后需重启手机生效", 5, listOf(
        Group("音量阶数", listOf(
            vol("alarm_volume_steps_switch", "alarm_volume_steps", "闹钟音量阶数", 15),
            vol("media_volume_steps_switch", "media_volume_steps", "媒体音量阶数", 15),
            vol("notify_volume_steps_switch", "notify_volume_steps", "通知音量阶数", 15),
            vol("ring_volume_steps_switch", "ring_volume_steps", "铃声音量阶数", 15),
            vol("vc_call_volume_steps_switch", "vc_call_volume_steps", "通话音量阶数", 10)
        )),
        Group("小窗", listOf(
            Row(key = "rm_window_reply_restriction", title = "去除小窗应用限制"),
            Row(key = "rm_window_reply_count_restriction", title = "去除小窗数量限制", summary = "超过三个时第四个小窗不能靠边挂起")
        )),
        Group("飞行模式", listOf(
            Row(key = "airplane_mode_keep_bt", title = "飞行模式保持蓝牙状态"),
            Row(key = "airplane_mode_keep_wlan", title = "飞行模式保持 WLAN 和热点")
        )),
        Group("特色功能", listOf(
            Row(key = "allow_untrusted_touches", title = "允许不受信任的触摸", summary = "允许高可见度 View 点击穿透，慎用"),
            Row(key = "long_power_key_wakeup_assist", title = "长按电源键启动默认数字助理"),
            Row(key = "block_telemetry_service", title = "阻止启动遥测服务"),
            Row(key = "remove_intent_hijack", title = "去除意图劫持", summary = "还原原生分享界面，双开用户不建议开启")
        )),
        Group("截图", listOf(
            Row(key = "disable_flag_secure", title = "允许截图", summary = "立即生效，无需重启"),
            Row(key = "disable_flag_secure_enhanced", title = "允许截图增强模式", summary = "阻止应用通过隐藏 API 禁止截屏")
        )),
        Group("WLAN 服务", listOf(
            Row(key = "telecom_wlan_cc_val", title = "自定义 WiFi 国家码", summary = "两位字母或数字，默认 us",
                kind = Row.SWITCH_TEXT, switchKey = "custom_wlan_countrycode", defStr = "us"),
            Row(key = "pin_sta_mac", title = "固定终端 MAC 地址", summary = "修改后重启 WiFi 生效",
                kind = Row.SWITCH_TEXT, switchKey = "pin_sta_mac_sw", defStr = "66:31:32:35:39:75"),
            Row(key = "pin_ap_bssid", title = "固定热点随机 BSSID", summary = "修改后重启热点生效",
                kind = Row.SWITCH_TEXT, switchKey = "pin_ap_bssid_sw", defStr = "b4:f3:cb:a7:b8:e7")
        ))
    ), parent = "other")

    private val apps = Category("apps", "应用与服务", "启动器、安装器、系统设置等", 5, listOf(
        Group("启动器", listOf(
            Row(key = "launcher_rm_zvoice_uninstall_dialog", title = "取消智慧语音已卸载弹窗"),
            Row(key = "launcher_force_support_resize_activity", title = "所有 Activity 允许调整大小"),
            Row(key = "launcher_force_show_blacklist_apps", title = "取消隐藏黑名单应用", summary = "例如原生文件、DTS 音效")
        )),
        Group("应用安装器", listOf(
            Row(key = "skip_apk_scan", title = "跳过 APK 扫描"),
            Row(key = "hide_purify_switch", title = "隐藏净化模式开关", summary = "同时隐藏风险提示"),
            Row(key = "installer_hide_store_hint", title = "隐藏从商店安装提示"),
            Row(key = "installer_cts_mode", title = "使用 CTS 测试的安装界面")
        )),
        Group("系统设置", listOf(
            Row(key = "usb_install_switch_skip_verify", title = "禁用 USB 安装账号验证"),
            Row(key = "setting_force_show_va_switch", title = "强制显示手势打开智慧语音开关"),
            Row(key = "setting_force_show_ga_shortcut_switch", title = "强制显示语音唤醒 Google 助理开关"),
            Row(key = "fun_override_max_year", title = "修改时间设置最大年份", summary = "默认 2099",
                kind = Row.SWITCH_TEXT, switchKey = "setting_fun_override_maxyear_sw", defStr = "2099", intValue = true)
        )),
        Group("应用双开", listOf(
            Row(key = "rm_double_low_memory_limit", title = "去除低内存设备双开限制"),
            Row(key = "double_any_app", title = "允许双开任意应用")
        )),
        Group("NFC 与权限", listOf(
            Row(key = "mute_nfc_sound", title = "禁用 NFC 提示音"),
            Row(key = "allow_thirdparty_launcher", title = "允许设置默认第三方桌面")
        )),
        Group("MTP", listOf(
            Row(key = "hide_mtp_category_browse", title = "隐藏 MTP 分类浏览"),
            Row(key = "rename_mtp_storage_root_name", title = "重命名 MTP 存储设备名", summary = "只对路径浏览有效",
                kind = Row.SWITCH_TEXT, switchKey = "rename_mtp_storage_root_name_sw", defStr = "内部存储设备")
        ))
    ), parent = "other")

    private val update = Category("update", "系统更新伪装", "伪装设备信息，阻止更新", 5, listOf(
        Group("伪装设备信息", listOf(
            mock("system_update_mock_model_sw", "system_update_mock_model", "伪装设备型号", "NX769J"),
            mock("system_update_mock_imei_sw", "system_update_mock_imei", "伪装设备 IMEI", "114514191981000"),
            mock("system_update_mock_local_sw", "system_update_mock_local", "伪装设备地区", "zh_CN"),
            mock("system_update_mock_manufacture_sw", "system_update_mock_manufacture", "伪装制造商", "ZTE"),
            mock("system_update_mock_variant_id_sw", "system_update_mock_variant_id", "伪装设备版本", "GEN_NY_CN"),
            mock("system_update_mock_build_display_sw", "system_update_mock_build_display", "伪装 Build.DISPLAY", "RedMagicOS9.5.25_NX769J_NY"),
            mock("system_update_mock_system_inner_version_sw", "system_update_mock_system_inner_version", "伪装系统内部版本号", "GEN_CN_NX769SV1.5.0B25"),
            mock("system_update_mock_fingerprint_sw", "system_update_mock_fingerprint", "伪装系统指纹",
                "nubia/NX769J/NX769J:14/UKQ1.230917.001/20250310.231626:user/release-keys"),
            mock("system_update_mock_sign_sw", "system_update_mock_sign", "伪装系统更新签名和版本",
                "a2e44d1795185b8e3281444007effb7f_UNJ.REDMAGIC.FOTA.14.0.0.2409191549")
        )),
        Group(null, listOf(
            Row(key = "system_update_block_update", title = "阻止更新", summary = "开启后更新会卡在 0%"),
            Row(key = "system_update_log_info", title = "记录更新信息", summary = "把更新信息打印到 Xposed 日志")
        ))
    ), parent = "other")

    private val all: List<Category> = listOf(camera, status, notify, qs, lock, other, framework, apps, update)

    fun categories(): List<Category> = all.filter { it.parent == null }

    fun byId(id: String): Category? = all.firstOrNull { it.id == id }
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

// ---------------- 动作 ----------------
object M3Actions {
    fun restartScope(activity: Activity) {
        val pkgs: List<String> = runCatching {
            activity.resources.getStringArray(R.array.xposed_scope).filter { it != "android" }
        }.getOrDefault(emptyList())
        MaterialAlertDialogBuilder(activity)
            .setTitle("重启作用域")
            .setMessage("将对 ${pkgs.size} 个作用域应用执行 killall，需要 root 权限。")
            .setPositiveButton("确定") { _, _ ->
                runCatching {
                    com.u9521.wooboxforredmagicos.util.ShellUtils.execCommand(
                        pkgs.map { "killall $it" }, true
                    )
                }
                Toast.makeText(activity, "已重启作用域", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun pill(activity: Activity, label: String, chipStyle: Boolean = false, onClick: () -> Unit): TextView {
        val tv = M3Ui.text(activity, label, if (chipStyle) 13f else 12f, M3Color.ON_SURFACE_VAR)
        if (chipStyle) {
            tv.setPadding(M3Ui.dp(activity, 14), M3Ui.dp(activity, 7), M3Ui.dp(activity, 14), M3Ui.dp(activity, 7))
        } else {
            tv.setPadding(M3Ui.dp(activity, 10), M3Ui.dp(activity, 5), M3Ui.dp(activity, 10), M3Ui.dp(activity, 5))
        }
        val d = GradientDrawable()
        d.cornerRadius = M3Ui.dp(activity, if (chipStyle) 16 else 14).toFloat()
        d.setColor(Color.TRANSPARENT)
        d.setStroke(M3Ui.dp(activity, 1), M3Color.OUTLINE_VAR)
        tv.background = d
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }
}

// ---------------- 主页 ----------------
class SettingsHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        M3Theme.apply(this)

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

        // 标题 + 头像
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(M3Ui.text(this, "WooBox NX", 34f, M3Color.ON_SURFACE, true))
        val model = (Build.MANUFACTURER + " " + Build.MODEL).replaceFirstChar { it.uppercase() }
        titleBox.addView(M3Ui.text(this, model + "  ·  v" + com.u9521.wooboxforredmagicos.BuildConfig.VERSION_NAME, 13f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 2), 0, 0)
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val avatar = avatarView()
        rightCol.addView(avatar, LinearLayout.LayoutParams(M3Ui.dp(this, 46), M3Ui.dp(this, 46)))
        header.addView(rightCol, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = M3Ui.dp(this@SettingsHomeActivity, 10) })
        header.setPadding(M3Ui.dp(this, 6), 0, M3Ui.dp(this, 6), 0)
        content.addView(header)
        header.post {
            val h = titleBox.height
            if (h > 0) {
                val min = M3Ui.dp(this, 42)
                val max = M3Ui.dp(this, 58)
                val size = (h * 0.86f).toInt().coerceIn(min, max)
                avatar.layoutParams = LinearLayout.LayoutParams(size, size)
            }
        }

        // 状态 chips
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 12), 0, M3Ui.dp(this@SettingsHomeActivity, 4))
        }
        val scopeCount = runCatching {
            resources.getStringArray(R.array.xposed_scope).count { it != "android" }
        }.getOrDefault(0)
        chips.addView(chip("● 运行中", M3Color.ON_PRIMARY_CONT, M3Color.PRIMARY_CONT))
        chips.addView(chip("作用域 $scopeCount 个", M3Color.ON_SURFACE_VAR, Color.TRANSPARENT))
        chips.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chips.addView(M3Actions.pill(this, "重启作用域", true) { M3Actions.restartScope(this) })
        content.addView(chips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 分类卡片
        SettingsModel.categories().forEach { cat ->
            content.addView(categoryCard(cat), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = M3Ui.dp(this@SettingsHomeActivity, 12) })
        }

        // 关于
        content.addView(categoryCard(Category("about", "关于", "版本、开发者与致谢", 6, emptyList())),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = M3Ui.dp(this@SettingsHomeActivity, 12) })

        // 底部按钮
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 20), 0, 0)
        }
        val b1 = tonalButton("备份设置") { startBackup() }
        val b2 = tonalButton("恢复配置") { confirmRestore() }
        val lp = LinearLayout.LayoutParams(0, M3Ui.dp(this, 52), 1f)
        btns.addView(b1, lp)
        btns.addView(b2, LinearLayout.LayoutParams(0, M3Ui.dp(this, 52), 1f).apply {
            leftMargin = M3Ui.dp(this@SettingsHomeActivity, 12)
        })
        content.addView(btns)
    }

    private fun tonalButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            backgroundTintList = ColorStateList.valueOf(M3Color.PRIMARY_CONT)
            setTextColor(M3Color.ON_PRIMARY_CONT)
            cornerRadius = M3Ui.dp(this@SettingsHomeActivity, 24)
            setOnClickListener { onClick() }
        }

    private val backupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val json = org.json.JSONObject(M3Prefs.sp(this).all as Map<*, *>).toString()
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            Toast.makeText(this, "备份成功", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "备份失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val restoreLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@runCatching
            val obj = org.json.JSONObject(text)
            val edit = M3Prefs.sp(this).edit().clear()
            obj.keys().forEach { k ->
                when (val v = obj[k]) {
                    is Boolean -> edit.putBoolean(k, v)
                    is Int -> edit.putInt(k, v)
                    is Long -> edit.putLong(k, v)
                    is Double -> edit.putFloat(k, v.toFloat())
                    is String -> edit.putString(k, v)
                    else -> edit.putString(k, v.toString())
                }
            }
            edit.apply()
            Toast.makeText(this, "已恢复配置，建议重启作用域", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "恢复失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBackup() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        backupLauncher.launch("Woobox_backup_$stamp.json")
    }

    private fun confirmRestore() {
        MaterialAlertDialogBuilder(this)
            .setTitle("恢复配置")
            .setMessage("选择一个 JSON 备份文件恢复模块设置，现有设置会被覆盖。")
            .setPositiveButton("选择文件") { _, _ -> restoreLauncher.launch(arrayOf("application/json")) }
            .setNegativeButton("取消", null)
            .show()
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
                when (cat.id) {
                    "camera" -> startActivity(Intent(this@SettingsHomeActivity, CameraSettingsActivity::class.java))
                    "about" -> startActivity(Intent(this@SettingsHomeActivity, AboutActivity::class.java))
                    else -> startActivity(Intent(this@SettingsHomeActivity, CategoryActivity::class.java).apply {
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

    private fun avatarView(): ImageView {
        val size = M3Ui.dp(this, 46)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val d: Drawable? = getDrawable(R.drawable.app_icon)
        if (d != null) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.clipPath(android.graphics.Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
            })
            d.setBounds(0, 0, size, size)
            d.draw(c)
            iv.setImageBitmap(bmp)
        }
        return iv
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
        M3Theme.apply(this)

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
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(M3Actions.pill(this, "重启作用域") { M3Actions.restartScope(this) })
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
                val valueText = M3Ui.text(this, "", 14f, M3Color.PRIMARY).apply {
                    setPadding(M3Ui.dp(this@CategoryActivity, 8), 0, 0, 0)
                }
                head.addView(valueText)
                box.addView(head)
                if (!row.summary.isNullOrEmpty()) {
                    box.addView(M3Ui.text(this, row.summary, 12f, M3Color.ON_SURFACE_VAR))
                }
                val slider = Slider(this).apply {
                    valueFrom = row.min.toFloat()
                    valueTo = row.max.toFloat()
                    stepSize = 1f
                    setTickVisible(false)
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

            Row.SWITCH_SLIDER -> {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, M3Ui.dp(this@CategoryActivity, 12), 0, M3Ui.dp(this@CategoryActivity, 8))
                }
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(M3Ui.text(this, row.title, 16f, M3Color.ON_SURFACE))
                if (!row.summary.isNullOrEmpty()) {
                    texts.addView(M3Ui.text(this, row.summary, 12f, M3Color.ON_SURFACE_VAR))
                }
                head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val valueText = M3Ui.text(this, "", 14f, M3Color.PRIMARY).apply {
                    setPadding(M3Ui.dp(this@CategoryActivity, 8), 0, M3Ui.dp(this@CategoryActivity, 4), 0)
                }
                head.addView(valueText)
                head.addView(MaterialSwitch(this).apply {
                    val key = row.switchKey
                    if (key != null) {
                        isChecked = prefs.getBoolean(key, false)
                        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
                    }
                })
                box.addView(head)
                val slider = Slider(this).apply {
                    valueFrom = row.min.toFloat()
                    valueTo = row.max.toFloat()
                    stepSize = 1f
                    setTickVisible(false)
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

            Row.SWITCH_TEXT -> {
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(M3Ui.text(this, row.title, 16f, M3Color.ON_SURFACE))
                if (!row.summary.isNullOrEmpty()) {
                    texts.addView(M3Ui.text(this, row.summary, 12f, M3Color.ON_SURFACE_VAR))
                }
                val valueView = M3Ui.text(this, "当前：" + currentValue(prefs, row), 12f, M3Color.PRIMARY)
                texts.addView(valueView)
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, M3Ui.dp(this@CategoryActivity, 10), 0, M3Ui.dp(this@CategoryActivity, 10))
                    isClickable = true
                    setOnClickListener {
                        editValue(row) { saved -> valueView.text = "当前：$saved" }
                    }
                }
                line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (row.switchKey != null) {
                    line.addView(MaterialSwitch(this).apply {
                        val key = row.switchKey
                        isChecked = prefs.getBoolean(key, false)
                        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
                    })
                } else {
                    line.addView(M3Ui.text(this, "›", 24f, M3Color.ON_SURFACE_VAR))
                }
                line
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
                        if (row.summary != null) {
                            addView(M3Ui.text(this@CategoryActivity, row.summary!!, 12f, M3Color.ON_SURFACE_VAR))
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(M3Ui.text(this@CategoryActivity, "›", 24f, M3Color.ON_SURFACE_VAR))
                    setOnClickListener {
                        val action = row.action ?: return@setOnClickListener
                        if (action.startsWith("page:")) {
                            startActivity(Intent(this@CategoryActivity, CategoryActivity::class.java)
                                .putExtra("id", action.removePrefix("page:")))
                        } else if (action == "legacy") {
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
                    setPadding(0, M3Ui.dp(this@CategoryActivity, 10), 0, M3Ui.dp(this@CategoryActivity, 10))
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

    private fun currentValue(prefs: SharedPreferences, row: Row): String =
        if (row.intValue) prefs.getInt(row.key, row.defStr.toIntOrNull() ?: 0).toString()
        else prefs.getString(row.key, row.defStr).orEmpty()

    private fun editValue(row: Row, onSaved: (String) -> Unit) {
        val prefs = M3Prefs.sp(this)
        val input = android.widget.EditText(this).apply {
            setText(currentValue(prefs, row))
            setSelection(text.length)
            setSingleLine(true)
            setTextColor(M3Color.ON_SURFACE)
            setHintTextColor(M3Color.ON_SURFACE_VAR)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(M3Ui.dp(this@CategoryActivity, 24), M3Ui.dp(this@CategoryActivity, 8),
                M3Ui.dp(this@CategoryActivity, 24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(row.title)
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                if (row.intValue) {
                    val n = v.toIntOrNull()
                    if (n == null) {
                        Toast.makeText(this, "请输入整数", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    prefs.edit().putInt(row.key, n).apply()
                } else {
                    prefs.edit().putString(row.key, v).apply()
                }
                onSaved(v)
                Toast.makeText(this, "设置成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

// ---------------- 屏下显示区域 ----------------
class CameraSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        M3Theme.apply(this)
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
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(M3Actions.pill(this, "重启作用域") { M3Actions.restartScope(this) })
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
            addView(segButton("纯黑", 3))
            check(when (com.u9521.wooboxforredmagicos.activity.MoonStyle.get(this@CameraSettingsActivity)) {
                2 -> R.id.tag_2
                3 -> R.id.tag_3
                1 -> R.id.tag_1
                else -> R.id.tag_0
            })
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val style = when (checkedId) {
                    R.id.tag_1 -> 1
                    R.id.tag_2 -> 2
                    R.id.tag_3 -> 3
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
            addView(M3Ui.text(this@CameraSettingsActivity, "指定哪些应用显示屏下内容", 12f, M3Color.ON_SURFACE_VAR))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listRow.addView(M3Ui.text(this@CameraSettingsActivity, "›", 24f, M3Color.ON_SURFACE_VAR))
        listCard.addView(listRow)
        content.addView(listCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = M3Ui.dp(this@CameraSettingsActivity, 12) })

        content.addView(M3Ui.text(this,
            "剪影只有黑白两色，暗色图标也能看清；纯黑遮罩只画黑圆，不显示任何内容。",
            12f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(M3Ui.dp(this@CameraSettingsActivity, 4), M3Ui.dp(this@CameraSettingsActivity, 12), 0, 0)
        })
    }

    private fun segButton(label: String, tag: Int): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            id = when (tag) { 1 -> R.id.tag_1; 2 -> R.id.tag_2; 3 -> R.id.tag_3; else -> R.id.tag_0 }
            layoutParams = LinearLayout.LayoutParams(0, M3Ui.dp(this@CameraSettingsActivity, 44), 1f)
        }

    private fun switchRow(prefs: SharedPreferences, key: String, title: String, summary: String): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@CameraSettingsActivity, 10), 0, M3Ui.dp(this@CameraSettingsActivity, 10))
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


// ---------------- 关于 ----------------
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        M3Theme.apply(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3Color.SURFACE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(M3Ui.dp(this@AboutActivity, 8), M3Ui.dp(this@AboutActivity, 16),
                M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(M3Color.ON_SURFACE)
            textSize = 30f
            setPadding(M3Ui.dp(this@AboutActivity, 12), 0, M3Ui.dp(this@AboutActivity, 12), M3Ui.dp(this@AboutActivity, 2))
            setOnClickListener { finish() }
        })
        bar.addView(M3Ui.text(this, "关于", 22f, M3Color.ON_SURFACE, true))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(M3Actions.pill(this, "重启作用域") { M3Actions.restartScope(this) })
        root.addView(bar)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@AboutActivity, 16), 0, M3Ui.dp(this@AboutActivity, 16),
                M3Ui.dp(this@AboutActivity, 24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 模块
        val appCard = M3Ui.card(this).apply {
            setContentPadding(M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16),
                M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16))
        }
        val appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appRow.addView(circleImage(R.drawable.app_icon, 56))
        val appTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@AboutActivity, 16), 0, 0, 0)
        }
        appTexts.addView(M3Ui.text(this, getString(R.string.app_name), 18f, M3Color.ON_SURFACE, true))
        appTexts.addView(M3Ui.text(this,
            com.u9521.wooboxforredmagicos.BuildConfig.VERSION_NAME,
            13f, M3Color.ON_SURFACE_VAR))
        appRow.addView(appTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appCard.addView(appRow)
        content.addView(appCard)

        content.addView(M3Ui.text(this, "本项目是基于 WooBox For RedMagicOS 开发的非官方独立演进版",
            12f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(M3Ui.dp(this@AboutActivity, 6), M3Ui.dp(this@AboutActivity, 10),
                M3Ui.dp(this@AboutActivity, 6), 0)
        })

        content.addView(M3Ui.text(this, "开发者", 13f, M3Color.ON_SURFACE_VAR, true).apply {
            setPadding(M3Ui.dp(this@AboutActivity, 4), M3Ui.dp(this@AboutActivity, 20),
                M3Ui.dp(this@AboutActivity, 4), M3Ui.dp(this@AboutActivity, 8))
        })
        val devCard = M3Ui.card(this)
        val devInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devInner.addView(personRow(R.drawable.lt, "乌堆小透明", "LittleTurtle2333", "coolmarket://u/883441",
            "http://www.coolapk.com/u/883441", "乌堆小透明：靓仔，点个关注吧！"))
        devInner.addView(View(this).apply {
            setBackgroundColor(M3Color.OUTLINE_VAR)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        devInner.addView(personRow(R.drawable.u9521, "9521", "u9521", "https://github.com/u9521",
            "https://github.com/u9521", "9521：关注了也不一定更新喵"))
        devInner.addView(View(this).apply {
            setBackgroundColor(M3Color.OUTLINE_VAR)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        devInner.addView(gifPersonRow(R.drawable.blizzard, "Blizzardwhite", "酷安 u/1636422",
            "https://www.coolapk.com/u/1636422", "Owo"))
        devCard.addView(devInner)
        content.addView(devCard)

        content.addView(M3Ui.text(this, "致谢", 13f, M3Color.ON_SURFACE_VAR, true).apply {
            setPadding(M3Ui.dp(this@AboutActivity, 4), M3Ui.dp(this@AboutActivity, 20),
                M3Ui.dp(this@AboutActivity, 4), M3Ui.dp(this@AboutActivity, 8))
        })

        val linkCard = M3Ui.card(this).apply {
            setContentPadding(M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16),
                M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16))
            isClickable = true
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "https://github.com/u9521/WooBoxForRedmagicOS/graphs/contributors")))
                }
            }
        }
        val linkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        linkRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(M3Ui.text(this@AboutActivity, "贡献者名单", 16f, M3Color.ON_SURFACE))
            addView(M3Ui.text(this@AboutActivity, "感谢每一位提交代码和反馈问题的朋友", 12f, M3Color.ON_SURFACE_VAR))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        linkRow.addView(M3Ui.text(this@AboutActivity, "›", 24f, M3Color.ON_SURFACE_VAR))
        linkCard.addView(linkRow)
        content.addView(linkCard)
    }

    private fun letterAvatar(letter: String, sizeDp: Int): View {
        val size = M3Ui.dp(this, sizeDp)
        val tv = TextView(this).apply {
            text = letter
            setTextColor(M3Color.ON_PRIMARY_CONT)
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(M3Color.PRIMARY_CONT)
        tv.background = d
        tv.layoutParams = LinearLayout.LayoutParams(size, size)
        return tv
    }

    private fun circleImage(resId: Int, sizeDp: Int): ImageView {
        val size = M3Ui.dp(this, sizeDp)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val d = getDrawable(resId)
        if (d != null) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.clipPath(android.graphics.Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
            })
            d.setBounds(0, 0, size, size)
            d.draw(c)
            iv.setImageBitmap(bmp)
        }
        iv.layoutParams = LinearLayout.LayoutParams(size, size)
        return iv
    }

    private fun gifPersonRow(resId: Int, name: String, handle: String, url: String, toast: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@AboutActivity, 12), 0, M3Ui.dp(this@AboutActivity, 12))
            isClickable = true
            setOnClickListener {
                Toast.makeText(this@AboutActivity, toast, Toast.LENGTH_SHORT).show()
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }
        }
        row.addView(GifCircleView(this, resId, M3Ui.dp(this, 44)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@AboutActivity, 14), 0, 0, 0)
        }
        texts.addView(M3Ui.text(this, name, 16f, M3Color.ON_SURFACE, true))
        texts.addView(M3Ui.text(this, handle, 12f, M3Color.ON_SURFACE_VAR))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(M3Ui.text(this, "›", 24f, M3Color.ON_SURFACE_VAR))
        return row
    }

    private fun personRow(resId: Int, name: String, handle: String, primary: String, fallback: String, toast: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@AboutActivity, 12), 0, M3Ui.dp(this@AboutActivity, 12))
            isClickable = true
            setOnClickListener {
                android.widget.Toast.makeText(this@AboutActivity, toast, android.widget.Toast.LENGTH_SHORT).show()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(primary)))
                } catch (e: Exception) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fallback)))
                    }
                }
            }
        }
        if (resId == 0) row.addView(letterAvatar(name.take(1), 44)) else row.addView(circleImage(resId, 44))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@AboutActivity, 14), 0, 0, 0)
        }
        texts.addView(M3Ui.text(this, name, 16f, M3Color.ON_SURFACE, true))
        texts.addView(M3Ui.text(this, handle, 12f, M3Color.ON_SURFACE_VAR))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(M3Ui.text(this, "›", 24f, M3Color.ON_SURFACE_VAR))
        return row
    }
}

/** 圆形裁切的 GIF 头像 */
class GifCircleView(context: Context, resId: Int, sizePx: Int) : androidx.appcompat.widget.AppCompatImageView(context) {
    init {
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        scaleType = ScaleType.CENTER_CROP
        runCatching {
            val d = android.graphics.ImageDecoder.decodeDrawable(
                android.graphics.ImageDecoder.createSource(resources, resId)
            )
            setImageDrawable(d)
            if (d is android.graphics.drawable.AnimatedImageDrawable) d.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(android.graphics.Path().apply {
            addCircle(width / 2f, height / 2f, minOf(width, height) / 2f, android.graphics.Path.Direction.CW)
        })
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }
}