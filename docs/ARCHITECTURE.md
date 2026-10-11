# Architecture

## Privileged access

Privileged commands go through `ShellBackend`, which has two implementations:

- **Shizuku**: starts a UserService (`ShellService`) as the shell user (uid 2000) and runs `sh -c` in it over AIDL
- **Root**: runs `su -c` directly

Settings lets the user pick Auto (Shizuku first), Shizuku or Root.

## Data collection

- UI updates from BatteryManager and shell reads run in separate loops
- Each shell tick reads `dumpsys battery` and every power supply's `uevent` in a single call (`app/src/main/res/raw/collect.sh`)
- The full vendor sysfs scan runs every 15 s (`scan.sh`) and the heavy `dumpsys batterystats` every 60 s, both in the background
- Current unit (µA / mA) and sign (auto / as is / inverted) are configurable, because devices disagree on both

## Charger detection

`ACTION_POWER_CONNECTED` is not on the implicit-broadcast exemption list, so a manifest receiver never fires on Android 8+. Plug-ins are detected by:

1. a runtime-registered receiver while the process is alive (instant)
2. the UI loop while the app is open (instant)
3. a persisted JobScheduler job with the charging constraint otherwise. This fires when the system considers the device charging, roughly when the level goes up by 1%, so it lags by tens of seconds to minutes

## Notifications and widget

Both are driven by JobScheduler jobs (`ChargingJobService`). While charging, the widget job reschedules itself every 60 s, and the charging constraint stops it when unplugged.

WorkManager is not used: it enables and disables its own components depending on pending work, which fires `PACKAGE_CHANGED` and makes widgets flash back to their placeholder.

## Source layout

```
app/src/main/
├── aidl/.../IShellService.aidl          # UserService AIDL
├── java/io/github/koki_dayo39/battprobe/
│   ├── shizuku/ShizukuManager.kt        # binder, permission and service binding state
│   ├── shizuku/ShellService.kt          # UserService running as the shell user
│   ├── shell/                           # ShellBackend, RootShell, PrivilegeManager
│   ├── background/                      # plug-in receiver, notification, JobScheduler jobs
│   ├── widget/                          # home screen widget
│   ├── data/BatteryRepository.kt        # BatteryManager + shell output parsing
│   ├── data/Derived.kt                  # capacity and charger info derivation
│   ├── diag/                            # diagnostics report, known sysfs nodes
│   └── ui/                              # Compose screens (overview, details, raw, settings)
└── res/raw/collect.sh, scan.sh          # scripts run in the shell
```

## Release builds

`assembleRelease` signs with `keystore.properties` or the `SIGNING_*` environment variables when present, and produces an unsigned APK otherwise (what F-Droid builds). The Google dependency-info block is disabled. Pushing a `v*` tag runs `.github/workflows/release.yml`, which publishes a signed APK to GitHub Releases. Maintainer notes are in [RELEASING.md](RELEASING.md) (Japanese).
