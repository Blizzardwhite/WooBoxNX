package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.systemui.features.FingerprintRing
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 屏下指纹的图标其实画在独立的系统应用 com.fingerprint.sensorservice 里
 * （窗口 SingleMode_Fingerprint_SensorView），不在 SystemUI。
 * 想改指纹图标，需要在 LSPosed 作用域里勾上这个应用。
 */
object FingerprintSensorUI : AppRegister() {
    override val packageName: List<String> = listOf("com.fingerprint.sensorservice")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-FingerprintUI"
    override val loadDexkit: Boolean = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        android.util.Log.i("WooBox-FPUI", "module loaded in " + lpparam.processName)
        autoInitHooks(lpparam, FingerprintRing)
        FingerprintRing.installImageViewHook()
    }
}
