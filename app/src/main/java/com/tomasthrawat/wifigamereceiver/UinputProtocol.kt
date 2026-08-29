package com.tomasthrawat.wifigamereceiver

/**
 * Builds the pseudo-JSON commands understood by the AOSP `uinput` binary
 * (frameworks/base/cmds/uinput). Protocol reference:
 * https://android.googlesource.com/platform/frameworks/base/+/main/cmds/uinput/README.md
 *
 * Event type/code constants below come from the stable Linux kernel UAPI
 * headers <linux/input-event-codes.h> and <linux/uinput.h>.
 */
object UinputProtocol {

    // linux/input-event-codes.h
    private const val EV_SYN = 0x00
    private const val EV_KEY = 0x01
    private const val EV_ABS = 0x03
    private const val SYN_REPORT = 0x00

    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11

    const val BTN_A = 0x130
    const val BTN_B = 0x131
    const val BTN_X = 0x133
    const val BTN_Y = 0x134
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b

    // linux/uinput.h UI_SET_* ioctl codes (100 + n, per the AOSP uinput README example)
    private const val UI_SET_EVBIT = 100
    private const val UI_SET_KEYBIT = 101
    private const val UI_SET_ABSBIT = 103

    private const val STICK_RANGE = 255

    /** Vid/pid reused from the official AOSP uinput README example (arbitrary, not a real device). */
    fun registerCommand(id: Int): String {
        val keys = listOf(BTN_A, BTN_B, BTN_X, BTN_Y, BTN_SELECT, BTN_START).joinToString(",")
        return """
            {"id":$id,"command":"register","name":"WifiGameReceiver Gamepad","vid":6354,"pid":11330,"bus":"bluetooth",
            "configuration":[
              {"type":$UI_SET_EVBIT,"data":[$EV_KEY,$EV_ABS]},
              {"type":$UI_SET_KEYBIT,"data":[$keys]},
              {"type":$UI_SET_ABSBIT,"data":[$ABS_X,$ABS_Y,$ABS_HAT0X,$ABS_HAT0Y]}
            ],
            "abs_info":[
              {"code":$ABS_X,"info":{"value":0,"minimum":-$STICK_RANGE,"maximum":$STICK_RANGE,"fuzz":0,"flat":0,"resolution":0}},
              {"code":$ABS_Y,"info":{"value":0,"minimum":-$STICK_RANGE,"maximum":$STICK_RANGE,"fuzz":0,"flat":0,"resolution":0}},
              {"code":$ABS_HAT0X,"info":{"value":0,"minimum":-1,"maximum":1,"fuzz":0,"flat":0,"resolution":0}},
              {"code":$ABS_HAT0Y,"info":{"value":0,"minimum":-1,"maximum":1,"fuzz":0,"flat":0,"resolution":0}}
            ]}
        """.trimIndent()
    }

    fun keyCommand(id: Int, code: Int, down: Boolean): String {
        val v = if (down) 1 else 0
        return injectCommand(id, listOf(EV_KEY, code, v, EV_SYN, SYN_REPORT, 0))
    }

    fun stickCommand(id: Int, x: Int, y: Int): String {
        val cx = x.coerceIn(-STICK_RANGE, STICK_RANGE)
        val cy = y.coerceIn(-STICK_RANGE, STICK_RANGE)
        return injectCommand(id, listOf(EV_ABS, ABS_X, cx, EV_ABS, ABS_Y, cy, EV_SYN, SYN_REPORT, 0))
    }

    fun hatCommand(id: Int, x: Int, y: Int): String {
        val cx = x.coerceIn(-1, 1)
        val cy = y.coerceIn(-1, 1)
        return injectCommand(id, listOf(EV_ABS, ABS_HAT0X, cx, EV_ABS, ABS_HAT0Y, cy, EV_SYN, SYN_REPORT, 0))
    }

    private fun injectCommand(id: Int, events: List<Int>): String {
        return "{\"id\":$id,\"command\":\"inject\",\"events\":[${events.joinToString(",")}]}"
    }
}
