/*
 **********************************************************************
 * -------------------------------------------------------------------
 * Project Name : SSH Tunnel
 * File Name : AppViewModel.kt
 * Author : Ebrahim Shafiei (EbraSha)
 * Email : Prof.Shafiei@Gmail.com
 * Created On : 2026-06-03 15:02:25
 * Description : ViewModel for server list and VPN connect/disconnect actions.
 * -------------------------------------------------------------------
 *
 * "Coding is an engaging and beloved hobby for me. I passionately and insatiably pursue knowledge in cybersecurity and programming."
 * – Ebrahim Shafiei
 *
 **********************************************************************
 */

package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ServerEntity
import com.example.data.ServerRepository
import com.example.vpn.AbdalVpnService
import com.example.vpn.VpnState
import com.example.vpn.VpnStateNotifier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREF_SELECTED_SERVER_ID = "selected_server_id"
    }

    private val repository: ServerRepository

    private val _allServers = MutableStateFlow<List<ServerEntity>>(emptyList())
    val allServers: StateFlow<List<ServerEntity>> = _allServers

    private val _selectedServer = kotlinx.coroutines.flow.MutableStateFlow<ServerEntity?>(null)
    val selectedServer: StateFlow<ServerEntity?> = _selectedServer
    fun selectServer(server: ServerEntity?) {
        _selectedServer.value = server
        prefs.edit().putInt(PREF_SELECTED_SERVER_ID, server?.id ?: -1).apply()
    }

    val vpnState: StateFlow<VpnState> = VpnStateNotifier.vpnState
    val errorMessage: StateFlow<String?> = VpnStateNotifier.errorMessage

    private val prefs = application.getSharedPreferences("abdal_vpn_prefs", Context.MODE_PRIVATE)

    private val _killSwitchEnabled = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("kill_switch", true))
    val killSwitchEnabled: StateFlow<Boolean> = _killSwitchEnabled
    fun setKillSwitch(enabled: Boolean) {
        _killSwitchEnabled.value = enabled
        prefs.edit().putBoolean("kill_switch", enabled).apply()
    }

    private val _whitelistIps = kotlinx.coroutines.flow.MutableStateFlow(prefs.getString("whitelist_ips", "") ?: "")
    val whitelistIps: StateFlow<String> = _whitelistIps
    fun setWhitelistIps(ips: String) {
        _whitelistIps.value = ips
        prefs.edit().putString("whitelist_ips", ips).apply()
    }

    init {
        val serverDao = AppDatabase.getDatabase(application).serverDao()
        repository = ServerRepository(serverDao)

        viewModelScope.launch {
            repository.allServers.collect { servers ->
                _allServers.value = servers

                if (_selectedServer.value == null && servers.isNotEmpty()) {
                    val savedId = prefs.getInt(PREF_SELECTED_SERVER_ID, -1)
                    if (savedId != -1) {
                        val saved = servers.find { it.id == savedId }
                        if (saved != null) {
                            _selectedServer.value = saved
                        } else {
                            _selectedServer.value = servers.first()
                        }
                    } else {
                        _selectedServer.value = servers.first()
                    }
                }
            }
        }
    }

    fun getSavedServerId(): Int {
        return prefs.getInt(PREF_SELECTED_SERVER_ID, -1)
    }

    fun addServer(name: String, ip: String, port: Int, user: String, pass: String) {
        viewModelScope.launch {
            repository.insert(ServerEntity(name = name, ip = ip, port = port, username = user, password = pass))
        }
    }

    fun updateServer(server: ServerEntity) {
        viewModelScope.launch {
            repository.update(server)
            if (_selectedServer.value?.id == server.id) {
                _selectedServer.value = server
            }
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            repository.delete(server)
            if (_selectedServer.value?.id == server.id) {
                _selectedServer.value = null
                prefs.edit().putInt(PREF_SELECTED_SERVER_ID, -1).apply()
            }
        }
    }

    fun reportVpnPermissionDenied() {
        VpnStateNotifier.updateState(VpnState.ERROR, "VPN permission was denied")
    }

    fun reportNoServerSelected() {
        VpnStateNotifier.updateState(VpnState.ERROR, "Select a server before connecting")
    }

    fun toggleVpn(context: Context, server: ServerEntity) {
        if (vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING) {
            val intent = Intent(context, AbdalVpnService::class.java).apply {
                action = AbdalVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
        } else {
            val intent = Intent(context, AbdalVpnService::class.java).apply {
                action = AbdalVpnService.ACTION_CONNECT
                putExtra(AbdalVpnService.EXTRA_IP, server.ip)
                putExtra(AbdalVpnService.EXTRA_PORT, server.port)
                putExtra(AbdalVpnService.EXTRA_USERNAME, server.username)
                putExtra(AbdalVpnService.EXTRA_PASSWORD, server.password)
                putExtra(AbdalVpnService.EXTRA_KILL_SWITCH, _killSwitchEnabled.value)
                putExtra(AbdalVpnService.EXTRA_WHITELIST, _whitelistIps.value)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
