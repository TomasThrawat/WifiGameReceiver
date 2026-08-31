# WifiGameReceiver

Companion app for [WifiGameController](https://github.com/TomasThrawat/WifiGameController) that runs **on the TV itself** and fixes the reliability problem of that project's ADB mode.

## Why this exists

WifiGameController's ADB mode has the phone open an ADB connection **over WiFi, to the TV's remote IP**, and drive `/system/bin/uinput` through it. That ADB stream — TLS/CNXN/AUTH handshake plus the live `exec:uinput -` shell stream — has to survive the entire session over a real wireless link. Any WiFi hiccup (RSSI dip, AP roam, DHCP renewal, doze-mode radio throttling) can drop it mid-session, which is the "الاتصال بيقع / معلش نتأكد" problem.

This app inverts that: **the TV connects to itself.**

- `TvAdbConnectionManager` + `UinputGamepadClient` here are the *same* RSA-keypair-and-`exec:uinput -` client WifiGameController's mobile app already uses — copied verbatim, because the uinput/ADB protocol doesn't care whether the transport underneath is a LAN socket or loopback.
- The only thing that changed is the target: `connect("127.0.0.1", adbPort)` instead of the TV's LAN IP. That connection never leaves the device, so it can't be affected by WiFi conditions at all.
- The phone side needs **no changes** — WifiGameController's existing **UDP mode** (already in the repo, already fire-and-forget/lossy-tolerant by design) is pointed at this app's IP:port instead of a UDP listener that didn't exist before. `A:1`/`A:0`/`JOY:x,y` in, `uinput inject` out.

```
Phone (WifiGameController, UDP mode)
   │  UDP  "A:1" / "JOY:0.3,-0.8"   (only thing crossing WiFi — lossy-tolerant)
   ▼
TV (WifiGameReceiver)
   │  ADB exec:uinput -  over 127.0.0.1   (never leaves the device)
   ▼
Android input stack → virtual gamepad
```

## What loopback does and doesn't fix

- **Fixes:** the ADB/uinput leg can no longer drop because of WiFi — it's not on WiFi.
- **Doesn't fix:** adbd itself (whether started via classic "Network debugging" or Wireless debugging) still listens on all interfaces, not just `127.0.0.1` — that's inherent to how Android's ADB-over-network works, not something an app can restrict from inside. Anyone else on the same WiFi could still attempt an AUTH handshake against a *different* key. Same caveat WifiGameController's own README already states; this app doesn't change it either way, it just stops relying on that network path for its own traffic.

## One-time TV setup

1. Settings → About → tap Build number 7 times → Developer options.
2. Either:
   - **Classic Android TV toggle** — Developer options → **Network debugging** → on. Fixed port, default `5555`, no pairing code. Leave "Pairing port"/"Pairing code" blank in the app.
   - **Wireless debugging** (if that's what your TV/box exposes instead) — Developer options → **Wireless debugging** → on → **Pair device with pairing code**. Enter the shown pairing port + 6-digit code into this app once; the ADB connect port shown on the main Wireless debugging screen goes in "ADB port".
3. Install and open this app on the TV, set the ports, tap **ابدأ الاستقبال**.
4. First connect triggers the TV's own **"Allow debugging?"** popup — accept it (check "always allow"). This is a one-time, on-device prompt; nothing to accept on the phone.
5. On the phone, open WifiGameController, switch to **UDP mode**, enter the TV's IP (shown at the top of this app's screen) and the UDP port (default `8766`).

## Project structure

- `MainActivity` — setup screen (shows LAN IP, ports, pairing fields, start/stop, live status)
- `ReceiverService` — foreground service; owns both the local ADB/uinput connection and the UDP listener
- `BootReceiver` — restarts the service after a TV reboot
- `ReceiverApp` — registers Conscrypt (only exercised by the optional pairing path)
- `adb/TvAdbConnectionManager`, `adb/UinputGamepadClient` — unmodified logic from WifiGameController's ADB mode

## Build

```
gradle assembleDebug
```

Also builds via GitHub Actions on push to `main` (`.github/workflows/build.yml`) — grab the APK from the run's Artifacts.
