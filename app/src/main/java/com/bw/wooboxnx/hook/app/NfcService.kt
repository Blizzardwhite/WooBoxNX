package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.nfcService.MuteNfcSound
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

object NfcService : AppRegister() {
    override val packageName: List<String> = listOf("com.android.nfc")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-NfcService"
    override val loadDexkit: Boolean = false
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        autoInitHooks(
            lpparam,
            MuteNfcSound, //移除NFC提示音
        )
    }
}