package com.dq.vpndlnarelay

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI-observable relay state. All writes are funnelled onto the main thread. */
object RelayState {
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("Stopped")
        private set
    var discoveredPort by mutableIntStateOf(0)
        private set
    val log = mutableStateListOf<String>()

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun setRunning(value: Boolean) = main.post { running = value }
    fun setStatus(value: String) = main.post { status = value }
    fun setDiscoveredPort(value: Int) = main.post { discoveredPort = value }

    fun log(message: String) {
        val line = "${clock.format(Date())}  $message"
        main.post {
            log.add(0, line)
            while (log.size > MAX_LOG_LINES) log.removeAt(log.lastIndex)
        }
    }

    private const val MAX_LOG_LINES = 200
}

object RelayPrefs {
    const val DEFAULT_HOST = "192.168.4.111"
    private const val FILE = "relay"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun host(context: Context): String = prefs(context).getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, 0)

    fun save(context: Context, host: String, port: Int) {
        prefs(context).edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply()
    }

    fun savePort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port).apply()
    }
}
