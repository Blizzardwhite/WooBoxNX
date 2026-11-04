package com.u9521.wooboxforredmagicos.util.xposed

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 让每个作用域进程给模块写一个心跳（开机时的时间戳），
 * 设置界面据此判断"这个作用域到底有没有被注入"。
 */
object HookBeat {
    private const val URI_HEARTBEAT = "content://com.u9521.wooboxforredmagicos.mod.moon/heartbeat"
    private val lastBeat = HashMap<String, Long>()

    @Suppress("PrivateApi")
    private fun context(): Context? {
        runCatching {
            Class.forName("android.app.AndroidAppHelper")
                .getMethod("currentApplication")
                .invoke(null)?.let { return it as Context }
        }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
            val field = at.getDeclaredField("mInitialApplication")
            field.isAccessible = true
            (field.get(current) as? Context)?.let { return it }
        }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
            at.getDeclaredMethod("getSystemContext").invoke(current)?.let { return it as Context }
        }
        return null
    }

    private fun beatNow(pkg: String): Boolean {
        val ctx = context() ?: return false
        val now = SystemClock.elapsedRealtime()
        lastBeat[pkg] = now
        return runCatching {
            ctx.contentResolver.insert(
                Uri.parse(URI_HEARTBEAT),
                ContentValues().apply {
                    put("pkg", pkg)
                    put("v", now.toString() + "|" + System.currentTimeMillis())
                }
            )
            true
        }.getOrDefault(false)
    }

    fun report(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastBeat[pkg] ?: 0L
        if (now - last < 20_000L) return
        if (beatNow(pkg)) return
        // 进程刚启动时可能还没有 Context，稍后重试
        scheduleRetry(pkg, 1)
    }

    private fun scheduleRetry(pkg: String, attempt: Int) {
        if (attempt > 6) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (!beatNow(pkg)) scheduleRetry(pkg, attempt + 1)
        }, 4_000L * attempt)
    }
}
