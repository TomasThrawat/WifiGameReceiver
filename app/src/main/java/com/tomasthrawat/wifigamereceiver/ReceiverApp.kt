package com.tomasthrawat.wifigamereceiver

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class ReceiverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
