package com.stopvpn.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var vpnManager: VpnManager
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var btnPower: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var ivLogo: ImageView
    private lateinit var rvServers: RecyclerView
    private lateinit var tvCurrentServer: TextView
    private lateinit var fabAddServer: FloatingActionButton

    private var selectedServer: ServerInfo? = null
    private val servers = mutableListOf<ServerInfo>()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedServer?.let { connectToServer(it) }
        } else {
            Toast.makeText(this, "Разрешение VPN отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vpnManager = VpnManager(this)

        btnPower = findViewById(R.id.btnPower)
        tvStatus = findViewById(R.id.tvStatus)
        ivLogo = findViewById(R.id.ivLogo)
        rvServers = findViewById(R.id.rvServers)
        tvCurrentServer = findViewById(R.id.tvCurrentServer)
        fabAddServer = findViewById(R.id.fabAddServer)

        servers.addAll(listOf(
            ServerInfo(
                id = "nl-ams-01",
                name = "NL-AMS-01",
                country = "Нидерланды, Амстердам",
                flagEmoji = "🇳🇱",
                interfaceAddress = "10.64.0.2/32",
                interfaceDns = "1.1.1.1, 8.8.8.8",
                interfacePrivateKey = "YOUR_PRIVATE_KEY_HERE",
                peerPublicKey = "YOUR_SERVER_PUBLIC_KEY_HERE",
                peerEndpoint = "nl-ams-01.stopvpn.example:51820"
            ),
            ServerInfo(
                id = "de-fra-01",
                name = "DE-FRA-01",
                country = "Германия, Франкфурт",
                flagEmoji = "🇩🇪",
                interfaceAddress = "10.64.0.3/32",
                interfaceDns = "1.1.1.1, 8.8.8.8",
                interfacePrivateKey = "YOUR_PRIVATE_KEY_HERE",
                peerPublicKey = "YOUR_SERVER_PUBLIC_KEY_HERE",
                peerEndpoint = "de-fra-01.stopvpn.example:51820"
            ),
            ServerInfo(
                id = "us-nyc-01",
                name = "US-NYC-01",
                country = "США, Нью-Йорк",
                flagEmoji = "🇺🇸",
                interfaceAddress = "10.64.0.4/32",
                interfaceDns = "1.1.1.1, 8.8.8.8",
                interfacePrivateKey = "YOUR_PRIVATE_KEY_HERE",
                peerPublicKey = "YOUR_SERVER_PUBLIC_KEY_HERE",
                peerEndpoint = "us-nyc-01.stopvpn.example:51820"
            )
        ))

        setupRecyclerView()
        setupVpnCallbacks()
        updateUiState(VpnStatus.DISCONNECTED)

        btnPower.setOnClickListener {
            when (vpnManager.getStatus()) {
                VpnStatus.CONNECTED, VpnStatus.CONNECTING -> vpnManager.disconnect()
                else -> {
                    selectedServer?.let { requestVpnPermissionAndConnect(it) }
                        ?: Toast.makeText(this, "Сначала выберите сервер из списка", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fabAddServer.setOnClickListener {
            showAddServerDialog()
        }
    }

    private fun setupRecyclerView() {
        serverAdapter = ServerAdapter(servers) { server ->
            when (vpnManager.getStatus()) {
                VpnStatus.CONNECTED -> {
                    if (vpnManager.getCurrentServer()?.id != server.id) {
                        vpnManager.switchServer(server)
                    }
                }
                VpnStatus.CONNECTING, VpnStatus.SWITCHING -> { }
                else -> {
                    selectedServer = server
                    serverAdapter.setSelectedServer(server.id)
                    requestVpnPermissionAndConnect(server)
                }
            }
        }
        rvServers.layoutManager = LinearLayoutManager(this)
        rvServers.adapter = serverAdapter
    }

    private fun setupVpnCallbacks() {
        vpnManager.onStatusChanged = { status ->
            updateUiState(status)
            serverAdapter.setStatus(status)
        }
        vpnManager.onServerChanged = { server ->
            server?.let {
                tvCurrentServer.text = "Сервер: ${it.flagEmoji} ${it.name}"
                serverAdapter.setSelectedServer(it.id)
            } ?: run {
                tvCurrentServer.text = "Сервер: не выбран"
                serverAdapter.setSelectedServer(null)
            }
        }
    }

    private fun requestVpnPermissionAndConnect(server: ServerInfo) {
        val intent = vpnManager.getPrepareIntent(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            connectToServer(server)
        }
    }

    private fun connectToServer(server: ServerInfo) {
        if (server.interfacePrivateKey == "YOUR_PRIVATE_KEY_HERE" ||
            server.peerPublicKey == "YOUR_SERVER_PUBLIC_KEY_HERE") {
            Toast.makeText(this, "Замени демо-ключи на реальные!", Toast.LENGTH_LONG).show()
            return
        }
        vpnManager.connect(server)
    }

    private fun showAddServerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etCountry = view.findViewById<EditText>(R.id.etCountry)
        val etEndpoint = view.findViewById<EditText>(R.id.etEndpoint)
        val etPrivateKey = view.findViewById<EditText>(R.id.etPrivateKey)
        val etPublicKey = view.findViewById<EditText>(R.id.etPublicKey)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Добавить") { _, _ ->
                val name = etName.text.toString().trim()
                val country = etCountry.text.toString().trim()
                val endpoint = etEndpoint.text.toString().trim()
                val privateKey = etPrivateKey.text.toString().trim()
                val publicKey = etPublicKey.text.toString().trim()

                if (name.isEmpty() || endpoint.isEmpty() || privateKey.isEmpty() || publicKey.isEmpty()) {
                    Toast.makeText(this, "Заполни все поля", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newServer = ServerInfo(
                    id = "custom_${System.currentTimeMillis()}",
                    name = name,
                    country = country.ifEmpty { "Custom" },
                    flagEmoji = "🌍",
                    interfaceAddress = "10.64.0.100/32",
                    interfaceDns = "1.1.1.1, 8.8.8.8",
                    interfacePrivateKey = privateKey,
                    peerPublicKey = publicKey,
                    peerEndpoint = endpoint
                )
                servers.add(newServer)
                serverAdapter.notifyItemInserted(servers.size - 1)
                rvServers.scrollToPosition(servers.size - 1)
                Toast.makeText(this, "Сервер добавлен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateUiState(status: VpnStatus) {
        when (status) {
            VpnStatus.CONNECTED -> {
                btnPower.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
                tvStatus.text = "VPN активен"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            }
            VpnStatus.CONNECTING -> {
                btnPower.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark)
                tvStatus.text = "Подключение..."
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            }
            VpnStatus.SWITCHING -> {
                btnPower.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark)
                tvStatus.text = "Смена сервера..."
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            }
            VpnStatus.DISCONNECTING -> {
                btnPower.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark)
                tvStatus.text = "Отключение..."
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            }
            else -> {
                btnPower.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
                tvStatus.text = "VPN отключен"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnManager.getStatus() == VpnStatus.CONNECTED) {
            vpnManager.disconnect()
        }
    }
}
