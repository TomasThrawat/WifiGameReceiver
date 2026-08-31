package com.tomasthrawat.wifigamereceiver.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class UinputGamepadClient(context: Context) {

    private val connectionManager = TvAdbConnectionManager.getInstance(context)
    private var stream: AdbStream? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var readerJob: Job? = null
    private val readerScope = CoroutineScope(Dispatchers.IO)
    private var registered = false

    private val deviceId = 1

    private val buttonToKeyCode = mapOf(
        "A" to "BTN_SOUTH",
        "B" to "BTN_EAST",
        "X" to "BTN_WEST",
        "Y" to "BTN_NORTH",
        "START" to "BTN_START",
        "SELECT" to "BTN_SELECT",
        "UP" to "BTN_DPAD_UP",
        "DOWN" to "BTN_DPAD_DOWN",
        "LEFT" to "BTN_DPAD_LEFT",
        "RIGHT" to "BTN_DPAD_RIGHT"
    )

    suspend fun pair(host: String, pairPort: Int, pairingCode: String): Boolean =
        withContext(Dispatchers.IO) {
            connectionManager.pair(host, pairPort, pairingCode)
        }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        connectionManager.connect(host, port)
        val s = connectionManager.openStream("exec:uinput -")
        stream = s
        writer = BufferedWriter(OutputStreamWriter(s.openOutputStream(), StandardCharsets.UTF_8))
        reader = BufferedReader(InputStreamReader(s.openInputStream(), StandardCharsets.UTF_8))
        startReaderLoop()
        registerDevice()
    }

    private fun startReaderLoop() {
        readerJob = readerScope.launch {
            try {
                var line: String?
                while (reader?.readLine().also { line = it } != null) {
                    Log.d(TAG, "uinput said: $line")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun registerDevice() {
        val keyBits = buttonToKeyCode.values.joinToString(",") { "\"$it\"" }
        val json = """
            {
              "id": $deviceId,
              "command": "register",
              "name": "WifiGameController Virtual Gamepad",
              "vid": 6354,
              "pid": 1,
              "bus": "bluetooth",
              "configuration": [
                {"type":"UI_SET_EVBIT", "data":["EV_KEY", "EV_ABS"]},
                {"type":"UI_SET_KEYBIT", "data":[$keyBits]},
                {"type":"UI_SET_ABSBIT", "data":["ABS_X", "ABS_Y"]}
              ],
              "abs_info": [
                {"code":"ABS_X", "info": {"value":0, "minimum":-255, "maximum":255, "fuzz":0, "flat":16, "resolution":0}},
                {"code":"ABS_Y", "info": {"value":0, "minimum":-255, "maximum":255, "fuzz":0, "flat":16, "resolution":0}}
              ]
            }
        """.trimIndent()
        writeLine(json)
        registered = true
        writeLine("""{"id": $deviceId, "command": "delay", "duration": 300}""")
        writeLine("""{"id": $deviceId, "command": "sync", "syncToken": "reg_check"}""")
    }

    fun sendButton(buttonCode: String, pressed: Boolean) {
        val key = buttonToKeyCode[buttonCode] ?: return
        val value = if (pressed) 1 else 0
        val json = """
            {"id": $deviceId, "command": "inject", "events": [
              "EV_KEY", "$key", $value,
              "EV_SYN", "SYN_REPORT", 0
            ]}
        """.trimIndent()
        writeLine(json)
    }

    fun sendStick(x: Float, y: Float) {
        val ix = (x.coerceIn(-1f, 1f) * 255).toInt()
        val iy = (y.coerceIn(-1f, 1f) * 255).toInt()
        val json = """
            {"id": $deviceId, "command": "inject", "events": [
              "EV_ABS", "ABS_X", $ix,
              "EV_ABS", "ABS_Y", $iy,
              "EV_SYN", "SYN_REPORT", 0
            ]}
        """.trimIndent()
        writeLine(json)
    }

    private fun writeLine(json: String) {
        val w = writer ?: return
        try {
            w.write(json)
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "write FAILED — ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        try { writer?.close() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        try { stream?.close() } catch (_: Exception) { }
        writer = null
        reader = null
        stream = null
        registered = false
    }

    fun isRegistered(): Boolean = registered

    companion object {
        private const val TAG = "UinputGamepad"
    }
}
