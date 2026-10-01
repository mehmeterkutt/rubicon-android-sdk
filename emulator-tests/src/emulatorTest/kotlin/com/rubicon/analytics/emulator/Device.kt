package com.rubicon.analytics.emulator

import java.io.File
import java.util.concurrent.TimeUnit

/** The connected emulator or device, driven through adb. */
class Device(
    private val adb: String,
    private val serial: String?,
) {
    val apiLevel: Int by lazy { shell("getprop ro.build.version.sdk").trim().toInt() }

    fun run(vararg args: String): String {
        val command = listOf(adb) + (if (serial != null) listOf("-s", serial) else emptyList()) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) { "adb timed out: $command" }
        return output
    }

    fun shell(command: String): String = run("shell", command)

    fun install(apk: File) {
        val output = run("install", "-r", "-t", apk.absolutePath)
        check(output.contains("Success")) { "install failed: $output" }
    }

    fun uninstall() {
        run("uninstall", PACKAGE)
    }

    fun reverse(
        devicePort: Int,
        hostPort: Int,
    ) {
        run("reverse", "tcp:$devicePort", "tcp:$hostPort")
    }

    /** A command to the test app's `CommandReceiver`; starts its process if needed. */
    fun command(
        name: String,
        extras: String = "",
    ) {
        broadcast("CommandReceiver", "--es cmd $name $extras")
    }

    /** `-f 32`: FLAG_INCLUDE_STOPPED_PACKAGES, since a freshly installed app is stopped. */
    fun broadcast(
        receiver: String,
        extras: String = "",
    ) {
        shell("am broadcast -f 32 -n $PACKAGE/.$receiver $extras")
    }

    fun startActivity(activity: String) {
        shell("am start -W -n $PACKAGE/.$activity")
    }

    fun openLink(uri: String) {
        shell("am start -W -a android.intent.action.VIEW -d '$uri' -n $PACKAGE/.LinkActivity")
    }

    fun pressHome() {
        shell("input keyevent 3")
    }

    fun runAs(command: String): String = shell("run-as $PACKAGE $command")

    /** The pid of the process named exactly [name], or null. `ps` needs `-A` from API 26. */
    fun pidOf(name: String): Int? {
        val list = shell(if (apiLevel >= 26) "ps -A" else "ps")
        return list
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size > 2 && it.last() == name }
            ?.get(1)
            ?.toIntOrNull()
    }

    fun setNetwork(on: Boolean) {
        val state = if (on) "enable" else "disable"
        shell("svc wifi $state")
        shell("svc data $state")
    }

    fun clearLogcat() {
        shell("logcat -c")
    }

    fun logcat(): String = shell("logcat -d")

    companion object {
        const val PACKAGE = "com.rubicon.analytics.testapp"
        private const val TIMEOUT_S = 180L

        fun fromSystemProperties(): Device = Device(System.getProperty("adb"), System.getProperty("serial"))
    }
}
