package com.tomasthrawat.wifigamereceiver

import kotlinx.coroutines.flow.MutableStateFlow

object ReceiverStatus {
    enum class State { STOPPED, CONNECTING_ADB, CONNECTED }
    val state = MutableStateFlow(State.STOPPED)
    val lastError = MutableStateFlow<String?>(null)
}
