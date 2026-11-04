package com.bw.wooboxnx.ui

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
import com.bw.wooboxnx.R

// ---------------- 色板 ----------------
object M3Color {
    var SURFACE = 0xFF000000.toInt()
    var SURFACE_CONT = 0xFF1F1F1F.toInt()
    var PRIMARY = 0xFF8BD5C6.toInt()
    var PRIMARY_CONT = 0xFF1F5B52.toInt()
    var ON_PRIMARY = 0xFF00382F.toInt()
    var ON_PRIMARY_CONT = 0xFFA7F2E4.toInt()
    var ON_SURFACE = 0xFFE4E4E4.toInt()
    var ON_SURFACE_VAR = 0xFFC6C6C6.toInt()
    var OUTLINE_VAR = 0xFF454545.toInt()
    var ERROR_CONT = 0xFF8C1D18.toInt()
    var ON_ERROR_CONT = 0xFFF9DEDC.toInt()
    var ICON_BG = 0xFF2C2C2C.toInt()
    var ON_ICON_BG = 0xFFDCDCDC.toInt()
    var WARN_CONT = 0xFF5C4600.toInt()
    var ON_WARN_CONT = 0xFFFFE08A.toInt()

    fun apply(c: Context) {
        SURFACE = c.getColor(R.color.m3_surface)
        SURFACE_CONT = c.getColor(R.color.m3_surface_container)
        PRIMARY = c.getColor(R.color.m3_primary)
        PRIMARY_CONT = c.getColor(R.color.m3_primary_container)
        ICON_BG = c.getColor(R.color.m3_icon_bg)
        ON_ICON_BG = c.getColor(R.color.m3_on_icon_bg)
        ON_PRIMARY = c.getColor(R.color.m3_on_primary)
        ON_PRIMARY_CONT = c.getColor(R.color.m3_on_primary_container)
        ON_SURFACE = c.getColor(R.color.m3_on_surface)
        ON_SURFACE_VAR = c.getColor(R.color.m3_on_surface_variant)
        OUTLINE_VAR = c.getColor(R.color.m3_outline_variant)
        val night = M3Theme.isNight(c)
        ERROR_CONT = if (night) 0xFF8C1D18.toInt() else 0xFFF9DEDC.toInt()
        ON_ERROR_CONT = if (night) 0xFFF9DEDC.toInt() else 0xFF410002.toInt()
        WARN_CONT = if (night) 0xFF5C4600.toInt() else 0xFFFFE08A.toInt()
        ON_WARN_CONT = if (night) 0xFFFFE08A.toInt() else 0xFF3F2E00.toInt()
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

/**
 * 记录"上次重启作用域时的配置基线"，
 * 用来判断当前设置是否真的和已生效的配置不同（改了又改回去就不算脏）。
 */
object M3Dirty {

    class Item(val key: String, val title: String, val catId: String?, val reboot: Boolean)

    class State(val items: List<Item>) {
        val restartTitles get() = items.filter { !it.reboot }.map { it.title }
        val rebootTitles get() = items.filter { it.reboot }.map { it.title }
        val dirty get() = items.isNotEmpty()
    }

    private const val FILE = "prefs_baseline.txt"

    private fun snapshot(c: Context): Map<String, String> =
        M3Prefs.sp(c).all.filterKeys { it != "scope_dirty" }
            .mapValues { it.value.toString() }

    private fun file(c: Context) = java.io.File(c.filesDir, FILE)

    private fun readBaseline(c: Context): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        runCatching {
            file(c).readLines().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) map[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        return map
    }

    private fun writeBaseline(c: Context, map: Map<String, String>) {
        runCatching {
            file(c).writeText(map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    private fun marker(c: Context) = java.io.File(c.filesDir, "baseline_boot.txt")

    fun ensureBaseline(c: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        val stored = runCatching { marker(c).readText().trim().toLong() }.getOrNull() ?: -1L
        if (!file(c).exists()) {
            writeBaseline(c, snapshot(c))
            runCatching { marker(c).writeText(now.toString()) }
            return
        }
        if (stored > now) {
            // 手机重启过：android 作用域的改动已经生效，重新建立基线
            writeBaseline(c, snapshot(c))
            M3Prefs.sp(c).edit().putBoolean("scope_dirty", false).apply()
        }
        // 标记只在明显过期时重写，避免每次回到前台都写盘
        if (kotlin.math.abs(now - stored) > 30_000L) {
            runCatching { marker(c).writeText(now.toString()) }
        }
    }

    /** 与基线比较，返回待生效的项目（分成「重启手机」和「重启作用域」两组） */
    fun check(c: Context): State {
        ensureBaseline(c)
        val base = readBaseline(c)
        val cur = snapshot(c)
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        val keys = HashSet<String>()
        keys.addAll(base.keys)
        keys.addAll(cur.keys)
        for (k in keys) {
            if (base[k] == cur[k]) continue
            val title = SettingsModel.titleOf(k) ?: k
            if (!seen.add(title)) continue
            items.add(
                Item(
                    key = k,
                    title = title,
                    catId = SettingsModel.categoryOf(k),
                    reboot = SettingsModel.scopeOf(k) == Row.REBOOT
                )
            )
        }
        items.sortBy { if (it.reboot) 0 else 1 }
        M3Prefs.sp(c).edit().putBoolean("scope_dirty", items.isNotEmpty()).apply()
        return State(items)
    }

    /** 重启作用域之后：非「重启手机」类改动视为已生效 */
    fun markRestarted(c: Context) {
        val base = readBaseline(c)
        val cur = snapshot(c)
        val merged = LinkedHashMap(cur)
        val keys = HashSet<String>()
        keys.addAll(base.keys)
        keys.addAll(cur.keys)
        for (k in keys) {
            if (SettingsModel.scopeOf(k) != Row.REBOOT) continue
            if (base.containsKey(k)) merged[k] = base[k]!! else merged.remove(k)
        }
        writeBaseline(c, merged)
        check(c)
    }

    /** 撤销所有未生效的修改，恢复到上次生效时的值 */
    fun revertPending(c: Context) {
        val base = readBaseline(c)
        val all = M3Prefs.sp(c).all
        val edit = M3Prefs.sp(c).edit()
        for ((k, baseVal) in base) {
            val curVal = all[k]
            if (curVal == null) {
                edit.putString(k, baseVal)
                continue
            }
            if (curVal.toString() == baseVal) continue
            when (curVal) {
                is Boolean -> edit.putBoolean(k, baseVal.toBoolean())
                is Int -> edit.putInt(k, baseVal.toIntOrNull() ?: 0)
                is Long -> edit.putLong(k, baseVal.toLongOrNull() ?: 0L)
                is Float -> edit.putFloat(k, baseVal.toFloatOrNull() ?: 0f)
                else -> edit.putString(k, baseVal)
            }
        }
        for (k in all.keys) {
            if (k != "scope_dirty" && !base.containsKey(k)) edit.remove(k)
        }
        edit.apply()
        check(c)
    }

    /** 所有改动都已生效（重启手机后用不到，保留给恢复/重置场景） */
    fun markAllApplied(c: Context) {
        writeBaseline(c, snapshot(c))
        M3Prefs.sp(c).edit().putBoolean("scope_dirty", false).apply()
    }
}
/** 模块运行状态（靠 SystemUI hook 写的心跳判断） */
object M3Status {
    /** 某个作用域本次开机是否写出过心跳 */
    fun scopeAlive(c: Context, pkg: String): Boolean = runCatching {
        aliveFile(java.io.File(c.filesDir, "beat_" + pkg.replace('.', '_') + ".txt"))
    }.getOrDefault(false)

    private fun aliveFile(f: java.io.File): Boolean {
        if (!f.exists()) return false
        val parts = f.readText().trim().split("|")
        if (parts.size < 2) return false
        val elapsed = parts[0].toLongOrNull() ?: return false
        val wall = parts[1].toLongOrNull() ?: return false
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        if (elapsed <= 0L || elapsed > nowElapsed + 5000L) return false
        return kotlin.math.abs((wall - elapsed) - (nowWall - nowElapsed)) < 60_000L
    }

    /** 当前安装的模块版本（安装时间戳） */
    private fun installedVersion(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime.toString()
    }.getOrDefault("")

    /** 本次开机心跳存在，但来自旧版本代码（进程还没重启） */
    fun scopeStale(c: Context, pkg: String): Boolean {
        if (!scopeAlive(c, pkg)) return false
        val f = java.io.File(c.filesDir, "beat_" + pkg.replace('.', '_') + ".txt")
        val v = runCatching { f.readText().trim().split("|").getOrNull(2) }.getOrNull()
        val cur = installedVersion(c)
        return !v.isNullOrEmpty() && cur.isNotEmpty() && v != cur
    }

    /** 常驻进程：这几次开机后应该总能收到心跳，用来判断"是否整体生效" */
    private val coreScopes = listOf(
        "com.android.systemui",
        "com.zte.mifavor.launcher",
        "com.fingerprint.sensorservice"
    )

    /** 0=未激活 1=部分生效 2=全部生效 */
    fun level(c: Context): Int {
        val alive = coreScopes.count { scopeAlive(c, it) }
        return when {
            alive == 0 -> 0
            alive == coreScopes.size -> 2
            else -> 1
        }
    }

    fun hookAlive(c: Context): Boolean = runCatching {
        val f = java.io.File(c.filesDir, "hook_alive.txt")
        if (!f.exists()) return false
        val parts = f.readText().trim().split("|")
        if (parts.size != 2) return false
        val elapsed = parts[0].toLongOrNull() ?: return false
        val wall = parts[1].toLongOrNull() ?: return false
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        if (elapsed <= 0L || elapsed > nowElapsed + 5000L) return false
        // 心跳记录的"开机时刻"必须和当前这次开机一致，避免上次开机的心跳误判
        val beatBootWall = wall - elapsed
        val nowBootWall = nowWall - nowElapsed
        kotlin.math.abs(beatBootWall - nowBootWall) < 60_000L
    }.getOrDefault(false)
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
    val intValue: Boolean = false,
    /** 生效方式：RESTART=重启作用域，REBOOT=重启手机 */
    var scope: Int = RESTART
) {
    companion object {
        const val SWITCH = 0
        const val SLIDER = 1
        const val ACTION = 2
        const val SWITCH_TEXT = 3
        const val SWITCH_SLIDER = 4

        const val INSTANT = 0
        const val RESTART = 1
        const val REBOOT = 2
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

    private val camera = Category("camera", "屏下显示区域", "显示图标、电量、自定义名单", 0, listOf(
        Group(null, listOf(
            Row(key = "moon_notch", title = "屏下内容：启用", summary = "在摄像头位置绘制遮罩"),
            Row(key = "moon_allow_light", title = "亮色模式也显示屏下内容"),
            Row(key = "camera_battery", title = "屏下位置显示电量")
        ))
    ))

    private val status = Category("status", "状态栏", "双击锁屏、电量样式、双排网速", 1, listOf(
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

    private val notify = Category("notify", "通知", "原生通知图标、系统通知拦截", 2, listOf(
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

    private val qs = Category("qs", "快速设置面板", "行列自定义、运营商、搜索按钮", 3, listOf(
        Group(null, listOf(
            Row(key = "fix_shade_switch", title = "修复切换闪烁（实验）",
                summary = "固定壁纸缩放，并避免两页遮罩叠加（重启系统界面生效）"),
            Row(key = "shade_scrim", title = "面板模糊底色",
                summary = "模糊壁纸 + 黑色渐变遮罩，两页共用同一张底图，切换不变色（重启系统界面生效）"),
            Row(key = "shade_blur_off", title = "去掉面板模糊",
                summary = "排查用：强制关闭通知中心与快速设置的背景模糊（重启系统界面生效）"),
            Row(key = "qs_header_shortcut_redirect_calendar", title = "点击日期跳转第三方日历"),
            Row(key = "qs_header_shortcut_redirect_search", title = "点击搜索打开浏览器"),
            Row(key = "qs_show_carrier", title = "快速设置显示运营商"),
            Row(key = "qs_show_search", title = "快速设置显示搜索按钮")
        )),
        Group("自定义行列", listOf(
            Row(key = "qs_custom_switch", title = "自定义行列数", summary = "总开关，关闭后下方设置均不生效"),
            Row(key = "qs_custom_rows", title = "行数", kind = Row.SLIDER, min = 1, max = 9, def = 3),
            Row(key = "qs_custom_rows_landscape", title = "行数（横屏）", kind = Row.SLIDER, min = 1, max = 4, def = 1),
            Row(key = "qs_custom_columns", title = "列数", kind = Row.SLIDER, min = 1, max = 9, def = 4),
            Row(key = "qs_custom_columns_landscape", title = "列数（横屏）", kind = Row.SLIDER, min = 1, max = 10, def = 6),
            Row(key = "qs_custom_columns_editor", title = "编辑模式列数", kind = Row.SLIDER, min = 1, max = 9, def = 4),
            Row(key = "qs_custom_columns_landscape_editor", title = "编辑模式列数（横屏）", kind = Row.SLIDER, min = 1, max = 10, def = 6)
        ))
    ))

    private val lock = Category("lock", "锁屏", "指纹圆环、锁屏音量", 4, listOf(
        Group(null, listOf(
            Row(key = "lockscreen_allow_adjust_volume", title = "允许在锁屏调整音量"),
            Row(key = "fp_ring", title = "锁屏指纹图标改成圆环")
        ))
    ))

    /** 系统行为：音量、小窗、飞行模式、截图、WLAN（改动后需重启手机） */
    private val framework = Category("framework", "系统", "音量阶数、小窗、飞行模式、WLAN", 7, listOf(
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
        Group("截图", listOf(
            Row(key = "disable_flag_secure", title = "允许截图", summary = "立即生效，无需重启"),
            Row(key = "disable_flag_secure_enhanced", title = "允许截图增强模式", summary = "阻止应用通过隐藏 API 禁止截屏")
        )),
        Group("其他系统行为", listOf(
            Row(key = "splash_allow_list_all", title = "解除启动画面屏蔽名单",
                summary = "让微信、应用中心、应用市场等也显示系统启动画面（重启手机生效）"),
            Row(key = "allow_untrusted_touches", title = "允许不受信任的触摸", summary = "允许高可见度 View 点击穿透，慎用"),
            Row(key = "block_telemetry_service", title = "阻止启动遥测服务"),
            Row(key = "remove_intent_hijack", title = "去除意图劫持", summary = "还原原生分享界面，双开用户不建议开启")
        )),
        Group("WLAN 服务", listOf(
            Row(key = "telecom_wlan_cc_val", title = "自定义 WiFi 国家码", summary = "两位字母或数字，默认 us",
                kind = Row.SWITCH_TEXT, switchKey = "custom_wlan_countrycode", defStr = "us"),
            Row(key = "pin_sta_mac", title = "固定终端 MAC 地址", summary = "格式 AA:BB:CC:DD:EE:FF，留空不生效",
                kind = Row.SWITCH_TEXT, switchKey = "pin_sta_mac_sw", defStr = ""),
            Row(key = "pin_ap_bssid", title = "固定热点随机 BSSID", summary = "格式 AA:BB:CC:DD:EE:FF，留空不生效",
                kind = Row.SWITCH_TEXT, switchKey = "pin_ap_bssid_sw", defStr = "")
        ))
    ))

    /** 按键与手势 */
    private val keys = Category("keys", "按键与手势", "手势助理、音量键、长按电源键", 9, listOf(
        Group("手势与交互", listOf(
            Row(key = "gesture_use_default_digital_assist", title = "手势打开默认数字助理"),
            Row(key = "no_vibrate_volKey_Long_press", title = "禁用长按调节音量的振动"),
            Row(key = "unhide_clipboard_overlay", title = "恢复剪贴板浮窗"),
            Row(key = "aosp_singlehandmode_offest", title = "单手模式偏移", summary = "正数向上，负数向下",
                kind = Row.SWITCH_SLIDER, switchKey = "aosp_singlehandmode_adjust", min = 0, max = 200, def = 0)
        )),
        Group("长按电源键", listOf(
            Row(key = "long_power_key_wakeup_assist", title = "启动默认数字助理",
                summary = "需系统长按行为为「语音助手」，否则保持原生关机菜单")
        ))
    ))

    /** 应用与服务 */
    private val apps = Category("apps", "应用", "启动器、安装器、双开、负一屏", 10, listOf(
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
        Group("负一屏", listOf(
            Row(key = "miboard_unlock_widgets", title = "允许添加任意小部件",
                summary = "解除 Z-Board 的服务器白名单，需勾选 miboard 作用域并重启")
        )),
        Group("MTP", listOf(
            Row(key = "hide_mtp_category_browse", title = "隐藏 MTP 分类浏览"),
            Row(key = "rename_mtp_storage_root_name", title = "重命名 MTP 存储设备名", summary = "只对路径浏览有效",
                kind = Row.SWITCH_TEXT, switchKey = "rename_mtp_storage_root_name_sw", defStr = "内部存储设备")
        )),
        Group("NFC 与权限", listOf(
            Row(key = "mute_nfc_sound", title = "禁用 NFC 提示音"),
            Row(key = "allow_thirdparty_launcher", title = "允许设置默认第三方桌面")
        ))
    ))

    /** 系统更新 */
    private val update = Category("update", "系统更新", "伪装设备信息、禁止自动更新", 8, listOf(
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
    ))

    /** 工具与调试 */
    private val other = Category("other", "其他", "调试日志、备份与恢复", 5, listOf(
        Group("调试", listOf(
            Row(key = "debug_log", title = "打印模块调试日志", summary = "重启作用域后生效"),
            Row(key = "block_system_server_log", title = "阻止系统输出日志", summary = "实验性功能，目前仅拦截 Telecom 日志")
        )),
        Group("备份与恢复", listOf(
            Row(title = "备份设置", summary = "把当前配置导出为 JSON 文件", kind = Row.ACTION, action = "backup"),
            Row(title = "恢复配置", summary = "从备份文件恢复，会覆盖当前设置", kind = Row.ACTION, action = "restore"),
            Row(title = "恢复默认设置", summary = "清除全部设置和屏下名单，不可撤销", kind = Row.ACTION, action = "reset_all")
        ))
    ))

    /** 这些 key 属于系统框架（android 作用域），改动后必须重启手机 */
    private val rebootKeys = setOf(
        "alarm_volume_steps", "alarm_volume_steps_switch",
        "media_volume_steps", "media_volume_steps_switch",
        "notify_volume_steps", "notify_volume_steps_switch",
        "ring_volume_steps", "ring_volume_steps_switch",
        "vc_call_volume_steps", "vc_call_volume_steps_switch",
        "rm_window_reply_restriction", "rm_window_reply_count_restriction",
        "airplane_mode_keep_bt", "airplane_mode_keep_wlan",
        "disable_flag_secure", "disable_flag_secure_enhanced",
        "allow_untrusted_touches", "block_telemetry_service", "remove_intent_hijack",
        "splash_allow_list_all",
        "telecom_wlan_cc_val", "custom_wlan_countrycode",
        "pin_sta_mac", "pin_sta_mac_sw", "pin_ap_bssid", "pin_ap_bssid_sw",
        "long_power_key_wakeup_assist",
        "remove_alert_windows_notification", "block_notification_sound_screen_on",
        "block_system_server_log"
    )

    private val all: List<Category> = listOf(
        camera, status, notify, qs, lock, framework, keys, apps, update, other
    )

    init {
        all.forEach { cat ->
            cat.groups.forEach { g ->
                g.rows.forEach { r ->
                    if ((r.key != null && r.key in rebootKeys) ||
                        (r.switchKey != null && r.switchKey in rebootKeys)
                    ) {
                        r.scope = Row.REBOOT
                    }
                }
            }
        }
    }

    /** key -> 所属分类 id */
    fun categoryOf(key: String): String? {
        all.forEach { cat ->
            cat.groups.forEach { g ->
                g.rows.forEach { r ->
                    if (r.key == key || r.switchKey == key) return cat.id
                }
            }
        }
        return null
    }
    /** key -> 生效方式 */
    fun scopeOf(key: String): Int {
        all.forEach { cat ->
            cat.groups.forEach { g ->
                g.rows.forEach { r ->
                    if (r.key == key || r.switchKey == key) return r.scope
                }
            }
        }
        return Row.RESTART
    }

    /** key -> 显示标题（用于应用修改清单） */
    fun titleOf(key: String): String? {
        all.forEach { cat ->
            cat.groups.forEach { g ->
                g.rows.forEach { r ->
                    if (r.key == key || r.switchKey == key) return r.title
                }
            }
        }
        return null
    }

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

    /** 应用修改：列出待生效项，并给出「重启手机 / 重启作用域」两个入口 */
    fun applyChanges(activity: Activity, onDone: () -> Unit) {
        val state = M3Dirty.check(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        var dialogRef: androidx.appcompat.app.AlertDialog? = null

        fun openItem(item: M3Dirty.Item) {
            val id = item.catId ?: return
            if (id == "camera") {
                activity.startActivity(
                    android.content.Intent(activity, CameraSettingsActivity::class.java)
                )
            } else {
                activity.startActivity(
                    android.content.Intent(activity, CategoryActivity::class.java)
                        .putExtra("id", id)
                        .putExtra("focus", item.key)
                )
            }
        }

        fun itemRow(item: M3Dirty.Item): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                val tv = android.util.TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    setBackgroundResource(tv.resourceId)
                }
                setOnClickListener {
                    dialogRef?.dismiss()
                    openItem(item)
                }
            }
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(M3Ui.text(activity, item.title, 14f, M3Color.ON_SURFACE))
            texts.addView(M3Ui.text(activity, if (item.reboot) "重启手机后生效" else "重启作用域后生效",
                11f, M3Color.ON_SURFACE_VAR))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(M3Ui.text(activity, "›", 20f, M3Color.ON_SURFACE_VAR))
            return row
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(16), dp(0))
        }

        if (state.items.isEmpty()) {
            box.addView(M3Ui.text(activity, "当前没有未生效的修改", 13f, M3Color.ON_SURFACE_VAR).apply {
                setPadding(0, dp(8), dp(8), dp(8))
            })
        } else {
            val scroll = ScrollView(activity)
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            state.items.forEach { list.addView(itemRow(it)) }
            scroll.addView(list)
            val listHeight = if (state.items.size <= 3) ViewGroup.LayoutParams.WRAP_CONTENT else dp(210)
            box.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight
            ))
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        fun addAction(label: String, filled: Boolean, onClick: () -> Unit) {
            val b = MaterialButton(activity).apply {
                text = label
                textSize = 13f
                cornerRadius = dp(18)
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(14), dp(6), dp(14), dp(6))
                if (filled) {
                    backgroundTintList = ColorStateList.valueOf(M3Color.PRIMARY_CONT)
                    setTextColor(M3Color.ON_PRIMARY_CONT)
                } else {
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    strokeWidth = dp(1)
                    strokeColor = ColorStateList.valueOf(M3Color.OUTLINE_VAR)
                    setTextColor(M3Color.ON_SURFACE)
                }
                setOnClickListener {
                    dialogRef?.dismiss()
                    onClick()
                }
            }
            actions.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
        }

        if (state.dirty) {
            addAction("取消修改", false) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("取消修改")
                    .setMessage("把尚未生效的改动恢复为上次生效时的状态，确定吗？")
                    .setPositiveButton("恢复") { _, _ ->
                        M3Dirty.revertPending(activity)
                        Toast.makeText(activity, "已恢复为上次生效的配置", Toast.LENGTH_SHORT).show()
                        onDone()
                        activity.recreate()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        addAction("重启手机", false) { confirmReboot(activity) }
        addAction("重启作用域", true) { restartScope(activity) { onDone() } }
        box.addView(actions)

        dialogRef = MaterialAlertDialogBuilder(activity)
            .setTitle("应用修改")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()
        dialogRef?.show()
    }
    private fun confirmReboot(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("重启手机")
            .setMessage("确定要重启手机吗？未生效的改动会在开机后生效。")
            .setPositiveButton("重启") { _, _ ->
                Toast.makeText(activity, "正在重启手机…", Toast.LENGTH_SHORT).show()
                Thread {
                    val hasRoot = runCatching {
                        com.bw.wooboxnx.util.ShellUtils.checkRootPermission()
                    }.getOrDefault(false)
                    if (!hasRoot) {
                        activity.runOnUiThread {
                            MaterialAlertDialogBuilder(activity)
                                .setTitle("重启失败")
                                .setMessage("没有获得 root 权限，无法重启手机。")
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                        return@Thread
                    }
                    runCatching {
                        com.bw.wooboxnx.util.ShellUtils.execCommand("reboot", true)
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun restartScope(activity: Activity, onRestarted: (() -> Unit)? = null) {
        val pkgs: List<String> = runCatching {
            activity.resources.getStringArray(R.array.xposed_scope).filter { it != "android" }
        }.getOrDefault(emptyList())
        MaterialAlertDialogBuilder(activity)
            .setTitle("重启作用域")
            .setMessage("将对 ${pkgs.size} 个作用域应用执行 killall，需要 root 权限。")
            .setPositiveButton("确定") { _, _ ->
                Toast.makeText(activity, "正在重启作用域…", Toast.LENGTH_SHORT).show()
                Thread {
                    val hasRoot = runCatching {
                        com.bw.wooboxnx.util.ShellUtils.checkRootPermission()
                    }.getOrDefault(false)
                    if (!hasRoot) {
                        activity.runOnUiThread {
                            MaterialAlertDialogBuilder(activity)
                                .setTitle("重启失败")
                                .setMessage("没有获得 root 权限，无法结束作用域里的应用进程。\n\n请确认已在 root 管理器中给本应用授权。")
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                        return@Thread
                    }
                    val result = runCatching {
                        com.bw.wooboxnx.util.ShellUtils.execCommand(
                            pkgs.map { "killall $it" }, true, true
                        )
                    }.getOrNull()
                    activity.runOnUiThread {
                        if (result == null) {
                            Toast.makeText(activity, "重启失败：命令执行异常", Toast.LENGTH_LONG).show()
                        } else {
                            M3Dirty.markRestarted(activity)
                            Toast.makeText(activity, "已重启作用域", Toast.LENGTH_SHORT).show()
                            onRestarted?.invoke()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 一次性高亮（搜索结果 / 应用修改跳转后用） */
    fun flash(v: View) {
        val a = android.animation.ObjectAnimator.ofFloat(v, "alpha", 1f, 0.3f, 1f, 0.3f, 1f)
        a.duration = 900
        a.start()
    }
    fun pill(
        activity: Activity,
        label: String,
        chipStyle: Boolean = false,
        endMarginDp: Int = 0,
        strokeColor: Int = M3Color.OUTLINE_VAR,
        strokeWidthDp: Int = 1,
        onClick: () -> Unit
    ): TextView {
        val tv = M3Ui.text(activity, label, if (chipStyle) 13f else 12f, M3Color.ON_SURFACE_VAR)
        if (chipStyle) {
            tv.setPadding(M3Ui.dp(activity, 14), M3Ui.dp(activity, 7), M3Ui.dp(activity, 14), M3Ui.dp(activity, 7))
        } else {
            tv.setPadding(M3Ui.dp(activity, 10), M3Ui.dp(activity, 5), M3Ui.dp(activity, 10), M3Ui.dp(activity, 5))
        }
        val d = GradientDrawable()
        d.cornerRadius = M3Ui.dp(activity, if (chipStyle) 16 else 14).toFloat()
        d.setColor(Color.TRANSPARENT)
        d.setStroke(M3Ui.dp(activity, strokeWidthDp), strokeColor)
        tv.background = d
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = M3Ui.dp(activity, endMarginDp) }
        return tv
    }
}

/** 备份 / 恢复（走系统文件选择器） */
class M3Backup(private val activity: AppCompatActivity) {

    /** 运行期状态，不进备份 */
    private val skipKeys = setOf("scope_dirty")

    private fun moonAppsRaw(): String = runCatching {
        java.io.File(activity.filesDir, com.bw.wooboxnx.activity.MoonStorage.FILE_NAME).readText()
    }.getOrDefault("")

    private val backupLauncher = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val obj = org.json.JSONObject()
            obj.put(Meta.MODULE, Meta.MODULE_ID)
            obj.put(Meta.VERSION, Meta.VERSION_CODE)
            M3Prefs.sp(activity).all.forEach { (k, v) ->
                if (!k.startsWith("_") && k !in skipKeys) obj.put(k, v)
            }
            // 屏下名单和样式存在文件里，不在 SharedPreferences 中
            obj.put(Meta.MOON_APPS, moonAppsRaw())
            obj.put(Meta.MOON_STYLE, com.bw.wooboxnx.activity.MoonStyle.get(activity))
            activity.contentResolver.openOutputStream(uri)?.use { it.write(obj.toString().toByteArray()) }
            Toast.makeText(activity, "备份成功", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(activity, "备份失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val restoreLauncher = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@runCatching
            val obj = org.json.JSONObject(text)
            val module = obj.optString(Meta.MODULE, "")
            val ver = obj.optInt(Meta.VERSION, 0)
            when {
                module == Meta.MODULE_ID && ver > Meta.VERSION_CODE -> {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("备份来自更新版本")
                        .setMessage("这个备份的格式版本是 $ver，当前应用只支持到 ${Meta.VERSION_CODE}，部分设置可能无法识别。\n\n仍要恢复吗？")
                        .setPositiveButton("继续恢复") { _, _ -> applyRestore(obj) }
                        .setNegativeButton("取消", null)
                        .show()
                }
                module == Meta.MODULE_ID -> applyRestore(obj)
                module.isEmpty() -> {
                    // 旧版本备份没有来源标记，让用户确认
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("备份没有来源标记")
                        .setMessage("这个文件不是本模块新版本导出的备份，可能来自其他模块或旧版本。\n\n继续恢复会覆盖当前配置，确定吗？")
                        .setPositiveButton("继续恢复") { _, _ -> applyRestore(obj) }
                        .setNegativeButton("取消", null)
                        .show()
                }
                else -> {
                    Toast.makeText(activity, "这不是本模块的备份文件", Toast.LENGTH_LONG).show()
                }
            }
        }.onFailure {
            Toast.makeText(activity, "恢复失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyRestore(obj: org.json.JSONObject) {
        val prefs = M3Prefs.sp(activity)
        // 记录现有类型，恢复时按原类型写入，避免类型不一致导致后续读取崩溃
        val current = prefs.all
        val edit = prefs.edit().clear()
        obj.keys().forEach { k ->
            if (k.startsWith("_") || k in skipKeys) return@forEach
            val v = obj[k]
            val old = current[k]
            runCatching {
                when (old) {
                    is Boolean -> edit.putBoolean(k, when (v) {
                        is Boolean -> v
                        is String -> v.equals("true", true)
                        is Number -> v.toInt() != 0
                        else -> false
                    })
                    is Int -> edit.putInt(k, when (v) {
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull() ?: 0
                        else -> 0
                    })
                    is Long -> edit.putLong(k, when (v) {
                        is Number -> v.toLong()
                        is String -> v.toLongOrNull() ?: 0L
                        else -> 0L
                    })
                    is Float -> edit.putFloat(k, when (v) {
                        is Number -> v.toFloat()
                        is String -> v.toFloatOrNull() ?: 0f
                        else -> 0f
                    })
                    is String -> edit.putString(k, v?.toString() ?: "")
                    else -> when (v) {
                        is Boolean -> edit.putBoolean(k, v)
                        is Int -> edit.putInt(k, v)
                        is Long -> edit.putLong(k, v)
                        is Double -> edit.putFloat(k, v.toFloat())
                        is String -> edit.putString(k, v)
                        else -> edit.putString(k, v.toString())
                    }
                }
            }
        }
        edit.apply()

        // 屏下名单/样式
        if (obj.has(Meta.MOON_APPS)) {
            val apps = obj.optString(Meta.MOON_APPS, "")
            runCatching {
                java.io.File(activity.filesDir, com.bw.wooboxnx.activity.MoonStorage.FILE_NAME).writeText(apps)
            }
            runCatching {
                android.provider.Settings.System.putString(
                    activity.contentResolver, "woobox_moon_apps", apps
                )
            }
        }
        if (obj.has(Meta.MOON_STYLE)) {
            runCatching {
                com.bw.wooboxnx.activity.MoonStyle.set(activity, obj.optInt(Meta.MOON_STYLE, 0))
            }
        }

        Toast.makeText(activity, "已恢复配置，请检查「应用修改」", Toast.LENGTH_LONG).show()
        activity.recreate()
    }

    fun startBackup() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        backupLauncher.launch("Woobox_backup_$stamp.json")
    }

    fun confirmRestore() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("恢复配置")
            .setMessage("选择一个 JSON 备份文件恢复模块设置，现有设置会被覆盖。")
            .setPositiveButton("选择文件") { _, _ -> restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            .setNegativeButton("取消", null)
            .show()
    }

    private object Meta {
        const val MODULE = "_module"
        const val VERSION = "_version"
        const val MOON_APPS = "_moon_apps"
        const val MOON_STYLE = "_moon_style"
        const val MODULE_ID = "woobox-nx"
        const val VERSION_CODE = 1
    }
}
/** 恢复默认设置：prefs 可能被 LSPosed 重定向到 /data/misc，clear 的同时要删模块文件 */
object M3Reset {
    private val FILES = listOf("moon_apps.txt", "moon_style.txt", "prefs_baseline.txt", "baseline_boot.txt")

    fun confirm(a: AppCompatActivity) {
        MaterialAlertDialogBuilder(a)
            .setTitle("恢复默认设置")
            .setMessage("把模块的全部设置恢复为默认值，并清除屏下应用名单和样式。\n\n此操作不可撤销。")
            .setPositiveButton("恢复") { _, _ -> reset(a) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun reset(a: AppCompatActivity) {
        runCatching { M3Prefs.sp(a).edit().clear().commit() }
        FILES.forEach { runCatching { java.io.File(a.filesDir, it).delete() } }
        runCatching {
            android.provider.Settings.System.putString(a.contentResolver, "woobox_moon_apps", "")
        }
        Toast.makeText(a, "已恢复默认设置", Toast.LENGTH_SHORT).show()
        a.recreate()
    }
}
class SettingsHomeActivity : AppCompatActivity() {

    private var scopePill: View? = null
    private var statusChip: TextView? = null

    private var listHost: LinearLayout? = null
    private var searchField: android.widget.EditText? = null
    private var searchClear: TextView? = null
    private var query = ""
    private var pendingSearch: Runnable? = null
    private lateinit var backup: M3Backup
    private lateinit var rowBuilder: M3RowBuilder

    private val aboutCategory = Category("about", "关于", "版本、开发者、致谢", 6, emptyList())

    /**
     * 系统「清除数据」只清 /data/user/0，而 LSPosed 把模块 prefs 重定向到了 /data/misc，
     * 所以判定「files 空了但 prefs 还有值」时主动清一次，让清除数据的行为符合预期。
     */
    private fun resetIfDataCleared() {
        runCatching {
            val hasFiles = filesDir.listFiles()?.isNotEmpty() == true
            val prefs = M3Prefs.sp(this)
            if (!hasFiles && prefs.all.isNotEmpty()) {
                prefs.edit().clear().commit()
            }
        }
    }

    /** 每次回到主页都重新判断模块是否在运行（心跳可能刚更新） */
    private fun refreshStatus() {
        val tv = statusChip ?: return
        val (label, fg, bg) = statusTriple()
        tv.text = label
        tv.setTextColor(fg)
        (tv.background as? GradientDrawable)?.setColor(bg)
    }

    /** 胶囊文案 / 前景 / 底色：全部生效=绿色，部分生效=黄色，全无=红色 */
    private fun statusTriple(): Triple<String, Int, Int> = when (M3Status.level(this)) {
        2 -> Triple("● 运行中", M3Color.ON_PRIMARY_CONT, M3Color.PRIMARY_CONT)
        1 -> Triple("● 部分生效", M3Color.ON_WARN_CONT, M3Color.WARN_CONT)
        else -> Triple("● 未激活", M3Color.ON_ERROR_CONT, M3Color.ERROR_CONT)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        val p = scopePill ?: return
        M3Dirty.check(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        M3Theme.apply(this)
        // 「清除数据」不会删掉 LSPosed 重定向到 /data/misc 的 prefs，这里补一次判定的清空
        resetIfDataCleared()
        backup = M3Backup(this)
        rowBuilder = M3RowBuilder(this, { markDirty() }, { handleAction(it) })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 透明，让顶部的背景箱子透出来；滚动区自带不透明底色，负责"切"掉箱子下半部分
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 顶栏固定不滚动
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@SettingsHomeActivity, 20), M3Ui.dp(this@SettingsHomeActivity, 12),
                M3Ui.dp(this@SettingsHomeActivity, 20), 0)
        }
        val logoBg = ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_box)
            setColorFilter(M3Color.SURFACE_CONT)
            alpha = 1f
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
        }
        root.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))


        val scroll = M3ScrollView(this).apply { setBackgroundColor(M3Color.SURFACE) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@SettingsHomeActivity, 20), M3Ui.dp(this@SettingsHomeActivity, 10),
                M3Ui.dp(this@SettingsHomeActivity, 20), M3Ui.dp(this@SettingsHomeActivity, 24))
        }
        scroll.addView(content)
        val scrollWrap = android.widget.FrameLayout(this)
        scrollWrap.addView(scroll, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val fade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(M3Color.SURFACE, Color.TRANSPARENT)
            )
            alpha = 0f
        }
        scrollWrap.addView(fade, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, M3Ui.dp(this@SettingsHomeActivity, 18),
            Gravity.TOP
        ))
        root.addView(scrollWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            fade.alpha = if (y > 6) 1f else 0f
        }

        // ---------- 头部 ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(M3Ui.text(this, "WooBox NX", 34f, M3Color.ON_SURFACE, true))
        val model = (Build.MANUFACTURER + " " + Build.MODEL).replaceFirstChar { it.uppercase() }
        titleBox.addView(M3Ui.text(this, model + "  ·  v" + com.bw.wooboxnx.BuildConfig.VERSION_NAME,
            13f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 2), 0, 0)
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.setPadding(M3Ui.dp(this, 6), 0, M3Ui.dp(this, 6), 0)
        topBar.addView(header)

        // ---------- 状态 chips ----------
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 12), 0, M3Ui.dp(this@SettingsHomeActivity, 4))
        }
        val scopeCount = runCatching {
            resources.getStringArray(R.array.xposed_scope).count { it != "android" }
        }.getOrDefault(0)
        val (statusText, statusFg, statusBg) = statusTriple()
        statusChip = chip(statusText, statusFg, statusBg)
        statusChip?.isClickable = true
        statusChip?.setOnClickListener { showStatusDialog() }
        chips.addView(statusChip)
        chips.addView(chip("作用域 $scopeCount 个", M3Color.ON_SURFACE_VAR, Color.TRANSPARENT))
        chips.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val homePill = M3Actions.pill(this, "应用修改", true, 0, M3Color.PRIMARY, 2) {
            M3Actions.applyChanges(this) { refreshDirty() }
        }
        scopePill = homePill
        chips.addView(homePill)
        topBar.addView(chips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---------- 搜索 ----------
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(M3Ui.dp(this@SettingsHomeActivity, 14), 0,
                M3Ui.dp(this@SettingsHomeActivity, 6), 0)
            background = GradientDrawable().apply {
                cornerRadius = M3Ui.dp(this@SettingsHomeActivity, 22).toFloat()
                setColor(M3Color.SURFACE_CONT)
            }
        }
        val search = android.widget.EditText(this).apply {
            hint = "搜索设置项"
            setHintTextColor(M3Color.ON_SURFACE_VAR)
            setTextColor(M3Color.ON_SURFACE)
            textSize = 14f
            setSingleLine()
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 9), 0, M3Ui.dp(this@SettingsHomeActivity, 9))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString().orEmpty()
                    searchClear?.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    // 轻微防抖，避免每敲一个字就整表重建导致闪烁
                    pendingSearch?.let { listHost?.removeCallbacks(it) }
                    val job = Runnable { pendingSearch = null; buildList() }
                    pendingSearch = job
                    listHost?.postDelayed(job, 90L)
                }
            })
        }
        searchField = search
        searchWrap.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clear = M3Ui.text(this, "✕", 16f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(M3Ui.dp(this@SettingsHomeActivity, 10), M3Ui.dp(this@SettingsHomeActivity, 8),
                M3Ui.dp(this@SettingsHomeActivity, 10), M3Ui.dp(this@SettingsHomeActivity, 8))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { clearSearch() }
        }
        searchClear = clear
        searchWrap.addView(clear)
        content.addView(searchWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 0 })

        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listHost = host
        content.addView(host, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = M3Ui.dp(this@SettingsHomeActivity, 12) })
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(M3Color.SURFACE) }
        frame.addView(logoBg, android.widget.FrameLayout.LayoutParams(
            M3Ui.dp(this, 168), M3Ui.dp(this, 168), Gravity.TOP or Gravity.END
        ).apply {
            topMargin = M3Ui.dp(this@SettingsHomeActivity, 6)
            rightMargin = M3Ui.dp(this@SettingsHomeActivity, 14)
        })

        // 底部渐隐：很短的一段，让箱子和下面内容衔接
        val boxFadeHeight = M3Ui.dp(this, 26)
        val boxFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, M3Color.SURFACE)
            )
        }
        frame.addView(boxFade, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, boxFadeHeight, Gravity.TOP
        ))
        topBar.post {
            (boxFade.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                topMargin = (topBar.bottom - boxFadeHeight).coerceAtLeast(0)
            }
            boxFade.requestLayout()
        }
        frame.addView(root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(frame)
        buildList()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (query.isNotEmpty()) {
                    clearSearch()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

    }

    private fun buildList() {
        val host = listHost ?: return
        host.removeAllViews()
        runCatching {
            if (query.isNotBlank()) {
                host.addView(searchResultList(query))
            } else {
                host.addView(rowsCard(SettingsModel.categories() + aboutCategory))
            }
        }.onFailure {
            host.addView(M3Ui.text(this, "渲染失败：" + it, 12f, M3Color.ERROR_CONT))
        }
    }

    private fun rowsCard(cats: List<Category>): View {
        val card = M3Ui.card(this).apply {
            setContentPadding(M3Ui.dp(this@SettingsHomeActivity, 8), M3Ui.dp(this@SettingsHomeActivity, 4),
                M3Ui.dp(this@SettingsHomeActivity, 8), M3Ui.dp(this@SettingsHomeActivity, 4))
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cats.forEachIndexed { index, cat ->
            inner.addView(compactRow(cat.icon, cat.title, cat.subtitle) { openCategory(cat) })
            if (index != cats.lastIndex) inner.addView(divider())
        }
        card.addView(inner)
        return card
    }

    private fun compactRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "$title，$subtitle"
            setPadding(M3Ui.dp(this@SettingsHomeActivity, 8), M3Ui.dp(this@SettingsHomeActivity, 14),
                M3Ui.dp(this@SettingsHomeActivity, 6), M3Ui.dp(this@SettingsHomeActivity, 14))
            isClickable = true
            val tv = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                setBackgroundResource(tv.resourceId)
            }
            setOnClickListener { onClick() }
        }
        val size = M3Ui.dp(this, 42)
        row.addView(IconView(this, icon).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = M3Ui.dp(this@SettingsHomeActivity, 14)
            }
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(M3Ui.text(this, title, 17f, M3Color.ON_SURFACE, true))
        texts.addView(M3Ui.text(this, subtitle, 13f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(0, M3Ui.dp(this@SettingsHomeActivity, 1), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(M3Ui.text(this, "›", 24f, M3Color.ON_SURFACE_VAR))
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(M3Color.OUTLINE_VAR)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ).apply { leftMargin = M3Ui.dp(this@SettingsHomeActivity, 64) }
    }

    private fun openCategory(cat: Category) {
        when (cat.id) {
            "camera" -> startActivity(Intent(this, CameraSettingsActivity::class.java))
            "about" -> startActivity(Intent(this, AboutActivity::class.java))
            else -> startActivity(Intent(this, CategoryActivity::class.java).apply {
                putExtra("id", cat.id)
            })
        }
    }

    private data class SearchHit(val cat: Category, val row: Row?, val group: String?)

    private fun searchHits(query: String): List<SearchHit> {
        val key = query.trim()
        val out = ArrayList<SearchHit>()
        if (key.isEmpty()) return out
        SettingsModel.categories().forEach { cat ->
            if (cat.id == "camera") {
                if (cat.title.contains(key, true) || cat.subtitle.contains(key, true)) {
                    out.add(SearchHit(cat, null, null))
                }
                return@forEach
            }
            cat.groups.forEach { group ->
                // 命中分组名时，把该分组下的设置项都列出来
                val groupHit = group.title?.contains(key, true) == true
                group.rows.forEach { row ->
                    if (groupHit ||
                        row.title.contains(key, true) ||
                        (row.summary?.contains(key, true) == true)
                    ) {
                        out.add(SearchHit(cat, row, group.title))
                    }
                }
            }
            // 命中分类名时，也给出一个直达入口
            if (cat.title.contains(key, true)) {
                out.add(SearchHit(cat, null, null))
            }
        }
        return out
    }

    private fun searchResultList(query: String): View {
        val hits = searchHits(query)
        if (hits.isEmpty()) {
            return M3Ui.text(this, "没有匹配的设置项", 14f, M3Color.ON_SURFACE_VAR).apply {
                setPadding(M3Ui.dp(this@SettingsHomeActivity, 8), M3Ui.dp(this@SettingsHomeActivity, 20),
                    M3Ui.dp(this@SettingsHomeActivity, 8), M3Ui.dp(this@SettingsHomeActivity, 20))
            }
        }
        val card = M3Ui.card(this).apply {
            setContentPadding(M3Ui.dp(this@SettingsHomeActivity, 8), M3Ui.dp(this@SettingsHomeActivity, 4),
                M3Ui.dp(this@SettingsHomeActivity, 8), M3Ui.dp(this@SettingsHomeActivity, 4))
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hits.forEachIndexed { index, hit ->
            val title = hit.row?.title ?: hit.cat.title
            val path = hit.cat.title + (hit.group?.let { "  ·  $it" } ?: "")
            inner.addView(compactRow(hit.cat.icon, title, path) {
                if (hit.row == null || hit.cat.id == "camera") {
                    openCategory(hit.cat)
                } else {
                    startActivity(Intent(this, CategoryActivity::class.java)
                        .putExtra("id", hit.cat.id)
                        .putExtra("focus", hit.row.key ?: hit.row.title))
                }
            })
            if (index != hits.lastIndex) inner.addView(divider())
        }
        card.addView(inner)
        return card
    }

    private fun markDirty() {
        M3Dirty.check(this)
    }

    private fun refreshDirty() {
        M3Dirty.check(this)
    }

    private fun clearSearch() {
        query = ""
        searchField?.setText("")
        searchClear?.visibility = View.GONE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        searchField?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        buildList()
    }

    private fun handleAction(action: String) {
        when {
            action.startsWith("page:") -> startActivity(Intent(this, CategoryActivity::class.java)
                .putExtra("id", action.removePrefix("page:")))

            action == "reset_all" -> M3Reset.confirm(this)
            action == "backup" -> backup.startBackup()
            action == "restore" -> backup.confirmRestore()
        }
    }

    private val scopeLabels = listOf(
        "com.android.systemui" to "系统界面",
        "android" to "系统框架",
        "com.fingerprint.sensorservice" to "指纹服务",
        "com.zte.mifavor.launcher" to "桌面",
        "com.zte.mifavor.miboard" to "负一屏",
        "com.android.settings" to "系统设置",
        "com.android.packageinstaller" to "安装器",
        "com.android.permissioncontroller" to "权限控制器",
        "com.android.nfc" to "NFC",
        "com.zte.cn.doubleapp" to "应用双开",
        "cn.nubia.filebrowser" to "文件管理",
        "com.zte.zdm" to "系统更新"
    )

    /** 常驻作用域：开机后一定会启动，所以「有没有心跳」能真实反映是否注入 */
    private val residentScopes = listOf(
        "com.android.systemui" to "系统界面",
        "com.zte.mifavor.launcher" to "桌面",
        "com.fingerprint.sensorservice" to "指纹服务",
        "android" to "系统框架"
    )

    private fun showStatusDialog() {
        val sb = StringBuilder()
        var stale = 0
        var missing = 0
        sb.append("常驻作用域\n")
        residentScopes.forEach { (pkg, label) ->
            val ok = M3Status.scopeAlive(this, pkg)
            val old = M3Status.scopeStale(this, pkg)
            if (old) stale++
            if (!ok) missing++
            sb.append(if (ok) "● " else "○ ").append(label)
            if (old) sb.append("（旧版本）")
            if (pkg == "android" && !ok) sb.append("（重启手机后生效）")
            sb.append('\n')
        }
        val others = scopeLabels.filter { p -> residentScopes.none { it.first == p.first } && M3Status.scopeAlive(this, p.first) }
        if (others.isNotEmpty()) {
            sb.append("\n已上报的其它作用域：").append(others.joinToString("、") { it.second }).append('\n')
        }
        sb.append("\n● 已注入　○ 没收到心跳")
        sb.append("\n其余作用域（安装器、文件管理等）只在对应应用打开时才会上报，不显示不代表没注入。")
        if (stale > 0) {
            sb.append("\n\n有 ").append(stale).append(" 个作用域还在跑安装前的旧代码，重启对应应用或手机后才会用上新版本。")
        }
        if (missing > 0) {
            sb.append("\n\n常驻作用域缺失时，检查 LSPosed 作用域是否勾选，或重启对应进程。")
        }
        val level = M3Status.level(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(
                when (level) {
                    2 -> "模块运行状态"
                    1 -> "模块部分生效"
                    else -> "模块未激活"
                }
            )
            .setMessage(sb.toString().trim())
            .setPositiveButton("知道了", null)
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
        return tv.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = M3Ui.dp(this@SettingsHomeActivity, 8) }
        }
    }

}


// ---------------- 分类详情页 ----------------
class CategoryActivity : AppCompatActivity() {

    private lateinit var backup: M3Backup
    private var scopePill: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backup = M3Backup(this)
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
        scopePill = M3Actions.pill(this, "应用修改", endMarginDp = 8, strokeColor = M3Color.PRIMARY, strokeWidthDp = 2) {
            M3Actions.applyChanges(this) { refreshDirty() }
        }
        bar.addView(scopePill)
        root.addView(bar)

        val scroll = M3ScrollView(this).apply { setBackgroundColor(M3Color.SURFACE) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@CategoryActivity, 16), 0, M3Ui.dp(this@CategoryActivity, 16),
                M3Ui.dp(this@CategoryActivity, 24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 从搜索结果跳过来时，滚动到对应设置项并闪一下
        val focus = intent.getStringExtra("focus")
        if (!focus.isNullOrEmpty()) {
            scroll.post {
                val target = findViewByTag(content, focus) ?: return@post
                val t = IntArray(2)
                val s = IntArray(2)
                target.getLocationInWindow(t)
                scroll.getLocationInWindow(s)
                scroll.smoothScrollTo(0, scroll.scrollY + (t[1] - s[1]) - M3Ui.dp(this, 72))
                M3Actions.flash(target)
            }
        }

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
                val rowView = buildRow(prefs, row)
                rowView.tag = row.key ?: row.title
                inner.addView(rowView)
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

    private val rowBuilder by lazy { M3RowBuilder(this, { markChanged() }, { handleAction(it) }) }

    private fun buildRow(prefs: SharedPreferences, row: Row): View = rowBuilder.build(prefs, row)
    private fun findViewByTag(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findViewByTag(v.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }
    private fun handleAction(action: String) {
        when {
            action.startsWith("page:") -> startActivity(Intent(this, CategoryActivity::class.java)
                .putExtra("id", action.removePrefix("page:")))

            action == "reset_all" -> M3Reset.confirm(this)
            action == "backup" -> backup.startBackup()
            action == "restore" -> backup.confirmRestore()
        }
    }
    private fun markChanged() {
        M3Dirty.check(this)
    }

    private fun refreshDirty() {
        M3Dirty.check(this)
    }

}


// ---------------- 屏下显示区域 ----------------
class CameraSettingsActivity : AppCompatActivity() {

    private var scopePill: View? = null

    private fun markChanged() {
        M3Dirty.check(this)
    }

    private fun refreshDirty() {
        M3Dirty.check(this)
    }

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
        scopePill = M3Actions.pill(this, "应用修改", endMarginDp = 8, strokeColor = M3Color.PRIMARY, strokeWidthDp = 2) {
            M3Actions.applyChanges(this) { refreshDirty() }
        }
        bar.addView(scopePill)
        root.addView(bar)

        val scroll = M3ScrollView(this).apply { setBackgroundColor(M3Color.SURFACE) }
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
            styleProvider = { com.bw.wooboxnx.activity.MoonStyle.get(this@CameraSettingsActivity) }
            batteryProvider = { prefs.getBoolean("camera_battery", false) }
            enabledProvider = { prefs.getBoolean("moon_notch", false) }
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
            check(when (com.bw.wooboxnx.activity.MoonStyle.get(this@CameraSettingsActivity)) {
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
                com.bw.wooboxnx.activity.MoonStyle.set(this@CameraSettingsActivity, style)
                preview.invalidate()
                markChanged()
            }
        }
        content.addView(seg)

        // 开关卡
        val card = M3Ui.card(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val switches = HashMap<String, MaterialSwitch>()
        fun updateChildState() {
            val on = switches["moon_notch"]?.isChecked ?: false
            listOf("moon_allow_light", "camera_battery").forEach { k ->
                switches[k]?.apply {
                    isEnabled = on
                    alpha = if (on) 1f else 0.4f
                }
            }
        }
        listOf(
            Triple("moon_notch", "启用", "在摄像头位置绘制遮罩"),
            Triple("moon_allow_light", "亮色模式也显示", "默认只在深色模式显示"),
            Triple("camera_battery", "显示电量", "替代弯月显示充电状态")
        ).forEachIndexed { i, t ->
            inner.addView(switchRow(
                prefs, t.first, t.second, t.third,
                onChange = {
                    if (t.first == "moon_notch") {
                        updateChildState()
                        preview.invalidate()
                    }
                    if (t.first == "camera_battery") preview.invalidate()
                },
                onCreated = { sw -> switches[t.first] = sw }
            ))
            if (i < 2) inner.addView(View(this).apply {
                setBackgroundColor(M3Color.OUTLINE_VAR)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
        }
        updateChildState()
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
                    com.bw.wooboxnx.activity.MoonAppListActivity::class.java))
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

    private fun switchRow(
        prefs: SharedPreferences,
        key: String,
        title: String,
        summary: String,
        onChange: () -> Unit = {},
        onCreated: (MaterialSwitch) -> Unit = {}
    ): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@CameraSettingsActivity, 10), 0, M3Ui.dp(this@CameraSettingsActivity, 10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(M3Ui.text(this, title, 16f, M3Color.ON_SURFACE))
        texts.addView(M3Ui.text(this, summary, 12f, M3Color.ON_SURFACE_VAR))
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, c ->
                prefs.edit().putBoolean(key, c).apply()
                markChanged()
                onChange()
            }
        }
        line.addView(sw)
        onCreated(sw)
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
        root.addView(bar)

        val scroll = M3ScrollView(this).apply { setBackgroundColor(M3Color.SURFACE) }
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
            isClickable = true
            contentDescription = "检查更新，当前版本 " + com.bw.wooboxnx.BuildConfig.VERSION_NAME
            setOnClickListener { checkUpdate() }
        }
        appRow.addView(circleImage(R.drawable.app_icon, 56))
        val appTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@AboutActivity, 16), 0, 0, 0)
        }
        appTexts.addView(M3Ui.text(this, getString(R.string.app_name), 18f, M3Color.ON_SURFACE, true))
        appTexts.addView(M3Ui.text(this,
            "v" + com.bw.wooboxnx.BuildConfig.VERSION_NAME + "（" + com.bw.wooboxnx.BuildConfig.VERSION_CODE + "）",
            13f, M3Color.ON_SURFACE_VAR))
        appRow.addView(appTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appRow.addView(M3Ui.text(this, "检查更新", 13f, M3Color.ON_SURFACE_VAR))
        appRow.addView(M3Ui.text(this, "›", 24f, M3Color.ON_SURFACE_VAR).apply {
            includeFontPadding = false
            setPadding(M3Ui.dp(this@AboutActivity, 6), 0, 0, 0)
            // 「›」字形在字体框里偏上，向下微调让它的视觉中心和左侧文字对齐
            translationY = -2.4f * resources.displayMetrics.density
        })
        appCard.addView(appRow)

        content.addView(appCard)

        content.addView(M3Ui.text(this, "本分支（WooBox NX）为社区二次开发版本，功能与上游同源。", 12f, M3Color.ON_SURFACE_VAR).apply {
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
        devInner.addView(gifPersonRow(R.drawable.blizzard, "暴白bw", "Blizzardwhite",
            "coolmarket://u/1636422", "https://www.coolapk.com/u/1636422", "Owo"))
        devCard.addView(devInner)
        content.addView(devCard)

        content.addView(M3Ui.text(this, "致谢", 13f, M3Color.ON_SURFACE_VAR, true).apply {
            setPadding(M3Ui.dp(this@AboutActivity, 4), M3Ui.dp(this@AboutActivity, 20),
                M3Ui.dp(this@AboutActivity, 4), M3Ui.dp(this@AboutActivity, 8))
        })

        val upstreamCard = M3Ui.card(this).apply {
            setContentPadding(M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16),
                M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16))
            isClickable = true
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/u9521/WooBoxForRedmagicOS")))
                }
            }
        }
        val upstreamRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        upstreamRow.addView(circleImage(R.drawable.app_icon, 44))
        val upstreamTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(M3Ui.dp(this@AboutActivity, 14), 0, 0, 0)
        }
        upstreamTexts.addView(M3Ui.text(this, "WooBox For RedMagicOS", 16f, M3Color.ON_SURFACE, true))
        upstreamTexts.addView(M3Ui.text(this, "原项目仓库 · GitHub", 12f, M3Color.ON_SURFACE_VAR))
        upstreamRow.addView(upstreamTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        upstreamRow.addView(M3Ui.text(this, "›", 24f, M3Color.ON_SURFACE_VAR))
        upstreamCard.addView(upstreamRow)
        content.addView(upstreamCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        content.addView(M3Ui.text(this, "本项目是基于 WooBox For RedMagicOS 开发的非官方独立演进版",
            12f, M3Color.ON_SURFACE_VAR).apply {
            setPadding(M3Ui.dp(this@AboutActivity, 6), M3Ui.dp(this@AboutActivity, 10),
                M3Ui.dp(this@AboutActivity, 6), 0)
        })

        val linkCard = M3Ui.card(this).apply {
            setContentPadding(M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16),
                M3Ui.dp(this@AboutActivity, 16), M3Ui.dp(this@AboutActivity, 16))
            isClickable = true
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "https://github.com/Blizzardwhite/WooBoxNX/graphs/contributors")))
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
        content.addView(linkCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = M3Ui.dp(this@AboutActivity, 12) })
    }

    private val repoLatestApi = "https://api.github.com/repos/Blizzardwhite/WooBoxNX/releases/latest"
    private val releasePage = "https://github.com/Blizzardwhite/WooBoxNX/releases"
    private val coolapkPage = "https://www.coolapk.com/u/1636422"

    /** 点击版本卡片：向 GitHub 查最新 Release，再和当前版本比较 */
    private fun checkUpdate() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread({
            val info = runCatching { fetchLatestRelease() }.getOrNull()
            runOnUiThread {
                if (info == null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("检查更新失败")
                        .setMessage("无法连接 GitHub，请检查网络后重试。")
                        .setPositiveButton("打开发布页") { _, _ -> openUrl(releasePage) }
                        .setNegativeButton("关闭", null)
                        .show()
                    return@runOnUiThread
                }
                val (tag, url) = info
                val current = com.bw.wooboxnx.BuildConfig.VERSION_NAME
                val newer = isNewerVersion(tag, current)
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (newer) "发现新版本 $tag" else "已是最新版本")
                    .setMessage(
                        "当前版本：v" + current + "（" + com.bw.wooboxnx.BuildConfig.VERSION_CODE + "）" +
                            if (newer) "\n最新版本：$tag" else ""
                    )
                    .setPositiveButton(if (newer) "打开发布页" else "知道了") { _, _ ->
                        if (newer) openUrl(url)
                    }
                    .setNeutralButton("酷安主页") { _, _ -> openCoolapk() }
                    .show()
            }
        }, "woobox-update").apply { isDaemon = true }.start()
    }

    private fun fetchLatestRelease(): Pair<String, String> {
        val conn = java.net.URL(repoLatestApi).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "WooBoxNX")
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
        val obj = org.json.JSONObject(text)
        return obj.getString("tag_name") to obj.optString("html_url", releasePage)
    }

    /** 只比较数字段，1.10 > 1.9 */
    private fun isNewerVersion(latestTag: String, current: String): Boolean {
        fun parts(s: String) = s.removePrefix("v").split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val a = parts(latestTag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    private fun openCoolapk() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("coolmarket://u/1636422")))
        } catch (e: Exception) {
            openUrl(coolapkPage)
        }
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

    private fun gifPersonRow(resId: Int, name: String, handle: String, primary: String, fallback: String, toast: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, M3Ui.dp(this@AboutActivity, 12), 0, M3Ui.dp(this@AboutActivity, 12))
            isClickable = true
            setOnClickListener {
                Toast.makeText(this@AboutActivity, toast, Toast.LENGTH_SHORT).show()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(primary)))
                } catch (e: Exception) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fallback)))
                    }
                }
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

/** 设置行构建器：分类页和首页展开共用 */
class M3RowBuilder(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
    private val onAction: (String) -> Unit
) {

    /** 在滑块轨道上标出默认值的位置 */
    private fun withDefaultMarker(slider: Slider, def: Int, min: Int, max: Int): View {
        val frame = android.widget.FrameLayout(activity)
        frame.addView(slider, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val dotSize = M3Ui.dp(activity, 4)
        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(M3Color.ON_SURFACE_VAR)
                alpha = 130
            }
        }
        val lp = android.widget.FrameLayout.LayoutParams(dotSize, dotSize)
        lp.gravity = Gravity.TOP or Gravity.START
        frame.addView(dot, lp)
        slider.post {
            val frac = if (max > min) (def - min).toFloat() / (max - min) else 0f
            val inset = M3Ui.dp(activity, 14).toFloat()
            val usable = (slider.width - inset * 2).coerceAtLeast(1f)
            dot.x = inset + usable * frac - dotSize / 2f
            dot.y = slider.height / 2f - dotSize / 2f
        }
        return frame
    }
    fun build(prefs: SharedPreferences, row: Row): View {
        return when (row.kind) {
            Row.SLIDER -> {
                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, M3Ui.dp(activity, 12), 0, M3Ui.dp(activity, 8))
                }
                val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                head.addView(M3Ui.text(activity, row.title, 16f, M3Color.ON_SURFACE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val valueText = M3Ui.text(activity, "", 14f, M3Color.PRIMARY).apply {
                    setPadding(M3Ui.dp(activity, 8), 0, 0, 0)
                }
                head.addView(valueText)
                box.addView(head)
                if (!row.summary.isNullOrEmpty()) {
                    box.addView(M3Ui.text(activity, row.summary, 12f, M3Color.ON_SURFACE_VAR))
                }
                box.addView(M3Ui.text(activity, "默认 ${row.def}", 11f, M3Color.ON_SURFACE_VAR).apply {
                    setPadding(0, M3Ui.dp(activity, 2), 0, 0)
                })
                val slider = Slider(activity).apply {
                    valueFrom = row.min.toFloat()
                    valueTo = row.max.toFloat()
                    stepSize = 1f
                    setTickVisible(false)
                    contentDescription = row.title
                    value = prefs.getInt(row.key, row.def).toFloat().coerceIn(row.min.toFloat(), row.max.toFloat())
                    valueText.text = value.toInt().toString()
                    addOnChangeListener { _, v, _ ->
                        valueText.text = v.toInt().toString()
                    }
                    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(slider: Slider) {}
                        override fun onStopTrackingTouch(slider: Slider) {
                            prefs.edit().putInt(row.key, slider.value.toInt()).apply()
                            onChanged()
                        }
                    })
                }
                box.addView(withDefaultMarker(slider, row.def, row.min, row.max))
                box
            }

            Row.SWITCH_SLIDER -> {
                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, M3Ui.dp(activity, 12), 0, M3Ui.dp(activity, 8))
                }
                val head = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(M3Ui.text(activity, row.title, 16f, M3Color.ON_SURFACE))
                if (!row.summary.isNullOrEmpty()) {
                    texts.addView(M3Ui.text(activity, row.summary, 12f, M3Color.ON_SURFACE_VAR))
                }
                head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val valueText = M3Ui.text(activity, "", 14f, M3Color.PRIMARY).apply {
                    setPadding(M3Ui.dp(activity, 8), 0, M3Ui.dp(activity, 4), 0)
                }
                head.addView(valueText)
                head.addView(MaterialSwitch(activity).apply {
                    contentDescription = row.title
                    val key = row.switchKey
                    if (key != null) {
                        isChecked = prefs.getBoolean(key, false)
                        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply(); onChanged() }
                    }
                })
                box.addView(head)
                box.addView(M3Ui.text(activity, "默认 ${row.def}", 11f, M3Color.ON_SURFACE_VAR).apply {
                    setPadding(0, M3Ui.dp(activity, 2), 0, 0)
                })
                val slider = Slider(activity).apply {
                    valueFrom = row.min.toFloat()
                    valueTo = row.max.toFloat()
                    stepSize = 1f
                    setTickVisible(false)
                    contentDescription = row.title
                    value = prefs.getInt(row.key, row.def).toFloat().coerceIn(row.min.toFloat(), row.max.toFloat())
                    valueText.text = value.toInt().toString()
                    addOnChangeListener { _, v, _ ->
                        valueText.text = v.toInt().toString()
                    }
                    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(slider: Slider) {}
                        override fun onStopTrackingTouch(slider: Slider) {
                            prefs.edit().putInt(row.key, slider.value.toInt()).apply()
                            onChanged()
                        }
                    })
                }
                box.addView(withDefaultMarker(slider, row.def, row.min, row.max))
                box
            }

            Row.SWITCH_TEXT -> {
                val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(M3Ui.text(activity, row.title, 16f, M3Color.ON_SURFACE))
                if (!row.summary.isNullOrEmpty()) {
                    texts.addView(M3Ui.text(activity, row.summary, 12f, M3Color.ON_SURFACE_VAR))
                }
                val valueView = M3Ui.text(activity, "当前：" + currentValue(prefs, row), 12f, M3Color.PRIMARY)
                texts.addView(valueView)
                val line = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, M3Ui.dp(activity, 10), 0, M3Ui.dp(activity, 10))
                    isClickable = true
                    setOnClickListener {
                        editValue(row) { saved -> valueView.text = "当前：$saved" }
                    }
                }
                line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (row.switchKey != null) {
                    line.addView(MaterialSwitch(activity).apply {
                        contentDescription = row.title
                        val key = row.switchKey
                        isChecked = prefs.getBoolean(key, false)
                        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply(); onChanged() }
                    })
                } else {
                    line.addView(M3Ui.text(activity, "›", 24f, M3Color.ON_SURFACE_VAR))
                }
                line
            }

            Row.ACTION -> {
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    contentDescription = row.title
                    setPadding(0, M3Ui.dp(activity, 16), 0, M3Ui.dp(activity, 16))
                    isClickable = true
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(M3Ui.text(activity, row.title, 16f, M3Color.ON_SURFACE))
                        if (row.summary != null) {
                            addView(M3Ui.text(activity, row.summary!!, 12f, M3Color.ON_SURFACE_VAR))
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(M3Ui.text(activity, "›", 24f, M3Color.ON_SURFACE_VAR))
                    setOnClickListener {
                        onAction(row.action ?: return@setOnClickListener)
                    }
                }

            }

            else -> {
                val line = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, M3Ui.dp(activity, 10), 0, M3Ui.dp(activity, 10))
                }
                val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(M3Ui.text(activity, row.title, 16f, M3Color.ON_SURFACE))
                if (!row.summary.isNullOrEmpty()) {
                    texts.addView(M3Ui.text(activity, row.summary, 12f, M3Color.ON_SURFACE_VAR).apply {
                        setPadding(0, M3Ui.dp(activity, 2), 0, 0)
                    })
                }
                line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val sw = MaterialSwitch(activity).apply {
                    contentDescription = row.title
                    isChecked = prefs.getBoolean(row.key, false)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(row.key, checked).apply(); onChanged()
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
        val prefs = M3Prefs.sp(activity)
        val input = android.widget.EditText(activity).apply {
            setText(currentValue(prefs, row))
            setSelection(text.length)
            setSingleLine(true)
            setTextColor(M3Color.ON_SURFACE)
            setHintTextColor(M3Color.ON_SURFACE_VAR)
        }
        val wrap = LinearLayout(activity).apply {
            setPadding(M3Ui.dp(activity, 24), M3Ui.dp(activity, 8),
                M3Ui.dp(activity, 24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(row.title)
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                if (row.intValue) {
                    val n = v.toIntOrNull()
                    if (n == null) {
                        Toast.makeText(activity, "请输入整数", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    prefs.edit().putInt(row.key, n).apply(); onChanged()
                } else {
                    prefs.edit().putString(row.key, v).apply(); onChanged()
                }
                onSaved(v)
                Toast.makeText(activity, "设置成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

}
