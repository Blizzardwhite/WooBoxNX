package com.bw.wooboxnx.hook.app.launcher

import android.os.UserHandle
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object RMzvoiceUninstalDialog : HookRegister() {
    private const val ZviocePackageName = "com.zte.halo.app"
    override fun init() = hasEnable("launcher_rm_zvoice_uninstall_dialog") {
        val pkgEnableMe =
            getDefaultCL().loadClass("android.content.pm.LauncherApps").getDeclaredMethod(
                "isPackageEnabled", String::class.java, UserHandle::class.java
            )
        val zvInstallHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                if (param!!.args[0].equals(ZviocePackageName)) {
                    param.result = true
                    Log.i("mocked: $ZviocePackageName is Enabled")
                }
            }
        }
        XposedBridge.hookMethod(pkgEnableMe, zvInstallHooker)
    }


}