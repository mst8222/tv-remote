<div align="center">

<img src="app/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="TV Remote">

# TV Remote

**Turn your phone into a remote for an Android TV box**<br>
No root · No developer options · No wireless debugging

<p>
  <a href="./README.md">简体中文</a> |
  <strong>English</strong>
</p>

<p>
  <a href="https://github.com/mst8222/tv-remote/releases/latest">
    <img src="https://img.shields.io/github/v/release/mst8222/tv-remote?color=brightgreen&include_prereleases" alt="release">
  </a><!--
  --><a href="https://gitee.com/moston/tv-remote/releases">
    <img src="https://img.shields.io/badge/Gitee-mirror-C71D23" alt="Gitee mirror">
  </a><!--
  --><a href="./LICENSE">
    <img src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue" alt="license">
  </a><!--
  --><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/root-not%20required-brightgreen" alt="no root">
</p>

<p>
  <a href="#overview">Overview</a> •
  <a href="#features">Features</a> •
  <a href="#screenshots">Screenshots</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#troubleshooting">Troubleshooting</a>
</p>

</div>

## Overview

Install a small always-on service on the box, keep both devices on the same LAN, and the phone can
control it. The service comes back automatically after a reboot. If the box app is not installed,
the phone can still work as a Bluetooth HID remote.

> [!NOTE]
> The direct protocol is **plaintext TCP** over the LAN. It is meant for a trusted home or office
> network only — never expose port 8899 to the Internet.

| | |
|---|---|
| Controller | Android phone (developed and tested on Samsung Note20 / Android 13) |
| Target | Android TV box (tested on H96 Max V56 / Android 12 and Samsung Fold3 / Android 13) |
| Prerequisites | Phone and box on the same LAN; accessibility enabled on the box (no root) |
| Build | Manual toolchain, no third-party dependencies, about 100–130 KB per APK |

It solves three everyday problems:

1. **The remote is always missing** — D-pad, OK, Back, Home, volume, mute and power are all on the phone.
2. **Typing on a TV is painful** — type a search term or account password on the phone and send it
   straight into the box's focused input field.
3. **Apps are buried in menus** — every launchable third-party app on the box (OK影视, 飞牛TV,
   Dangbei Desktop, Kodi, ...) plus system Settings is one tap away.

It also manages several boxes: scan or add devices manually, and each device remembers its own
pairing and connection mode.

## Features

| Feature | Description |
|---|---|
| Full keypad | D-pad, OK, Back, Home, Menu, Power, volume, mute |
| Text input | Type on the phone and write into the box's focused field |
| App shortcuts | Dynamically lists every launchable third-party app with its real box icon; Settings is always first |
| Air mouse | BT HID mode: tilt the phone to move the box cursor, OK acts as left click |
| Touchpad | Direct mode with D-pad / Touch / Mouse segments: Touch maps your finger onto the box screen (tap exactly where you point), Mouse drags the box cursor; pick by box firmware capability |
| OK long-press | BT mode keeps sending key-down and lets the TV app decide; direct mode sends a long-press command on release after 500 ms |
| Multi-device | LAN scan, manual add, switch and delete; pairing and connection mode remembered per device |
| Auto channel switch | Tapping text input, app shortcuts or the touchpad in BT mode switches to direct and runs it; tapping the air mouse in direct mode switches to BT; pairing/connecting continues the original action afterwards |
| No root | Box side uses the accessibility service, `AudioManager` and `PackageManager` |
| Auto start | Foreground service with a notification, started again on boot/reboot |
| Bilingual UI | Phone: Chinese / English / follow system; box follows the box system language |
| In-app update | Both apps query the latest GitHub and Gitee releases, prompt when either is newer, and prefer Gitee for the download |
| APK push install | Android phone picks a local APK and streams it to the box for install; any phone/browser can send an APK download link and the box installs it automatically after download |

### App shortcut rules

The phone requests the box's app list via `LIST`; the payload is `package|base64(label)` per entry.
The UI renders every entry sorted by label, with Settings (`com.android.settings`, a system app)
pinned first and always shown.

- Labels come from `PackageManager.getApplicationLabel` in the box's system language;
- only third-party apps with a LAUNCHER / LEANBACK_LAUNCHER entry are listed;
- icons are fetched on demand via `ICON <token> <package>` (144x144 PNG, Base64): the
  first character is shown first, then replaced by the real icon and cached in memory
  (keyed by box IP + package); failures keep the placeholder;
- text input and app shortcuts are available only in direct mode.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-remote-en.png" width="250"><br>Phone app (English)</td>
    <td align="center"><img src="docs/screenshots/phone-remote-zh.png" width="250"><br>Phone app (Chinese)</td>
    <td align="center"><img src="docs/screenshots/box-server-zh.png" width="460"><br>Box service (status / pair code / address / QR code)</td>
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

## Cross-platform capability matrix

The box also ships a built-in web remote: open `http://<box-ip>:17880` in any browser on the same
LAN, with nothing to install. The box screen shows a QR code for that address with the pairing code embedded, so a phone
camera opens it and pairs automatically — no address or code to type. After trusting the self-signed certificate,
`https://<box-ip>:17843` adds install-to-home-screen and an offline shell.
iOS / iPadOS Safari has no install button: use Share → Add to Home Screen (the page shows
this hint at the top). macOS Safari 14+ uses File → Add to Dock.

The web remote also manages multiple boxes: paired boxes are remembered in browser storage and can be
switched from the device bar at the top. On an HTTPS page, "Scan to add" reads another box's QR code;
manual address and pair code entry is also available. Any entry can be renamed or deleted. The page
offers a D-pad / Touch / Mouse switch and no longer provides an air mouse.
(Camera scanning needs a secure context; HTTP pages only offer manual add.)

| Client | LAN direct | Bluetooth HID | Touchpad |
|---|---|---|---|
| Android native app | Yes (TCP 8899) | Yes (API 28+) | BT gyroscope air mouse; direct-mode Touch / Mouse |
| Browser (Android / iOS / HarmonyOS NEXT / PC) | Yes (17880 / 17843) | Not provided | Direct-mode Touch / Mouse |
| Native iOS / HarmonyOS NEXT app | Not provided; use the browser | Not provided | Not provided |

> Browsers have no HID device API, and Web Bluetooth is GATT-client only, so the web page
> deliberately has no Bluetooth entry. Without Bluetooth, use the Android app's BT mode or a 2.4G remote.

## How it works

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

| Component | Port / mechanism | Role |
|---|---|---|
| `ServerService` | TCP 8899 | Handles `PING` / `PAIR` / `KEY` / `KEYLONG` / `TEXT` / `APP` / `LIST` / `ICON` |
| Discovery | UDP 8898 | Box broadcasts `TVREMOTE\|8899\|<model>` every 3 seconds |
| `TvRemoteAccessibilityService` | Android accessibility API | Global keys, focus traversal, text input, long-press injection |
| `BootReceiver` | Boot broadcast | Restarts the service after a power cycle |
| `BtHid` | Bluetooth HID | Keyboard, consumer control and mouse (air mouse) reports |

## Quick start

### Requirements

| Component | Minimum | Verified |
|---|---|---|
| Phone · LAN direct | Android 8.0 (API 26) | Android 13 (Samsung Note20) |
| Phone · Bluetooth HID | Android 9.0 (API 28) | Android 13 (Samsung Note20) |
| Box app · Standard build | Android 8.0 (API 26) | Android 12 (H96 Max V56), Android 13 (Samsung Fold3) |
| Box app · Legacy build | Android 5.1 (API 22) | Android 5.1.1 (Redmi TV MiTV-ANTL0) |

> Both box APKs share the same package name and version code; install the one that matches the
> box OS. The legacy build cannot use absolute touch (synthesized gestures) on API 22 firmware.

Box requirements:

1. An Android TV box or Android TV that allows sideloading APKs. Non-Android platforms
   (webOS, Tizen, conventional TVs) are not supported.
2. Install "TV Remote Service" and **enable its accessibility service manually**:
   Box Settings → Accessibility → TV Remote Service.
   Without it, key/text commands return `ERR a11y`; volume, app launch and app list still work.
   When "Accessibility service" is off on the box screen, a "Turn on" button appears at the end of
   that row: focus it with the remote and press OK to open the system accessibility list, then press
   OK once more on the switch.
   For apps that only react to real key events (e.g. cloud TV players), switch "Injection"
   to ADB on the box: enable ADB / USB debugging once in Developer options, then press OK
   on the box screen (the TV asks once to allow the connection).
3. **Root is not required.** The box side uses `AccessibilityService`, `AudioManager` and `PackageManager`.
4. Keep the box on the network. The service runs as a foreground service with a notification and is
   started on boot; on firmware that restricts background startup, allow the app to auto-start.
5. Some box firmware does not respond to touch (H96 in our tests). Use the box's own remote to focus
   and press OK, or open system settings over ADB. Focus starts on "Check for updates", the D-pad
   moves row by row, and the "Turn on" buttons at the end of a row can be focused as well.

Network requirements:

- Phone and box must be on the **same LAN / subnet**.
- Direct mode uses **TCP 8899** (control) and **UDP 8898** (discovery broadcast).
- If the router enables **AP isolation**, auto-discovery fails; add the box IP manually instead.
- A static DHCP lease for the box is recommended. Changing the IP requires adding the device again.

### 1. Box side

1. Install the APK that matches the box OS (USB drive or `adb install`):
   - Android 8.0 and above: `dist/TVRemoteServer.apk` (standard);
   - Android 5.1: `dist/TVRemoteServer-android5.apk` (legacy).
2. Open "TV Remote Service" and allow notifications. The screen shows the **6-digit pair code**,
   the box IP and a QR code.
3. Open box Settings → Accessibility and enable "TV Remote Service".
4. Leave the service running; it starts automatically on boot/reboot.

> The pair code is generated on first launch and stored in the app's private storage. Clearing app
> data or reinstalling generates a new code.

### 2. Phone · LAN direct

1. Install `dist/TVRemote.apk`; connect the phone to the same Wi-Fi as the box.
2. Open the app. Tap "Scan" and pick the box in the result list, or tap "Add" and enter a name and IP.
3. Tap "Direct"; in the dialog you can tap "Scan" to read the QR code on the box screen
   (IP and pairing code are filled automatically), or type the 6-digit code and tap "Pair and connect".
4. The remote is ready. Pairing is stored per box IP and restored automatically next time.

> The device list ships with two sample entries (荐片TV · RK3566, 三星 Fold3).
> Tap the device name → "Delete current" to replace them with your own box; at least one entry
> is always kept.

### 3. Phone · Bluetooth HID (optional)

<details>
<summary><strong>Show Bluetooth pairing steps</strong></summary>

1. Enable Bluetooth on the phone; on the box, open Bluetooth and stay on the device / pairing page
   (keep it discoverable).
2. Tap "BT" in the app, then "Scan nearby devices" and pick the box; confirm the pairing code on
   both sides and the phone connects automatically. If the box cannot see the phone, tap
   "Let the box find this phone" (discoverable for 120 seconds).
3. Alternatively, pair from the box side as before: select this phone in the box Bluetooth list.
4. Once "Bluetooth connected" is shown the remote works; the connection mode is remembered per device.

</details>

## Daily use

| Action | Description |
|---|---|
| Tap the device name | Switch device or delete the current one |
| Scan | Listen for box UDP broadcasts, list all boxes on the LAN, tap one to add and connect |
| Add | Enter a device name and box IP manually |
| Direct | Opens the pairing dialog if not paired; otherwise enters direct mode |
| Long-press "Direct" | Clear the current device's pairing (after a box reset or box swap) |
| BT | Register HID and connect the paired box; switching devices reconnects that device's target |
| Wrong mode, no problem | When a button needs the other channel the app switches automatically: text / app shortcuts / touchpad go direct, the air mouse goes BT; if pairing or device selection is needed, the original action continues afterwards |
| 中/EN | Chinese / English / follow system, applied immediately and persisted |
| D-pad / OK | Uses system DPAD global actions; falls back to focus traversal on firmware that returns false |
| OK short / long press | In BT mode the box keeps receiving "key down" while held, so TV apps judge long-press themselves; in direct mode a hold of 500 ms or more sends a long-press command on release |
| Air mouse (↖, top-right) | BT mode only: tilt the phone to move the box cursor, OK acts as left click; tap again to disable |
| Touchpad (segments above the D-pad) | Direct mode only, D-pad / Touch / Mouse. Touch: your finger maps to the same box screen position (no cursor); tap to click, hold 500 ms to long-press, single-finger swipe to scroll. Mouse: drag to move the box cursor, tap to click, hold to long-press, two-finger swipe to scroll |
| Back / Home | System global Back / Home |
| Menu | Opens recent tasks (no global Menu action without root) |
| Power | Locks the box screen (not a real power off) |
| Volume / Mute | Adjusts box media volume |
| Text input | Writes into the focused editable field; fails if the current screen has none |
| App shortcuts | Dynamically lists every launchable third-party app on the box (name + real icon) |
| Install APK (file) | Android app "Install APK" card picks a local APK, streams it to the box over HTTP, verifies the ZIP magic number, then launches the system installer |
| Install APK (link) | Android app or web remote enters an APK download link; the box downloads it in the background and launches the system installer automatically; works on iOS / HarmonyOS / any browser |
| Check for updates | Phone: "About" card; box: "Version update" card. Queries GitHub and Gitee, takes the higher version, downloads from Gitee first and falls back to GitHub; the system installer confirms |

## Direct protocol

Box TCP 8899, one command per line terminated by `\n`.
The touchpad Mouse mode uses `MOVE` / `MCLICK` / `MTAP` / `MLONG` / `SCROLL` (relative cursor);
Touch mode uses `MTAPN` / `MLONGN` / `MSWIPEN` (absolute coordinates).

| Send | Reply | Meaning |
|---|---|---|
| `PING` | `PONG` | Liveness check, no auth |
| `PAIR <6-digit code>` | `OK <token>` / `ERR badcode` | Exchange the pair code for a long-lived token |
| `KEY <token> <keycode>` | `OK` / `ERR auth` / `ERR a11y` / `ERR adb` / `ERR cmd` / `ERR exec` | Inject a key; ADB mode sends a real key event |
| `KEYLONG <token> <keycode>` | `OK` / `ERR auth` / `ERR a11y` / `ERR adb` / `ERR cmd` / `ERR exec` | Long-press injection (keycode 23/OK only): `ACTION_LONG_CLICK` when the focused node supports it, otherwise a synthetic 600 ms gesture at the focus center |
| `TEXT <token> <base64>` | `OK` / `ERR a11y` / `ERR adb` / `ERR exec` | Write UTF-8 text (base64 encoded) into the focused field; ADB mode uses `input text` for plain ASCII |
| `APP <token> <package>` | `OK` / `ERR exec` | Launch an app; accessibility not required |
| `LIST <token>` | `OK <package\|base64(label), comma-separated>` | Launchable third-party apps; accessibility not required |
| `ICON <token> <package>` | `OK <base64(PNG)>` / `ERR arg` | App icon (144x144 PNG); accessibility not required; unknown package returns `ERR arg` |
| `APKURL <token> <base64(url)>` | `OK` / `ERR auth` / `ERR arg` | Send an APK download link; the box downloads it asynchronously and launches the installer; only http/https schemes allowed |
| `MOVE <token> <dx> <dy>` | `OK` / `ERR arg` / `ERR a11y` | Move the box cursor relatively; each axis -127..127 |
| `MCLICK <token> <0\|1>` | `OK` / `ERR arg` / `ERR a11y` | Cursor press / release (0 releases, 1 presses) |
| `MTAP <token>` | `OK` / `ERR a11y` | Tap at the cursor (node first, gesture fallback) |
| `MLONG <token>` | `OK` / `ERR a11y` | Long-press at the cursor |
| `SCROLL <token> <steps>` | `OK` / `ERR arg` / `ERR a11y` | Two-finger scroll; positive scrolls down, range -5..5 |
| `MTAPN <token> <x> <y>` | `OK` / `ERR arg` / `ERR a11y` | Absolute tap; x/y are normalized screen coordinates 0..10000 |
| `MLONGN <token> <x> <y>` | `OK` / `ERR arg` / `ERR a11y` | Absolute long-press; same coordinates as `MTAPN` |
| `MSWIPEN <token> <x1> <y1> <x2> <y2> <ms>` | `OK` / `ERR arg` / `ERR a11y` | Absolute swipe; coordinates 0..10000, duration 50..1500 ms |

UDP 8898 broadcast payload: `TVREMOTE|8899|<box model>`, sent every 3 seconds for auto-discovery.

## Tests

```powershell
powershell -File tests\protocol\run.ps1
```

Automated regression: 250 cases (163 protocol + 71 web API + 16 QR); expected output
`通过 250，失败 0` (250 passed, 0 failed).
Port 8898 must be free. If a real box is broadcasting on the LAN, the `discover_no_broadcast_null`
case can fail due to that real broadcast; this is environment noise.

## Project layout

```
tv-remote/
├── app/                    # Phone app, com.koco.tvremote
│   ├── AndroidManifest.xml
│   ├── res/                # Icons + Chinese/English strings (default zh, values-en for English)
│   └── src/com/koco/tvremote/
│       ├── MainActivity.java   # UI and interaction, built in code (no layout XML)
│       ├── LanClient.java      # Direct client (TCP + UDP discovery)
│       ├── BtHid.java          # Bluetooth HID keyboard / consumer control / mouse (air mouse)
│       └── Errors.java         # Localized error mapping
├── server/                 # Box app, com.koco.tvremoteserver
│   ├── AndroidManifest.xml
│   ├── res/                # Icons + UI/notification strings
│   └── src/com/koco.tvremoteserver/
│       ├── MainActivity.java                  # Pair code / address / accessibility status
│       ├── ServerService.java                 # Foreground service, TCP 8899 + UDP 8898
│       ├── TvRemoteAccessibilityService.java  # Root-free key/text injection
│       └── BootReceiver.java                  # Start on boot
├── docs/screenshots/       # README screenshots
├── spec/                   # Requirements and specs (planned / developing / implemented / discarded)
├── tests/                  # protocol regression + manual acceptance checklists
├── tools/                  # PC-side Java protocol prototypes (ADB / TLS)
├── build.py                # One-command build
├── keys/                   # Signing keys (not committed)
└── dist/                   # Build output (not committed)
```

## Build

Requirements:

- Python 3
- JDK 11+ (`javac` on PATH; JDK 21 verified)
- Android SDK: `build-tools/36.1.0`, `platforms/android-35`

```powershell
python build.py app       # -> dist/TVRemote.apk (API 26)
python build.py server    # -> dist/TVRemoteServer.apk (standard, API 26)
                          # -> dist/TVRemoteServer-android5.apk (legacy, API 22)
```

`build.py` looks for the SDK under `%LOCALAPPDATA%\Android\Sdk`. The chain is
aapt2 → javac → d8 → dex packaging → zipalign → apksigner.
Both box APKs are uploaded to the same release; in-app updates pick the asset that matches the OS.

> [!WARNING]
> `keys/` is not committed (`.gitignore`). If `keys/release.jks` is lost, existing installations
> cannot be upgraded in place and must be uninstalled first. Back this file up.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "Connection refused: box service not running" | Open "TV Remote Service" on the box |
| "Connection timed out / failed" | Box offline, IP changed, or devices are not on the same Wi-Fi |
| "Wrong pairing code" | Re-enter the 6 digits shown on the box; clearing app data changes the code |
| "Pairing expired" | Long-press "Direct" to clear the pairing, then pair again |
| "Accessibility service is off on the box" | Enable "TV Remote Service" in box Settings → Accessibility; or focus "Turn on" at the end of that box row and press OK to jump straight to the same settings |
| "The box could not perform it" | No focused control/input on the current screen; open the target screen first |
| Scan finds nothing | Same Wi-Fi, box service running, AP isolation off; or add the IP manually |
| D-pad does nothing | The screen has no focusable element (e.g. full-screen player); some firmware only supports focus traversal |
| Touchpad taps or swipes do nothing | That firmware does not execute synthetic gestures; switch to Mouse mode and drag the cursor. Both modes require accessibility |
| Bluetooth keys do nothing | Keep the app in the foreground, tap "BT" again, confirm "Bluetooth connected" |
| Air mouse moves no cursor | The box cached the HID descriptor from its first pairing: remove the pairing with the phone in the box Bluetooth settings and pair again; on some firmware (H96) also restart box Bluetooth or edit `HidDescriptor` in `bt_config.conf` with root |
| Cannot connect after a box reboot | Check auto-start; some firmware needs an explicit auto-start permission or reopening the app |
| Web remote does not open | Confirm the same WiFi and use the address shown on the box (`http://<ip>:17880`); for `https://<ip>:17843` first trust the self-signed certificate in the browser |
| An "Update" badge appears in the header | Every launch queries the latest GitHub and Gitee releases and prompts when either is newer; tap the badge to read the release notes. Ignoring it is fine |
| Box shows "ADB debugging: not connected" | ADB / USB debugging is off in Developer options: press OK on that row or on its "Turn on" button to jump there (OK enables it directly once authorized); TVs without that page fall back to the settings home |
| Box shows "ADB debugging: off" | You are in accessibility injection mode; press OK on the "Injection" row to switch to ADB, then press OK on the "ADB debugging" row to connect |

## Scope and limitations

Out of scope:

- Controlling the TV's own power/volume (IR / HDMI-CEC): the Power key locks the box, and volume
  changes the box media volume.
- WAN or cross-subnet control. The protocol is plaintext and meant for a trusted LAN only;
  never expose 8899 / 17880 / 17843 to the internet.
- Native iOS / HarmonyOS NEXT clients (not developed separately; use the built-in web remote).

Known limitations:

- "Power" locks the screen; it cannot really power the box on/off. "Menu" opens recent tasks.
- Volume changes only the box media volume, not the TV or an AV receiver.
- Text input requires an editable field on the current screen and a box IME that accepts `ACTION_SET_TEXT`.
- Some TV firmware does not support global DPAD actions (H96 / Android 12 in our tests); the app
  falls back to focus traversal, so direction keys do nothing on screens without focusable controls.
- Bluetooth HID does not support text input or app shortcuts and needs the app in the foreground.
- OK long-press is judged by the box app in BT mode; in direct mode it depends on the focused
  node supporting `ACTION_LONG_CLICK`, so apps without long-click support show no effect.
- Air mouse is available only in the Android app's BT HID mode: the box must accept Bluetooth
  mouse HID reports. Phones without a gyroscope cannot enable it; sensitivity is a fixed constant.
- Both the web and phone touchpads work over the direct protocol only and require the box
  accessibility service; BT mode offers no touchpad. Touch mode relies on clickable nodes or
  synthetic gestures: on firmware that does not execute synthetic gestures (measured on H96
  Android 12), pure-touch surfaces and swipes do nothing, so switch to Mouse mode. Mouse mode
  relies on the cursor overlay; launchers that only respond to a D-pad still cannot be driven.
- The web remote needs the box service online. Browsers never receive the UDP broadcast, so a box
  must be added by scanning its QR code or typing its address. Camera scanning works only on
  HTTPS pages (secure context). If the self-signed certificate is not trusted, Chrome offers no
  install entry and may disable the Service Worker; iOS Safari can still "Add to Home Screen"
  but without offline caching.
- Controlling box B from the page served by box A is a cross-origin request, which the box web
  service allows; therefore never expose 17880 / 17843 to an untrusted network.
- The box persists the HID report descriptor from its **first** pairing (on H96 it lives in
  `HidDescriptor` inside `/data/misc/bluedroid/bt_config.conf`; Fold3 re-reads it on reconnect).
  After upgrading, boxes paired with an older version must remove the pairing and pair again,
  otherwise they still build a keyboard-only input device and air mouse does nothing.
- App shortcuts cover third-party apps with a LAUNCHER / LEANBACK_LAUNCHER entry; pure service apps
  and apps without a launcher entry are not listed.
- `LIST` relies on the `<queries>` declaration in the box app manifest (Android 11+ package visibility).
- In-app updates query both the GitHub and Gitee public releases; a newer version on either source
  triggers the badge. The anonymous GitHub API limit is 60 requests per hour, and the other source
  covers that failure.
- The APK is downloaded from the Gitee direct URL first and falls back to the GitHub API asset
  endpoint; when neither source is reachable, use a proxy or download the APK manually.
- Updates always go through the system install confirmation (no silent install). Some Android
  versions additionally require allowing this app to install unknown apps; on the box the
  installer dialog must be confirmed with the physical remote.
- The box "Check for updates" button handles both remote OK/ENTER and touch, because some firmware
  does not fire the default click for a focusable TextView.
- The startup update check runs once per app launch (no background polling). It fails silently when
  both sources are unreachable; use "Check for updates" on the About card to retry.

## License

Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE):

- **Noncommercial study and research**: free to use under the PolyForm Noncommercial terms.
- **Any commercial use (internal company use, SaaS, resale)**: not permitted without a separate
  commercial license from the author (mst8222@163.com).

Redistributions and modified works must retain this license and copyright notice.

The phone app bundles [ZXing core](https://github.com/zxing/zxing) (Apache-2.0) for QR recognition.

---

<div align="center">

If this project helps you, please consider giving it a Star.

[GitHub Releases](https://github.com/mst8222/tv-remote/releases) • [Gitee Releases](https://gitee.com/moston/tv-remote/releases) • [Issues](https://github.com/mst8222/tv-remote/issues)

<sub>TV Remote · Noncommercial use only</sub>

</div>
