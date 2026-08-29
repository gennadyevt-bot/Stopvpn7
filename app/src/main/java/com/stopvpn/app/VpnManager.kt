package com.stopvpn.app

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream

class VpnManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private var tunnel: WgTunnel? = null
    private var currentConfig: Config? = null
    private val futureBackend = CompletableDeferred<Backend>()
    private var currentServer: ServerInfo? = null

    var onStatusChanged: ((VpnStatus) -> Unit)? = null
    var onServerChanged: ((ServerInfo?) -> Unit)? = null

    companion object {
        private const val TAG = "StopVpnManager"
        private var globalStatus: VpnStatus = VpnStatus.DISCONNECTED
    }

    init {
        scope.launch(Dispatchers.IO) {
            try {
                backend = GoBackend(context)
                futureBackend.complete(backend!!)
                Log.i(TAG, "WireGuard backend initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize backend: ${e.message}")
                futureBackend.completeExceptionally(e)
            }
        }
    }

    fun prepareVpn(activity: Activity): Boolean {
        val intent = GoBackend.VpnService.prepare(activity)
        return intent == null
    }

    fun getPrepareIntent(activity: Activity): android.content.Intent? {
        return GoBackend.VpnService.prepare(activity)
    }

    fun connect(server: ServerInfo) {
        scope.launch(Dispatchers.IO) {
            try {
                updateStatus(VpnStatus.CONNECTING)
                currentServer = server
                onServerChanged?.invoke(server)

                val config = buildConfig(server)
                currentConfig = config

                val tunnelName = "stopvpn_${server.id}"
                tunnel = WgTunnel(tunnelName) { state ->
                    scope.launch(Dispatchers.Main) {
                        when (state) {
                            Tunnel.State.UP -> updateStatus(VpnStatus.CONNECTED)
                            Tunnel.State.DOWN -> updateStatus(VpnStatus.DISCONNECTED)
                            else -> updateStatus(VpnStatus.DISCONNECTED)
                        }
                    }
                }

                futureBackend.await().setState(tunnel!!, Tunnel.State.UP, config)
                Log.i(TAG, "Connected to ${server.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                updateStatus(VpnStatus.ERROR)
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                updateStatus(VpnStatus.DISCONNECTING)
                tunnel?.let { t ->
                    futureBackend.await().setState(t, Tunnel.State.DOWN, currentConfig)
                }
                updateStatus(VpnStatus.DISCONNECTED)
                currentServer = null
                onServerChanged?.invoke(null)
                Log.i(TAG, "Disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect failed: ${e.message}")
                updateStatus(VpnStatus.ERROR)
            }
        }
    }

    fun switchServer(newServer: ServerInfo) {
        scope.launch(Dispatchers.IO) {
            try {
                updateStatus(VpnStatus.SWITCHING)
                tunnel?.let { t ->
                    futureBackend.await().setState(t, Tunnel.State.DOWN, currentConfig)
                }
                delay(500)
                connect(newServer)
            } catch (e: Exception) {
                Log.e(TAG, "Switch failed: ${e.message}")
                updateStatus(VpnStatus.ERROR)
            }
        }
    }

    fun getStatus(): VpnStatus = globalStatus
    fun getCurrentServer(): ServerInfo? = currentServer

    fun isVpnActive(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.type == ConnectivityManager.TYPE_VPN
            }
        } catch (e: Exception) { false }
    }

    private fun buildConfig(server: ServerInfo): Config {
        val wgQuickConfig = """
            [Interface]
            Address = ${server.interfaceAddress}
            DNS = ${server.interfaceDns}
            PrivateKey = ${server.interfacePrivateKey}

            [Peer]
            PublicKey = ${server.peerPublicKey}
            ${if (server.peerPresharedKey.isNotEmpty()) "PresharedKey = ${server.peerPresharedKey}" else ""}
            AllowedIPs = ${server.peerAllowedIPs}
            Endpoint = ${server.peerEndpoint}
            PersistentKeepalive = ${server.peerPersistentKeepalive}
        """.trimIndent()
        return Config.parse(ByteArrayInputStream(wgQuickConfig.toByteArray()))
    }

    private fun updateStatus(status: VpnStatus) {
        globalStatus = status
        scope.launch(Dispatchers.Main) {
            onStatusChanged?.invoke(status)
        }
    }
}
