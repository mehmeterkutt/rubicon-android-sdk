package com.rubicon.analytics.internal.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.NetworkMonitor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One network callback for the whole SDK (Retry 6, Context 2): it decides whether to try an upload,
 * flushes when the network returns, ends a network wait, and gives `network.type` to the context.
 *
 * - Registered once, at [start], in `try/catch(Throwable)`, never unregistered: Android limits the
 *   callbacks an app may hold. `registerDefaultNetworkCallback` from API 24, a request for internet
 *   networks before it.
 * - Connected means the network has `NET_CAPABILITY_INTERNET`; `VALIDATED` is never required, since
 *   some networks never validate.
 * - Without `ACCESS_NETWORK_STATE`: always connected, no callback, no network type, one WARN.
 * - If registration fails: the state is queried at each [isConnected], there is no automatic flush
 *   on return, one WARN.
 */
@RubiconInternal
internal class AndroidNetworkMonitor(
    private val context: Context,
    private val connectivity: () -> ConnectivityManager? = {
        context.getSystemService(
            Context.CONNECTIVITY_SERVICE,
        ) as? ConnectivityManager
    },
) : NetworkMonitor {
    private enum class Mode { NOT_STARTED, CALLBACK, QUERY, NO_PERMISSION }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<NetworkMonitor.Listener>()

    @Volatile
    private var mode = Mode.NOT_STARTED

    @Volatile
    private var connected = true

    @Volatile
    private var lastType: String? = null

    @Volatile
    private var lastNetwork: Network? = null

    /**
     * Called on the callback's thread when the network or its type changes, for the context cache
     * (Context 2). Set by the context provider on its first refresh, after [start].
     */
    @Volatile
    var onNetworkChanged: (() -> Unit)? = null

    /** Whether changes arrive by callback; otherwise the context cache reads them itself. */
    val usesCallback: Boolean get() = mode == Mode.CALLBACK

    /** `wifi`, `cellular`, `ethernet`, `other` or `none`; `null` when it cannot be known (Context 3). */
    val networkType: String?
        get() =
            when (mode) {
                Mode.NO_PERMISSION -> null
                Mode.QUERY -> query()?.type
                else -> lastType
            }

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val state = State.of(capabilities)
                // Capabilities also change with signal strength: only another network or type counts.
                val changed = network != lastNetwork || state.type != lastType
                lastNetwork = network
                update(state)
                if (changed) networkChanged()
            }

            override fun onLost(network: Network) {
                // Below API 24 the request may cover several networks: ask what is left.
                lastNetwork = null
                update(query() ?: State.NONE)
                networkChanged()
            }
        }

    override fun start(log: Log) {
        synchronized(lock) {
            if (mode != Mode.NOT_STARTED) return
            if (!hasPermission()) {
                mode = Mode.NO_PERMISSION
                connected = true
                log.warn(
                    Code.NETWORK_STATE_PERMISSION_MISSING,
                ) { "ACCESS_NETWORK_STATE was removed; the network is assumed to be connected" }
                return
            }
            try {
                val manager = connectivity() ?: throw IllegalStateException("No ConnectivityManager")
                register(manager)
                mode = Mode.CALLBACK
                val now = query()
                connected = now?.connected ?: true
                lastType = now?.type
            } catch (t: Throwable) {
                mode = Mode.QUERY
                log.warn(
                    Code.NETWORK_CALLBACK_FAILED,
                    t,
                ) { "The network callback could not be registered; the network is checked before each upload" }
            }
        }
    }

    override fun isConnected(): Boolean =
        when (mode) {
            Mode.NO_PERMISSION -> true
            Mode.QUERY -> query()?.connected ?: true
            else -> connected
        }

    override fun addListener(listener: NetworkMonitor.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: NetworkMonitor.Listener) {
        listeners -= listener
    }

    private fun update(state: State) {
        lastType = state.type
        val changed = synchronized(lock) { (connected != state.connected).also { connected = state.connected } }
        if (changed) listeners.forEach { it.onConnectivityChanged(state.connected) }
    }

    private fun networkChanged() {
        try {
            onNetworkChanged?.invoke()
        } catch (t: Throwable) {
            // The context keeps its previous values; the monitor goes on.
        }
    }

    @SuppressLint("MissingPermission")
    private fun register(manager: ConnectivityManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            manager.registerNetworkCallback(request, callback)
        }
    }

    // The current default network, or null when it cannot be read.
    @SuppressLint("MissingPermission")
    private fun query(): State? =
        try {
            val manager = connectivity()
            if (manager == null) {
                null
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = manager.activeNetwork
                val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                capabilities?.let(State::of) ?: State.NONE
            } else {
                @Suppress("DEPRECATION")
                val info = manager.activeNetworkInfo
                @Suppress("DEPRECATION")
                if (info != null && info.isConnected) State(true, legacyType(info.type)) else State.NONE
            }
        } catch (t: Throwable) {
            null
        }

    private fun hasPermission(): Boolean =
        context.checkPermission(Manifest.permission.ACCESS_NETWORK_STATE, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED

    private class State(
        val connected: Boolean,
        val type: String,
    ) {
        companion object {
            val NONE = State(false, "none")

            fun of(capabilities: NetworkCapabilities): State {
                val type =
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }
                // INTERNET only; VALIDATED is never required (Retry 6).
                return State(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), type)
            }
        }
    }

    private companion object {
        @Suppress("DEPRECATION")
        fun legacyType(type: Int): String =
            when (type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> "other"
            }
    }
}
