package com.tomasthrawat.wifigamereceiver

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Owns the single Shizuku UserService connection for the whole app.
 * MainActivity triggers bind() once Shizuku permission is granted;
 * UdpReceiverService reads `service` to forward parsed UDP messages.
 */
object GamepadBridge {

    var service: IUinputService? = null
        private set
    var isBound = false
        private set

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UinputUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("uinput_service")
        .debuggable(BuildConfig.DEBUG)
        .version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val svc = IUinputService.Stub.asInterface(binder)
                service = svc
                isBound = true
                try { svc.registerDevice() } catch (_: Exception) {}
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    fun bind() {
        if (isBound) return
        if (!Shizuku.pingBinder() || Shizuku.getVersion() < 10) return
        Shizuku.bindUserService(userServiceArgs, connection)
    }

    fun unbind() {
        try { service?.destroy() } catch (_: Exception) {}
        try { Shizuku.unbindUserService(userServiceArgs, connection, true) } catch (_: Exception) {}
        service = null
        isBound = false
    }
}
