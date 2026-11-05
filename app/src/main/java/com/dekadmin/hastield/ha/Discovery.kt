package com.dekadmin.hastield.ha

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Collections

/** Un serveur Home Assistant repéré sur le réseau local via mDNS. */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int
) {
    val url: String
        get() = "http://$host:$port"
}

/**
 * Découverte des instances Home Assistant locales à partir du service mDNS
 * `_home-assistant._tcp.` (celui exposé par l'intégration "Home Assistant"
 * par défaut).
 */
class HaDiscovery(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_home-assistant._tcp."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = Collections.synchronizedList(ArrayDeque<NsdServiceInfo>())

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var running = false
    private var onFound: ((DiscoveredServer) -> Unit)? = null
    private var onFinished: (() -> Unit)? = null

    private val timeoutRunnable = Runnable { stop() }

    fun start(
        durationMs: Long = 8000,
        onFound: (DiscoveredServer) -> Unit,
        onFinished: () -> Unit
    ) {
        stop()

        this.onFound = onFound
        this.onFinished = onFinished
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager
        running = true

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                pending.add(serviceInfo)
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                mainHandler.post { finish() }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            mainHandler.post { finish() }
            return
        }
        mainHandler.postDelayed(timeoutRunnable, durationMs)
    }

    /** NsdManager n'autorise qu'une résolution à la fois : on sérialise. */
    private fun resolveNext() {
        if (!running || resolving) return
        val manager = nsdManager ?: return
        val next = synchronized(pending) { if (pending.isEmpty()) null else pending.removeFirst() }
            ?: return

        resolving = true
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolving = false
                    val host = hostAddressOf(serviceInfo)
                    if (host != null && serviceInfo.port > 0) {
                        val name = serviceInfo.serviceName?.removeSuffix(".") ?: host
                        mainHandler.post { onFound?.invoke(DiscoveredServer(name, host, serviceInfo.port)) }
                    }
                    resolveNext()
                }
            })
        } catch (e: Exception) {
            resolving = false
            resolveNext()
        }
    }

    private fun hostAddressOf(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        info.host?.hostAddress?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return info.hostAddresses.firstOrNull()?.hostAddress
        }
        return null
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(timeoutRunnable)
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (_: Exception) {
                // ignore : découverte déjà arrêtée
            }
        }
        discoveryListener = null
        resolveNextPaused()
    }

    private fun resolveNextPaused() {
        synchronized(pending) { pending.clear() }
        finish()
    }

    private fun finish() {
        if (running) running = false
        val callback = onFinished
        onFinished = null
        onFound = null
        callback?.invoke()
    }
}
