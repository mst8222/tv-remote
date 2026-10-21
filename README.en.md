# TV Remote

English | [中文](README.md)

Control an Android TV box from your phone. **No root, no developer options, no wireless debugging**:
install a small always-on service on the box, keep both devices on the same LAN, and the phone can
control it. The service comes back automatically after a reboot. If the box app is not installed,
the phone can still work as a Bluetooth HID remote.

- Controller: Android phone (developed and tested on Samsung Note20, Android 13)
- Target: Android TV box (tested on H96 Max V56 / Android 12 and Samsung Fold3 / Android 13)
- Both APKs are built by a small manual toolchain, with no third-party dependencies (about 100–120 KB each)

---

## What it is for

Turn a phone into a full-featured remote for an Android TV box:

1. **The remote is always missing** — D-pad, OK, Back, Home, volume, mute and power are all on the phone.
2. **Typing on a TV is painful** — type a search term or account password on the phone and send it
   straight into the box's focused input field.
3. **Apps are buried in menus** — every launchable third-party app on the box (OK影视, 飞牛TV,
   Dangbei Desktop, Kodi, ...) plus system Settings is one tap away.

It also manages several boxes: scan or add devices manually, and each device remembers its own
pairing and connection mode.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-remote-en.png" width="250"><br>Phone app (English)</td>
    <td align="center"><img src="docs/screenshots/phone-remote-zh.png" width="250"><br>Phone app (Chinese)</td>
    <td align="center"><img src="docs/screenshots/box-server-zh.png" width="460"><br>Box service (pair code / address / accessibility status)</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-devices-zh.png" width="250"><br>Device picker / delete</td>
    <td align="center"><img src="docs/screenshots/phone-scan-zh.png" width="250"><br>LAN auto-discovery</td>
    <td align="center">The box UI follows the box system language; the phone app can switch via "中/EN".</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-apps-en.png" width="250"><br>App shortcuts (English UI)</td>
    <td align="center"><img src="docs/screenshots/phone-apps-zh.png" width="250"><br>App shortcuts (Chinese UI)</td>
    <td align="center">Labels come from the box system language, sorted by name; Settings is always first.</td>
  </tr>
</table>

## Two connection modes

| | LAN direct (recommended) | Bluetooth HID (fallback) |
|---|---|---|
| How it works | Phone connects over TCP to the box service | Phone registers as a Bluetooth keyboard/remote |
| Requirement | Box app installed + accessibility enabled | Phone and box paired over Bluetooth |
| D-pad / OK / Back / Home / Menu / Power | Yes | Yes |
| Volume / Mute | Yes | Yes |
| Text input | Yes | No |
| App shortcuts | Yes | No |
| Auto discovery | Yes (UDP broadcast) | Pair via system Bluetooth settings |
| Best for | Daily use; full features; survives reboots | Box without the app, or temporary use |

> In direct mode, keys and text are injected through the box accessibility service; app launch and
> volume do not need accessibility. In Bluetooth mode the phone app must stay in the foreground
> (Android unregisters HID apps in the background; returning to the foreground re-registers it).

## Architecture

```
┌──────────────────────────┐                       ┌────────────────────────────────┐
│ Phone: TV Remote.apk     │      TCP 8899         │ Box: TV Remote Service.apk      │
│                          │ ────────────────────► │                                │
│  · D-pad / OK            │   PAIR / KEY / TEXT   │  ServerService (foreground)     │
│  · Back / Home / Menu    │   APP / LIST / PING   │   ├ TCP 8899 listener           │
│  · Volume / Mute / Power │ ◄──────────────────── │   ├ UDP 8898 broadcast / 3 s    │
│  · Text / App shortcuts  │                       │   ├ BootReceiver (auto start)   │
│  · Scan / switch devices │      UDP 8898         │   └ Accessibility service       │
│                          │ ◄──── broadcast ───── │      (root-free injection)      │
└──────────────────────────┘                       └────────────────────────────────┘
          │
          └── Bluetooth HID ─────► box Bluetooth stack (box app not required)
```

## Requirements

### Android versions

| Component | Minimum | Verified |
|---|---|---|
| Phone · LAN direct | Android 8.0 (API 26) | Android 13 (Samsung Note20) |
| Phone · Bluetooth HID | Android 9.0 (API 28) | Android 13 (Samsung Note20) |
| Box app | Android 8.0 (API 26) | Android 12 (H96 Max V56), Android 13 (Samsung Fold3) |

- Bluetooth HID relies on `BluetoothHidDevice` (API 28+). Phones below Android 9 can only use LAN direct.
- If a device has no usable Bluetooth adapter, the app reports that Bluetooth remote is not supported;
  LAN direct is unaffected.

### Box requirements

1. An Android TV box or Android TV that allows sideloading APKs. Non-Android platforms
   (webOS, Tizen, conventional TVs) are not supported.
2. Install "TV Remote Service" and **enable its accessibility service manually**:
   Box Settings → Accessibility → TV Remote Service.
   Without it, key/text commands return `ERR a11y`; volume, app launch and app list still work.
3. **Root is not required.** The box side uses `AccessibilityService`, `AudioManager` and `PackageManager`.
4. Keep the box on the network. The service runs as a foreground service with a notification and is
   started on boot; on firmware that restricts background startup, allow the app to auto-start.
5. Some box firmware does not respond to touch (H96 in our tests). Use the box's own remote to focus
   and press OK, or open system settings over ADB.

### Network requirements

- Phone and box must be on the **same LAN / subnet**.
- Direct mode uses **TCP 8899** (control) and **UDP 8898** (discovery broadcast).
- If the router enables **AP isolation**, auto-discovery fails; add the box IP manually instead.
- A static DHCP lease for the box is recommended. Changing the IP requires adding the device again.

### Out of scope

- Controlling the TV's own power/volume (IR / HDMI-CEC): the Power key locks the box, and volume
  changes the box media volume.
- WAN or cross-subnet control. The protocol is plaintext and meant for a trusted LAN only.
- iOS clients.

## Quick start

### 1. Box side

1. Download `TVRemoteServer.apk` from Releases and install it (USB drive or `adb install`).
2. Open "TV Remote Service" and allow notifications. The screen shows the **6-digit pair code**
   and the box IP.
3. Open box Settings → Accessibility and enable "TV Remote Service".
4. Leave the service running; it starts automatically on boot/reboot.

> The pair code is generated on first launch and stored in the app's private storage. Clearing app
> data or reinstalling generates a new code.

### 2. Phone · LAN direct

1. Download `TVRemote.apk` from Releases and install it; connect the phone to the same Wi-Fi as the box.
2. Open the app. Tap "Scan" and pick the box in the result list, or tap "Add" and enter a name and IP.
3. Tap "Direct", enter the 6-digit code shown on the box, then "Pair and connect".
4. The remote is ready. Pairing is stored per box IP and restored automatically next time.

> The device list ships with two sample entries (荐片TV · RK3566, 三星 Fold3).
> Tap the device name → "Delete current" to replace them with your own box; at least one entry
> is always kept.

### 3. Phone · Bluetooth HID (optional)

1. Enable Bluetooth on the phone; on the box, open Bluetooth and start searching for devices.
2. Tap "BT" in the app. If no device is paired yet, tap "Let the box find this phone"
   (makes the phone discoverable for 120 seconds).
3. Select the phone in the box Bluetooth list and confirm the pairing code on both sides.
4. Return to the app, tap "BT" again and choose the paired box. Wait for "Bluetooth connected".

## Daily use

| Action | Description |
|---|---|
| Tap the device name | Switch device or delete the current one |
| Scan | Listen for box UDP broadcasts, list all boxes on the LAN, tap one to add and connect |
| Add | Enter a device name and box IP manually |
| Direct | Opens the pairing dialog if not paired; otherwise enters direct mode |
| Long-press "Direct" | Clear the current device's pairing (after a box reset or box swap) |
| BT | Register HID and connect the paired box; switching devices reconnects that device's target |
| 中/EN | Chinese / English / follow system, applied immediately and persisted |
| D-pad / OK | Uses system DPAD global actions; falls back to focus traversal on firmware that returns false |
| Back / Home | System global Back / Home |
| Menu | Opens recent tasks (no global Menu action without root) |
| Power | Locks the box screen (not a real power off) |
| Volume / Mute | Adjusts box media volume |
| Text input | Writes into the focused editable field; fails if the current screen has none |
| App shortcuts | Dynamically lists every launchable third-party app on the box (name + first-letter icon) |
| Check for updates | Phone: "About" card; box: "Version update" card. Reads the latest GitHub Release, downloads the APK, and the system installer confirms |

### App shortcut rules

The phone requests the box's app list via `LIST`; the payload is
`package|base64(label)` per entry. The UI renders every entry sorted by label,
with Settings (`com.android.settings`) pinned first (system app, always shown).

- Labels come from `PackageManager.getApplicationLabel` in the box's system language;
- only third-party apps with a LAUNCHER / LEANBACK_LAUNCHER entry are listed;
- icons are not transferred; the first character of the label is shown;
- text input and app shortcuts are available only in direct mode.
- listing relies on the `<queries>` declaration in the box app manifest (Android 11+ package visibility).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "Connection refused: box service not running" | Open "TV Remote Service" on the box |
| "Connection timed out / failed" | Box offline, IP changed, or devices are not on the same Wi-Fi |
| "Wrong pairing code" | Re-enter the 6 digits shown on the box; clearing app data changes the code |
| "Pairing expired" | Long-press "Direct" to clear the pairing, then pair again |
| "Accessibility service is off on the box" | Enable "TV Remote Service" in box Settings → Accessibility |
| "The box could not perform it" | No focused control/input on the current screen; open the target screen first |
| Scan finds nothing | Same Wi-Fi, box service running, AP isolation off; or add the IP manually |
| D-pad does nothing | The screen has no focusable element (e.g. full-screen player); some firmware only supports focus traversal |
| Bluetooth keys do nothing | Keep the app in the foreground, tap "BT" again, confirm "Bluetooth connected" |
| Cannot connect after a box reboot | Check auto-start; some firmware needs an explicit auto-start permission or reopening the app |

## Build

Requirements:

- Python 3
- JDK 11+ (`javac` on PATH; JDK 21 verified)
- Android SDK: `build-tools/36.1.0`, `platforms/android-35`

```powershell
python build.py app       # -> dist/TVRemote.apk
python build.py server    # -> dist/TVRemoteServer.apk
```

`build.py` looks for the SDK under `%LOCALAPPDATA%\Android\Sdk`. The chain is
aapt2 → javac → d8 → dex packaging → zipalign → apksigner.

### Signing key

`keys/` is not committed (`.gitignore`). If `keys/release.jks` is lost, existing installations
cannot be upgraded in place and must be uninstalled first. Back this file up.

## Direct protocol

Box TCP 8899, one command per line terminated by `\n`.

| Send | Reply | Meaning |
|---|---|---|
| `PING` | `PONG` | Liveness check, no auth |
| `PAIR <6-digit code>` | `OK <token>` / `ERR badcode` | Exchange the pair code for a long-lived token |
| `KEY <token> <keycode>` | `OK` / `ERR auth` / `ERR a11y` / `ERR cmd` / `ERR exec` | Inject a key |
| `TEXT <token> <base64>` | `OK` / `ERR a11y` / `ERR exec` | Write UTF-8 text (base64 encoded) into the focused field |
| `APP <token> <package>` | `OK` / `ERR exec` | Launch an app; accessibility not required |
| `LIST <token>` | `OK <package\|base64(label), comma-separated>` | Launchable third-party apps; accessibility not required |

UDP 8898 broadcast payload: `TVREMOTE|8899|<box model>`, sent every 3 seconds for auto-discovery.

## Project layout

```
tv-remote/
├── app/                    # Phone app, com.koco.tvremote
│   ├── AndroidManifest.xml
│   ├── res/                # Icons + Chinese/English strings (default zh, values-en for English)
│   └── src/com/koco/tvremote/
│       ├── MainActivity.java   # UI and interaction, built in code (no layout XML)
│       ├── LanClient.java      # Direct client (TCP + UDP discovery)
│       ├── BtHid.java          # Bluetooth HID keyboard / consumer control
│       └── Errors.java         # Localized error mapping
├── server/                 # Box app, com.koco.tvremoteserver
│   ├── AndroidManifest.xml
│   ├── res/                # Icons + UI/notification strings
│   └── src/com/koco/tvremoteserver/
│       ├── MainActivity.java                  # Pair code / address / accessibility status
│       ├── ServerService.java                 # Foreground service, TCP 8899 + UDP 8898
│       ├── TvRemoteAccessibilityService.java  # Root-free key/text injection
│       └── BootReceiver.java                  # Start on boot
├── docs/screenshots/       # README screenshots
├── tools/                  # PC-side Java protocol prototypes (ADB / TLS)
├── build.py                # One-command build
├── keys/                   # Signing keys (not committed)
└── dist/                   # Build output (not committed)
```

## Security

- The direct protocol is **plaintext TCP** and intended for a trusted LAN only. Do not expose port
  8899 to the Internet.
- The pair code is only used to obtain a token; subsequent commands require the token.
- The box service only accepts remote-control commands; it provides no file access or arbitrary
  command execution.

## Known limitations

- "Power" locks the screen; it cannot really power the box on/off. "Menu" opens recent tasks.
- Volume changes only the box media volume, not the TV or an AV receiver.
- Text input requires an editable field on the current screen and a box IME that accepts `ACTION_SET_TEXT`.
- Some TV firmware does not support global DPAD actions (H96 / Android 12 in our tests); the app
  falls back to focus traversal, so direction keys do nothing on screens without focusable controls.
- Bluetooth HID does not support text input or app shortcuts and needs the app in the foreground.
- App shortcuts cover third-party apps with a LAUNCHER / LEANBACK_LAUNCHER entry; pure service apps
  and apps without a launcher entry are not listed.
- `LIST` relies on the `<queries>` declaration in the box app manifest (Android 11+ package visibility).
- In-app updates need access to `api.github.com`; the anonymous API limit is 60 requests per hour,
  and the app reports a retry-later error when exceeded.
- Updates always go through the system install confirmation (no silent install). Some Android
  versions additionally require allowing this app to install unknown apps; on the box the
  installer dialog must be confirmed with the physical remote.

## Versions

- v1.4.0: in-app update check on both apps; reads the latest GitHub Release, downloads the
  matching APK and lets the system installer complete the upgrade. The box service restarts
  automatically after the update.
- v1.3.0: dynamic app shortcuts; `LIST` also returns app labels (`package|base64(label)`) and the
  phone renders every launchable third-party app on the box; the box app declares `<queries>` for
  Android 11+ package visibility.
- v1.2: root-free box control through the accessibility service; connection mode and Bluetooth
  target remembered per device; Bluetooth Back/Home switched to consumer control
  (`AC Back` / `AC Home`).
- v1.1: removed phone-side ADB control; kept "Direct + Bluetooth".

## License

Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). Personal study, research,
testing, and other noncommercial use are permitted; **commercial use is prohibited**.
Redistributions and modified works must retain this license and copyright notice.

Questions and bug reports: please open a GitHub Issue.
