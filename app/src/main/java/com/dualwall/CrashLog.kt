package com.dualwall

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常的落地记录。
 *
 * 目的很直接：这类「一装就崩」的问题只看代码猜不出来，必须拿到真实堆栈。
 * 崩溃时把堆栈写进 filesDir/crash/last_crash.txt，下次启动由 EditorScreen 顶部的
 * 红色横幅展示出来，可以直接复制发出去 —— 不需要连 USB、不需要 logcat。
 */
class DualWallApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashLog.record(this, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashLog {

    private const val TAG = "DualWall"

    private fun file(context: Context): File = File(context.filesDir, "crash/last_crash.txt")

    /** 上次崩溃的完整现场；没有崩溃过返回 null。 */
    fun read(context: Context): String? = runCatching {
        file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun record(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { w ->
            w.println("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            w.println("线程: ${thread.name}")
            w.println(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}" +
                    " (API ${Build.VERSION.SDK_INT}) / ${Build.SUPPORTED_ABIS.firstOrNull()}"
            )
            w.println("指纹: ${Build.FINGERPRINT}")
            w.println()
            error.printStackTrace(w)
        }
        val dir = file(context).parentFile ?: return
        dir.mkdirs()
        file(context).writeText(sw.toString())
        Log.e(TAG, "未捕获异常", error)
    }
}
