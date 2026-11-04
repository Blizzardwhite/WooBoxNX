package com.bw.wooboxnx.fragment

import android.widget.Toast
import cn.fkj233.ui.activity.data.InitView
import cn.fkj233.ui.activity.view.SwitchV
import cn.fkj233.ui.activity.view.TextSummaryV
import com.bw.wooboxnx.R
import com.bw.wooboxnx.compose.InputDialogBuilder
import com.bw.wooboxnx.compose.InputDialogConfig
import com.bw.wooboxnx.compose.LsposedInactiveTip
import com.bw.wooboxnx.compose.PrefsTypeAdapter
import com.bw.wooboxnx.compose.SubItemConfig
import com.bw.wooboxnx.compose.SwitchGroupBuilder
import com.bw.wooboxnx.compose.SwitchGroupConfig

object ScopeSystemSettings : MyFragment() {
    override val regKey: String
        get() = "scope_systemSettings"
    override val iData: InitView.ItemData.() -> Unit
        get() = {
            LsposedInactiveTip(this, mactivity!!).setViews()
            TitleText(textId = R.string.scope_systemSettings)
            TextSummaryWithSwitch(
                TextSummaryV(textId = R.string.usb_install_switch_skip_verify),
                SwitchV("usb_install_switch_skip_verify")
            )
            TextSummaryWithSwitch(
                TextSummaryV(textId = R.string.setting_force_show_va_switch),
                SwitchV("setting_force_show_va_switch")
            )
            TextSummaryWithSwitch(
                TextSummaryV(
                    textId = R.string.setting_force_show_ga_shortcut_switch,
                    tipsId = R.string.setting_force_show_ga_shortcut_switch_tips
                ), SwitchV("setting_force_show_ga_shortcut_switch")
            )
            Line()
            TitleText(textId = R.string.Fun_options)
            SwitchGroupBuilder(
                this,
                mactivity!!,
                SwitchGroupConfig(
                    "setting_fun_override_maxyear_sw",
                    R.string.setting_fun_override_maxyear_sw,
                    listOf(SubItemConfig.Arrow(R.string.setting_fun_override_maxyear) {
                        InputDialogBuilder(
                            this, mactivity!!, InputDialogConfig(
                                R.string.setting_fun_override_maxyear,
                                R.string.setting_fun_override_maxyear_tips,
                                "fun_override_max_year",
                                2099,
                                PrefsTypeAdapter.INT,
                            )
                        ).show {
                            Toast.makeText(mactivity, "设置成功", Toast.LENGTH_SHORT).show()
                            it.dismiss()
                        }
                    })
                )
            ).build()
        }
}