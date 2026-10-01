package com.rubicon.analytics.internal.config

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * The frozen flag of one `AnalyticsConfig` and its groups (Configuration 4). Before [freeze] every
 * setting is writable; afterwards a write calls [onFrozenWrite] with the setting's name and is
 * ignored, unless [onFrozenWrite] throws, which it does in debug.
 */
internal class Freezer {
    @Volatile
    private var frozen: Boolean = false

    @Volatile
    private var onFrozenWrite: (String) -> Unit = {}

    fun freeze(onFrozenWrite: (setting: String) -> Unit) {
        this.onFrozenWrite = onFrozenWrite
        frozen = true
    }

    /** Whether a write to [setting] may proceed. */
    fun allowWrite(setting: String): Boolean {
        if (!frozen) return true
        onFrozenWrite(setting)
        return false
    }

    /** A setting that stops changing once the configuration is frozen: one line per setting. */
    fun <T> setting(initial: T): ReadWriteProperty<Any, T> = Setting(initial, this)

    private class Setting<T>(
        initial: T,
        private val freezer: Freezer,
    ) : ReadWriteProperty<Any, T> {
        @Volatile
        private var value: T = initial

        override fun getValue(
            thisRef: Any,
            property: KProperty<*>,
        ): T = value

        override fun setValue(
            thisRef: Any,
            property: KProperty<*>,
            value: T,
        ) {
            if (freezer.allowWrite(property.name)) this.value = value
        }
    }
}
