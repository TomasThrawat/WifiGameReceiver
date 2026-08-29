package com.tomasthrawat.wifigamereceiver

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs inside the Shizuku UserService process (shell UID, ADB privilege) —
 * NOT inside the normal app process. Spawns the AOSP `uinput` binary
 * directly (this process already has shell UID, no Shizuku.newProcess
 * wrapper needed) and drives it over its interactive stdin protocol to
 * create a real kernel gamepad device.
 */
class UinputUserService : IUinputService.Stub() {

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private val registered = AtomicBoolean(false)
    private val deviceId = 1

    override fun registerDevice() {
        if (registered.get()) return
        try {
            val p = ProcessBuilder("/system/bin/uinput", "-")
                .redirectErrorStream(true)
                .start()
            process = p
            stdin = p.outputStream
            write(UinputProtocol.registerCommand(deviceId))
            registered.set(true)
        } catch (_: Exception) {
            registered.set(false)
        }
    }

    override fun sendButton(keyCode: Int, down: Boolean) {
        if (!registered.get()) return
        write(UinputProtocol.keyCommand(deviceId, keyCode, down))
    }

    override fun sendDpad(x: Int, y: Int) {
        if (!registered.get()) return
        write(UinputProtocol.hatCommand(deviceId, x, y))
    }

    override fun sendJoystick(x: Int, y: Int) {
        if (!registered.get()) return
        write(UinputProtocol.stickCommand(deviceId, x, y))
    }

    override fun unregisterDevice() {
        // Closing stdin sends EOF to the uinput process, which unregisters
        // the device automatically — there is no explicit unregister command.
        try { stdin?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        stdin = null
        registered.set(false)
    }

    override fun destroy() {
        unregisterDevice()
    }

    private fun write(json: String) {
        try {
            stdin?.write((json + "\n").toByteArray(Charsets.UTF_8))
            stdin?.flush()
        } catch (_: Exception) {
            registered.set(false)
        }
    }
}
