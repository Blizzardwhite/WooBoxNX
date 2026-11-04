package com.bw.wooboxnx.fragment

import cn.fkj233.ui.activity.data.InitView.ItemData
import com.bw.wooboxnx.activity.SettingsActivity

abstract class MyFragment {
    abstract val regKey: String
    protected abstract val iData: ItemData.() -> Unit
    protected var mactivity: SettingsActivity? = null
    fun registerView(activity: SettingsActivity, title: String, hideMenu: Boolean) {
        mactivity = activity
        activity.mInitvew!!.register(regKey, title, hideMenu, iData)
    }
}