package com.bw.wooboxnx.hook.app.mtpFileBrowser

import android.annotation.SuppressLint
import android.os.storage.StorageVolume
import com.bw.wooboxnx.util.XSPUtils
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object HideMTPCategoryBrowse : HookRegister() {
    @SuppressLint("PrivateApi")
    override fun init() {
        val addEmulated =
            getDefaultCL().loadClass("cn.nubia.filebrowser.mtpserver.mtp.MtpDatabase")
                .getDeclaredMethod("addEmulatedStorage", StorageVolume::class.java)
        val addEmulatedHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                if (!XSPUtils.getBoolean("hide_mtp_category_browse", false)) {
                    return
                }
//                val volume = param!!.args[0] as StorageVolume
//                Log.e("root object is:${Gson().toJson(volume)}")
                param!!.result = null
            }
        }
        XposedBridge.hookMethod(addEmulated, addEmulatedHooker)
    }
}