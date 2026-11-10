package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.wakeup.VoiceWakeupDefaultAssist
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 语音唤醒服务（com.zte.voiceassist.wakeup，高通 SVA 唤醒词识别）。
 *
 * 这里只放"唤醒后干什么"相关的 hook，识别链路本身不动。
 */
object Wakeup : AppRegister() {
    override val packageName: List<String> = listOf("com.zte.voiceassist.wakeup")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-Wakeup"
    override val loadDexkit: Boolean = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        autoInitHooks(
            lpparam,
            VoiceWakeupDefaultAssist,//语音唤醒打开默认数字助理
        )
    }
}
