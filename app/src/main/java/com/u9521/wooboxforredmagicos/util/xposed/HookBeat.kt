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
            val current = at.getMethod("currentActivityThread").invoke(null)
            at.getMethod("getSystemContext").invoke(current)?.let { return it as Context }
        }
        return null
    }

    fun report(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastBeat[pkg] ?: 0L
        if (now - last < 20_000L) return
        val ctx = context()
        if (ctx == null) {
            // 进程刚启动时可能还没有 Context，稍后重试
            scheduleRetry(pkg, 1)
            return
        }
        lastBeat[pkg] = now
        runCatching {
            ctx.contentResolver.insert(
                Uri.parse(URI_HEARTBEAT),
                ContentValues().apply {
                    put("pkg", pkg)
                    put("v", now.toString() + "|" + System.currentTimeMillis())
                }
            )
        }
    }

    private fun scheduleRetry(pkg: String, attempt: Int) {
        if (attempt > 4) return
        Handler(Looper.getMainLooper()).postDelayed({
            val ctx = context()
            if (ctx == null) {
                scheduleRetry(pkg, attempt + 1)
            } else {
                val now = SystemClock.elapsedRealtime()
                lastBeat[pkg] = now
                runCatching {
                    ctx.contentResolver.insert(
                        Uri.parse(URI_HEARTBEAT),
                        ContentValues().apply {
                            put("pkg", pkg)
                            put("v", now.toString() + "|" + System.currentTimeMillis())
                        }
                    )
                }
            }
        }, 3000L * attempt)
    }
}
