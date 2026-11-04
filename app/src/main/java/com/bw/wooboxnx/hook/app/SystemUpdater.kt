package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.systemUpdater.BlockUpdate
import com.bw.wooboxnx.hook.app.systemUpdater.LogUpdateInfo
import com.bw.wooboxnx.hook.app.systemUpdater.MockDeviceInfo
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SystemUpdater : AppRegister() {
    override val packageName: List<String> = listOf("com.zte.zdm")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-SystemUpdater"
    override val loadDexkit: Boolean = true
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        autoInitHooks(
            lpparam,
            MockDeviceInfo, // 伪装设备信息
            BlockUpdate,//阻止系统更新
            LogUpdateInfo,//打印更新信息
        )
    }
}