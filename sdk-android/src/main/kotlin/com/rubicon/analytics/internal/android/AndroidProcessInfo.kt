package com.rubicon.analytics.internal.android

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.rubicon.analytics.internal.platform.ProcessInfo

/**
 * Process facts for the main process rule (Disk 2d) and the User-Agent (HTTP 5).
 *
 * The main process is the one named like `applicationInfo.processName`, which falls back to the
 * package name and follows `<application android:process>`. The current name comes from
 * `Application.getProcessName()` on API 28 and later, and from `runningAppProcesses` matched on
 * `Process.myPid()` before. Never `/proc/self/cmdline` (a disk read on the main thread) or
 * `ActivityThread` (a hidden API).
 */
internal class AndroidProcessInfo(
    private val context: Context,
    private val currentName: () -> String? = { currentProcessName(context) },
) : ProcessInfo {
    override val osVersion: String
        get() = Build.VERSION.RELEASE ?: "unknown"

    override val apiLevel: Int
        get() = Build.VERSION.SDK_INT

    override fun isMainProcess(): Boolean? {
        val current = currentName() ?: return null
        val main = context.applicationInfo.processName ?: context.packageName
        return current == main
    }

    companion object {
        fun currentProcessName(context: Context): String? =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Application.getProcessName()
                } else {
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val pid = Process.myPid()
                    manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
                }
            } catch (e: Exception) {
                // Some devices throw from ActivityManager; the caller then assumes the main process.
                null
            }
    }
}
