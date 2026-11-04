package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.permissioncontroller.AllowThirdpartyLauncher
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PermissionController : AppRegister() {
    override val packageName: List<String> = listOf("com.android.permissioncontroller")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-PermissionController"
    override val loadDexkit: Boolean = true
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        autoInitHooks(
            lpparam,
            AllowThirdpartyLauncher, //允许设置第三方桌面
        )
    }
}