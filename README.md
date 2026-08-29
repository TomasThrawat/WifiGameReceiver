# WifiGameReceiver

Kotlin Android receiver for **[WifiGameController](https://github.com/TomasThrawat/WifiGameController)**. Turns this phone/tablet into a **real virtual gamepad** that any app or game on the device can see — driven by the exact same local-WiFi UDP protocol the controller app sends.

## Why Shizuku, and why `uinput`

A normal (non-root) Android app cannot inject input events for other apps — that needs the system `INJECT_EVENTS` permission. Two ways around that without root:

- **Shizuku** gives an app a Binder connection running at the **shell (ADB) UID**, no root needed — just Shizuku running (via wireless debugging or a one-time `adb` command).
- AOSP ships a small **`uinput`** command-line tool (`frameworks/base/cmds/uinput`) built exactly for this: it registers a virtual kernel input device (keyboard, gamepad, joystick, whatever you configure) and lets you inject raw evdev events into it over stdin. CTS uses it this way, run as shell — so shell-level access is enough to run it too, no root required *on AOSP-compliant devices*.

Because the resulting device is a **real kernel input device** (not a fake overlay), any app — an emulator, a game, anything reading `SOURCE_GAMEPAD`/`SOURCE_JOYSTICK`/`SOURCE_DPAD` — sees it exactly like a real USB/Bluetooth controller.

The app does **not** use the older `Shizuku.newProcess()` helper (Shizuku's own team has deprecated/hidden it and recommends against it — no tty support, unreliable for a long-lived interactive process). Instead it uses a Shizuku **`UserService`**: a small privileged component (`UinputUserService`) that runs in its own process at shell UID and spawns `/system/bin/uinput -` directly with `ProcessBuilder`, keeping its stdin open for the life of the session.

## Requirements

- Shizuku installed and **running** (you said it already is — good, this app needs nothing else set up on that front).
- Same WiFi network as the phone running WifiGameController.

> **Note on non-AOSP devices (OEM SELinux):** the `uinput` binary and its shell-level access are AOSP-documented and used by CTS as shell, but individual OEM skins (heavily customized SELinux policies, e.g. some ColorOS/MIUI-style builds) can restrict `/dev/uinput` access beyond stock AOSP. If `Register device` fails in the app, that's the most likely cause — this is a device-policy limitation, not something the app can work around.

## Setup

1. Open the app, tap **Grant Shizuku permission** and accept the prompt in Shizuku.
2. Enter the same UDP port you used in WifiGameController's connect screen.
3. Tap **Start**. The app registers the virtual gamepad and starts listening.
4. On the other phone, open WifiGameController, enter this device's local IP + the same port, and play.

## Protocol (matches WifiGameController exactly)

| Message | Meaning |
|---|---|
| `A:1` / `A:0` | Button A down / up |
| `B:1` / `B:0` | Button B down / up |
| `X:1` / `X:0` | Button X down / up |
| `Y:1` / `Y:0` | Button Y down / up |
| `START:1` / `START:0` | Start down / up |
| `SELECT:1` / `SELECT:0` | Select down / up |
| `UP:1/0`, `DOWN:1/0`, `LEFT:1/0`, `RIGHT:1/0` | D-pad, combined into one hat-axis update |
| `JOY:x,y` | Analog stick, floats -1.0..1.0, streamed ~20/sec |

Buttons map to Linux gamepad key codes (`BTN_A`, `BTN_B`, ...); the D-pad maps to `ABS_HAT0X`/`ABS_HAT0Y`; the joystick maps to `ABS_X`/`ABS_Y` scaled to ±255. See `UinputProtocol.kt`.

Reference for the `uinput` command's register/inject JSON protocol: https://android.googlesource.com/platform/frameworks/base/+/main/cmds/uinput/README.md

## Project structure

- `MainActivity` — Shizuku permission flow, port entry, start/stop
- `GamepadBridge` — binds the Shizuku `UserService`
- `UinputUserService` — runs at shell UID, owns the `uinput` process
- `UinputProtocol` — builds the register/inject JSON commands + event codes
- `UdpReceiverService` — foreground service, UDP socket, protocol parsing

## Build

CI builds a debug APK on every push to `main` via `.github/workflows/build.yml` (uses the Gradle GitHub Action directly, no committed Gradle wrapper jar) — grab it from the workflow run's **Artifacts**. Locally: open in Android Studio, or run `gradle assembleDebug` with Gradle 8.7+ and Android SDK 34 installed.

## Permissions

`INTERNET`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and the Shizuku API permission — no root, no Bluetooth, no cloud relay.
