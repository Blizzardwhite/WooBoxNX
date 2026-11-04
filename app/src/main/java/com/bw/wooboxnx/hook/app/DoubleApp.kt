package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.doubleApp.DoubleAnyApp
import com.bw.wooboxnx.hook.app.doubleApp.RmLowMemoryLimit
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

object DoubleApp : AppRegister() {
    override val packageName: List<String> = listOf("com.zte.cn.doubleapp")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-DoubleApp"
    override val loadDexkit: Boolean = false
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        autoInitHooks(
            lpparam,
            DoubleAnyApp, //双开任意应用
            RmLowMemoryLimit, //去除低内存设备两个双开应用限制
        )
    }
}