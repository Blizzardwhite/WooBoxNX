package com.bw.wooboxnx.hook.app.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 让助理页面可以在锁屏上显示（配合「语音唤醒打开默认数字助理」）。
 *
 * 小爱这类第三方助理只声明了 ACTION_ASSIST，没有 showWhenLocked，
 * 息屏/锁屏时拉起会被系统压在锁屏后面，看起来像"没反应"。
 *
 * 这里在 ActivityRecord.Builder.build() 之前，给"默认助理的 ACTION_ASSIST 页面"
 * 补上两个 ActivityInfo 标志位（等价于在清单里写 showWhenLocked="true"
 * turnScreenOn="true"）：
 * - showWhenLocked：页面显示在锁屏之上（锁屏被遮挡但不会真正解锁）
 * - turnScreenOn：息屏时直接把屏幕点亮
 *
 * 只对系统默认助理的包生效，避免其它应用借 ACTION_ASSIST 绕过锁屏。
 */
object AssistShowOnLockScreen : HookRegister() {

    private const val FLAG_SHOW_WHEN_LOCKED = 0x00800000
    private const val FLAG_TURN_SCREEN_ON = 0x01000000

    @Volatile
    private var cachedAssistant: String? = null

    override fun init() = hasEnable("voice_wakeup_default_assist") {
        runCatching {
            val builderCls = getDefaultCL()
                .loadClass("com.android.server.wm.ActivityRecord\$Builder")
            val build = builderCls.getDeclaredMethod("build")
            val infoField = builderCls.getDeclaredField("mActivityInfo")
            val intentField = builderCls.getDeclaredField("mIntent")
            val atmField = builderCls.getDeclaredField("mAtmService")

            XposedBridge.hookMethod(build, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val builder = param.thisObject
                        val intent = intentField.get(builder) as? Intent ?: return
                        val action = intent.action
                        if (action != Intent.ACTION_ASSIST &&
                            action != Intent.ACTION_VOICE_COMMAND
                        ) return
                        val info = infoField.get(builder) as? ActivityInfo ?: return
                        val ctx = systemContext(atmField.get(builder)) ?: return
                        val pkg = assistantPackage(ctx) ?: return
                        if (info.packageName != pkg) return

                        // ActivityInfo 可能被 PackageManager 缓存复用，改副本，避免污染全局
                        val patched = ActivityInfo(info)
                        patched.flags = patched.flags or
                            FLAG_SHOW_WHEN_LOCKED or FLAG_TURN_SCREEN_ON
                        infoField.set(builder, patched)
                        Log.i("assist on lock screen: ${info.packageName}/${info.name}")
                    }.onFailure {
                        Log.px("E", "assist lock screen patch failed: $it", it, logInRelease = true)
                    }
                }
            })
            Log.i("AssistShowOnLockScreen hooked")
        }.onFailure {
            Log.px("E", "AssistShowOnLockScreen init failed: $it", it, logInRelease = true)
        }
    }

    /** system_server 里的 Context，优先从 ActivityTaskManagerService 拿 */
    private fun systemContext(atm: Any?): Context? {
        runCatching {
            val ctx = atm?.javaClass?.getField("mContext")?.get(atm) as? Context
            if (ctx != null) return ctx
        }
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentSystemContext")
                .invoke(null) as? Context
        }.getOrNull()
    }

    /** 当前默认数字助理的包名，取不到就不处理 */
    private fun assistantPackage(ctx: Context): String? {
        cachedAssistant?.let { return it }

        val fromSetting = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "assistant")
        }.getOrNull()
        val pkg = runCatching {
            if (fromSetting.isNullOrBlank()) null
            else ComponentName.unflattenFromString(fromSetting)?.packageName
        }.getOrNull() ?: runCatching {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_ASSIST), 0)
                ?.activityInfo?.packageName
        }.getOrNull()

        if (pkg != null) cachedAssistant = pkg
        return pkg
    }
}
