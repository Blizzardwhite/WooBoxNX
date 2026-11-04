package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.packageinstaller.HidePurifySwitch
import com.bw.wooboxnx.hook.app.packageinstaller.HideStoreHint
import com.bw.wooboxnx.hook.app.packageinstaller.SkipApkScan
import com.bw.wooboxnx.hook.app.packageinstaller.UseCtsActivity
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PackageInstaller : AppRegister() {
    override val packageName: List<String> = listOf("com.android.packageinstaller")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-PackageInstaller"
    override val loadDexkit: Boolean = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        autoInitHooks(
            lpparam,
            SkipApkScan,//跳过Apk扫描
            HidePurifySwitch,//隐藏净化开关和提示
            HideStoreHint,//隐藏商店安装按钮
            UseCtsActivity//使用cts测试时的安装界面
        )
    }
}