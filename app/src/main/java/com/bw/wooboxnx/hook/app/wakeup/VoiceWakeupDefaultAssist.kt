package com.bw.wooboxnx.hook.app.wakeup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 语音唤醒改为打开系统默认数字助理。
 *
 * 原逻辑：WakeupService 识别到唤醒词后调用
 * HaloUtils.startVoiceAssistant(Context)，往 com.zte.halo.app 发一条
 * OLA_VOICE_KEY 广播，由百度那套「智慧语音」接管。
 *
 * 这里把这次广播换成拉起默认数字助理（等同长按 Home / Action Assist），
 * 屏幕没亮时先点亮再拉，避免助理被息屏吞掉。拉起失败（例如没设默认助理）
 * 就保持原样，继续走系统智慧语音，不至于唤醒后什么都不发生。
 */
object VoiceWakeupDefaultAssist : HookRegister() {

    private const val SCREEN_OFF_DELAY = 500L

    /** 同一次唤醒可能连着回调，避免重复拉起 */
    @Volatile
    private var lastLaunchAt = 0L

    override fun init() = hasEnable("voice_wakeup_default_assist") {
        runCatching {
            val clazz = getDefaultCL()
                .loadClass("com.zte.voiceassist.wakeup.utils.HaloUtils")
            val method = clazz.getDeclaredMethod(
                "startVoiceAssistant", Context::class.java
            )
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return
                    if (!intercept(ctx)) return
                    // 拦下广播，不再交给 com.zte.halo.app
                    param.result = null
                }
            })
            Log.i("VoiceWakeupDefaultAssist hooked")
        }.onFailure {
            Log.px("E", "VoiceWakeupDefaultAssist init failed: $it", it, logInRelease = true)
        }
    }

    /**
     * @return true 表示这次唤醒已经由我们处理，false 表示交回系统原逻辑
     */
    private fun intercept(ctx: Context): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunchAt < 800L) {
            Log.i("wakeup ignored (duplicate)")
            return true
        }

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            // 息屏唤醒：先点亮屏幕，等窗口层级准备好再拉起
            wakeScreen(pm)
            lastLaunchAt = now
            Handler(Looper.getMainLooper()).postDelayed({
                if (!launchAssist(ctx)) {
                    Log.px("W", "launch assist failed after wakeup", logInRelease = true)
                }
            }, SCREEN_OFF_DELAY)
            return true
        }

        if (launchAssist(ctx)) {
            lastLaunchAt = now
            return true
        }
        Log.px("W", "no default assistant, fallback to halo", logInRelease = true)
        return false
    }

    private fun wakeScreen(pm: PowerManager) {
        // PowerManager.wakeUp 是 @SystemApi，公开 SDK 里没有，反射调用
        runCatching {
            PowerManager::class.java
                .getMethod("wakeUp", Long::class.javaPrimitiveType, String::class.java)
                .invoke(pm, SystemClock.uptimeMillis(), "woobox:voice_wakeup")
        }.recoverCatching {
            PowerManager::class.java
                .getMethod("wakeUp", Long::class.javaPrimitiveType)
                .invoke(pm, SystemClock.uptimeMillis())
        }.onSuccess {
            Log.i("screen woken up by voice wakeup")
        }.onFailure {
            Log.px("E", "wakeUp failed: $it", it, logInRelease = true)
        }
    }

    /** 拉起系统默认数字助理的语音命令界面（等同耳机按键喊助理） */
    private fun launchAssist(ctx: Context): Boolean {
        val pkg = assistantPackage(ctx)
        // 优先 VOICE_COMMAND：助理会直接进语音监听界面，而不是停在主页面
        if (pkg != null) {
            val voice = runCatching {
                Intent(Intent.ACTION_VOICE_COMMAND).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }.getOrNull()
            if (voice != null && startActivity(ctx, voice)) {
                Log.i("launch assistant voice command: $pkg")
                return true
            }
        }
        // 兜底：普通助理入口
        return startActivity(
            ctx, Intent(Intent.ACTION_ASSIST).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ).also { if (it) Log.i("launch assistant by ACTION_ASSIST") }
    }

    private fun startActivity(ctx: Context, intent: Intent): Boolean = runCatching {
        ctx.startActivity(intent)
        true
    }.getOrElse {
        Log.px("W", "startActivity failed: ${intent.action} $it", it, logInRelease = true)
        false
    }

    /** 当前默认数字助理的包名 */
    private fun assistantPackage(ctx: Context): String? {
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "assistant")
        }.getOrNull()
        val pkg = runCatching {
            if (raw.isNullOrBlank()) null
            else ComponentName.unflattenFromString(raw)?.packageName
        }.getOrNull()
        return pkg ?: runCatching {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_ASSIST), 0)
                ?.activityInfo?.packageName
        }.getOrNull()
    }
}
