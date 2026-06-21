# PanicRelay

A native Android app that turns a single press of a Bluetooth smart pepper spray into an instant, multi-channel emergency alert — calls, SMS with live GPS, Telegram, email and webhooks — to as many people as you choose.

I built it to replace the subscription-locked companion app of a Bluetooth smart pepper spray I bought for an elderly family member: one motion to defend yourself **and** call for help, on every channel that matters, with no subscription and no vendor in the middle.

> ⚠️ **Personal / educational project — not a certified safety device.**
> This is a hobby build shared for learning and portfolio purposes. It is **not** a medical-alert product and comes with **no warranty or guarantee**. Do not rely on it as someone's only line of safety.

---

## 🔒 Privacy notice (please read)

Every device-identifying or secret value in this repository is an **intentionally fictitious placeholder**:

| Item | In this repo | Reality |
|------|--------------|---------|
| Device MAC address | empty / `XX:XX:XX:XX:XX:XX` | entered by the user at runtime, on the phone only |
| Device name | `XXXXXXXX` | never committed |
| Emergency contacts / numbers | **not present** | live only in the phone's local settings at runtime |
| Telegram token, email, webhook | **not present** | configured in-app by the user |
| Signing keystore + password | throwaway sample (`changeme-dev-key`) | a disposable debug key, **not** a production/store identity |

All real configuration is stored locally on the device and is **never** part of the source code. The bundled keystore is a sample key used only so debug builds install over each other; it is not tied to any app-store identity and must not be reused for a real release.

---

## ✨ What it does

When the spray is activated, the phone instantly:

- 📞 **Calls** an emergency contact (works from a locked, sleeping screen)
- 💬 **Sends an SMS** with a live Google Maps GPS link to everyone on the list
- 🔔 **Pushes a real-time alert** to a Telegram chat/group
- 📧 **Emails** chosen recipients (SMTP)
- 🌐 **Fires a webhook** (JSON POST) into your own automations (n8n, etc.)
- 🚨 **Plays a loud local alarm**

Recipients and channels are **unlimited and fully configurable** — the opposite of "two contacts, one channel, take it or leave it."

## 🧠 How it works

1. **Bluetooth layer** — The spray is a BLE peripheral. The app connects to it by MAC, subscribes to the notify characteristic that fires the instant the trigger is pulled, and treats that signal as the "activated" event (with a short startup grace + debounce to ignore the test toggle).
2. **Foreground service** — A persistent `connectedDevice` foreground service keeps the BLE link alive, auto-reconnects, and survives battery optimization and reboots.
3. **Alert fan-out** — On activation, `AlertManager` fires every enabled channel in parallel and writes a local event log.

## 🏗️ Build (no IDE required)

The whole app is compiled in the cloud via **GitHub Actions** — no Android Studio needed:

1. Push to your fork, or run the workflow manually (**Actions → Build PanicRelay APK → Run workflow**).
2. Download the `PanicRelay-debug` artifact.
3. Sideload the APK on your phone.

## ⚙️ Setup

In the app, enter your **own** values:

- the **MAC address** of your BLE device
- emergency **contacts**
- the alert **message**
- any channels you want: Telegram (token + chat id), email (SMTP), webhook URL

Then grant the requested permissions (Bluetooth, SMS, Call, Location → *Allow all the time*), disable battery optimization for the app, and start the service.

## 📂 Project structure

```
app/src/main/java/com/marco/panicrelay/
├── MainActivity.kt      # configuration UI + control buttons
├── PanicService.kt      # foreground BLE service (connect, detect, trigger)
├── AlertManager.kt      # fan-out: call, SMS, Telegram, email, webhook, alarm, log
├── Prefs.kt             # local settings wrapper
└── BootReceiver.kt      # restart service after reboot
.github/workflows/build.yml   # cloud build pipeline
```

## 🧩 Engineering notes

Most of the work wasn't the idea — it was modern Android constraints: keeping a BLE link alive in the background, launching a call from a locked screen on Android 14/15, surviving aggressive battery "optimization", restarting after reboot, reading location with the screen off, and building + signing entirely in CI.

## 📜 License

Personal project shared as-is. Use at your own risk.
