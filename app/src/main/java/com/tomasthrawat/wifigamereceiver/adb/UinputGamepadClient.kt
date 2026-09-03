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

    // Numeric Linux input-event-codes (bionic libc/kernel/uapi/linux/input-event-codes.h).
    // AOSP's `uinput` cmd (frameworks/base cmds/uinput, Event.java/Device.java on
    // android14-release) parses these fields with Integer.decode(), which only accepts
    // decimal/hex numbers — NOT symbolic names like "BTN_SOUTH". Symbolic strings here
    // throw NumberFormatException -> "Error reading in object, ignoring." and kill the
    // uinput process. Keep these as raw Int codes, never symbolic names.
    private val buttonToKeyCode = mapOf(
        "A" to 304,      // BTN_SOUTH / BTN_A
        "B" to 305,      // BTN_EAST / BTN_B
        "X" to 308,      // BTN_WEST / BTN_Y
        "Y" to 307,      // BTN_NORTH / BTN_X
        "START" to 315,  // BTN_START
        "SELECT" to 314, // BTN_SELECT
        "UP" to 544,     // BTN_DPAD_UP
        "DOWN" to 545,   // BTN_DPAD_DOWN
        "LEFT" to 546,   // BTN_DPAD_LEFT
        "RIGHT" to 547   // BTN_DPAD_RIGHT
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
        val keyBits = buttonToKeyCode.values.joinToString(",")
        val json = """
            {
              "id": $deviceId,
              "command": "register",
              "name": "WifiGameController Virtual Gamepad",
              "vid": 6354,
              "pid": 1,
              "bus": "bluetooth",
              "configuration": [
                {"type":100, "data":[1, 3]},
                {"type":101, "data":[$keyBits]},
                {"type":103, "data":[0, 1]}
              ],
              "abs_info": [
                {"code":0, "info": {"value":0, "minimum":-255, "maximum":255, "fuzz":0, "flat":16, "resolution":0}},
                {"code":1, "info": {"value":0, "minimum":-255, "maximum":255, "fuzz":0, "flat":16, "resolution":0}}
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
             1, $key, $value,
             0, 0, 0
            ]}
        """.trimIndent()
        writeLine(json)
    }

    fun sendStick(x: Float, y: Float) {
        val ix = (x.coerceIn(-1f, 1f) * 255).toInt()
        val iy = (y.coerceIn(-1f, 1f) * 255).toInt()
        val json = """
            {"id": $deviceId, "command": "inject", "events": [
              3, 0, $ix,
              3, 1, $iy,
              0, 0, 0
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
