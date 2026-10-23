package com.dq.vpndlnarelay

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dq.vpndlnarelay.ui.theme.VPNDLNARelayTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VPNDLNARelayTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RelayScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun RelayScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var host by remember { mutableStateOf(RelayPrefs.host(context)) }
    var port by remember { mutableStateOf(RelayPrefs.port(context).takeIf { it > 0 }?.toString().orEmpty()) }
    var scanning by remember { mutableStateOf(false) }
    var phoneOnly by remember { mutableStateOf(RelayPrefs.phoneOnly(context)) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, ACCESS_LOCAL_NETWORK))
    }
    // The service finds the port on its own when the saved one stops working.
    LaunchedEffect(RelayState.discoveredPort) {
        if (RelayState.discoveredPort > 0) port = RelayState.discoveredPort.toString()
    }

    fun save() = RelayPrefs.save(context, host.trim(), port.trim().toIntOrNull() ?: 0)

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("VPN DLNA Relay", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("DLNA server IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                label = { Text("Port") },
                placeholder = { Text("auto") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                enabled = !scanning && host.isNotBlank(),
                onClick = {
                    scanning = true
                    val target = host.trim()
                    RelayState.log("Scanning $target…")
                    thread(name = "relay-scan") {
                        val result = PortScanner.scan(target) { RelayState.log(it) }
                        RelayState.log(
                            when {
                                result.server != null -> "Found “${result.server.name}” on port ${result.server.port}"
                                result.hostResponded -> "No DLNA server found on $target"
                                else -> "$target is unreachable. Is the VPN connected?"
                            }
                        )
                        result.server?.let {
                            RelayPrefs.save(context, target, it.port)
                            RelayState.setDiscoveredPort(it.port)
                        }
                        scanning = false
                    }
                },
            ) { Text(if (scanning) "Scanning…" else "Scan for port") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Only this phone", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (phoneOnly) "Only apps on this phone can see the server"
                    else "Devices on the same Wi-Fi can see the server",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = phoneOnly,
                onCheckedChange = {
                    phoneOnly = it
                    RelayPrefs.savePhoneOnly(context, it)
                    // The listeners are bound per mode, so a running relay restarts to switch.
                    if (RelayState.running) {
                        save()
                        RelayService.start(context)
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = host.isNotBlank(),
                onClick = {
                    save()
                    RelayService.start(context)
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (RelayState.running) "Restart" else "Start") }
            OutlinedButton(
                enabled = RelayState.running,
                onClick = { RelayService.stop(context) },
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
        }
        Text(RelayState.status, style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(RelayState.log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
