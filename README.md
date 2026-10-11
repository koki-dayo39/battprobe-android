# BattProbe

**English** | [日本語](README.ja.md)

BattProbe is an Android app that shows what your battery and charger are really doing, from the basics down to the raw kernel nodes, refreshed every second.

It works without any special permission. Grant it [Shizuku](https://shizuku.rikka.app/) or root access and it reads much deeper: the negotiated charger power, USB PD details, capacity health and every `/sys/class/power_supply` node your device exposes.

## Features

| | What you see | Needs Shizuku / root |
|---|---|---|
| Overview | Level, charging state, power source (AC / USB / wireless), time to full | No |
| Power | Current, voltage, power (W), average current, live power graph | No |
| Charger | Negotiated max current / voltage, charger-side voltage, current and input power, charger type (SDP / DCP / PD…), USB-C mode, charge phase (fast / taper / trickle) | Yes |
| Health | Temperature, health, cycle count, design capacity vs. learned full capacity, capacity health % | Mostly |
| Pixel | Battery Defender, adaptive charging, charge stage, per-band cycle histogram and other Pixel-specific nodes | Yes |
| Details & Raw | Every BatteryManager value, full `dumpsys battery`, all `power_supply` files, a `batterystats` excerpt, copyable raw text | Yes |
| Unknown nodes | A list of sysfs entries the app does not understand yet, ready to copy and report | Yes |
| Widget | Negotiated "V × A = W", USB type, measured charger power and battery current. Tap to refresh | For values |
| Charger notification | Shows the negotiated power when you plug in, for 30 seconds or for as long as you are charging | For values |
| Diagnostics | Share or save a report with device info, raw data and the in-app log | No |

What a device reports varies a lot between manufacturers. If something looks wrong or missing on yours, please [open an issue](https://github.com/koki-dayo39/battprobe-android/issues) and attach a diagnostics report (Settings → Diagnostics).

## Privacy

- No internet permission, so nothing leaves your device
- No analytics, no ads, no tracking
- Diagnostics reports are only created when you ask for one, and you choose where they go

## Requirements

- Android 8.0 or newer
- Optional, for the deeper data: [Shizuku](https://shizuku.rikka.app/) 11+ (Sui works too) or root (Magisk, KernelSU, …)

## Download

- [GitHub Releases](https://github.com/koki-dayo39/battprobe-android/releases)
- IzzyOnDroid and F-Droid: coming soon

The F-Droid build is signed by F-Droid, while the GitHub Releases and IzzyOnDroid builds are signed by the developer. Android won't update one with the other, so to switch you need to uninstall first.

## Getting started

1. Install BattProbe and open it. The basic values appear right away
2. For the deeper data, start Shizuku (via wireless debugging or root), or have root ready
3. Tap **Grant permission** on the Shizuku card (or **Use root**)
4. Once connected, the Charger, Health, Details and Raw sections fill in

If the current is shown with the wrong sign or scale on your device, you can change the unit (µA / mA) and sign in Settings.

## Building

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # minified, unsigned unless keystore.properties exists
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the app is put together.

## License

Copyright (C) 2026 koki-dayo39

BattProbe is free software, released under the [GNU General Public License v3.0 or later](LICENSE).
