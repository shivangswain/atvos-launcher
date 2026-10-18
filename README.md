# tvOS Home

A replacement home screen for Google TV (built for a TCL C6K; needs Android 14 or later) that looks and moves
like the Apple tvOS home screen. It has a liquid-glass dock for the top row and a grid of app icons
below. Behind the dock sits a "top shelf" backdrop from each app's own recommendations. In the
corner, a clock pill opens a tvOS-style Control Center.

- **Pure Java with zero dependencies.** It uses only the Android framework: no AndroidX, no Kotlin
  stdlib. The release APK is about 80 KB.
- **One custom `View`.** It draws everything on a single canvas in 1920×1080 design units. The
  animations are interruptible exponential eases, and the view only redraws while something is
  moving. It runs at 60 fps on the TV's MT5896, with about 1 ms of UI-thread work per frame.
- **Small, explained permission set.** Each permission is listed with its reason below. Only the
  launcher activity and the system-only device admin receiver are exported. Network access is
  HTTPS-only and used just for recommendation artwork.

## Using it

| Remote | Does |
| --- | --- |
| D-pad | Move focus. Down from the dock scrolls into the grid. |
| Select | Open the app (it zooms out of its icon). |
| Hold select (or Menu) | Opens the tvOS menu beside the icon: **Edit Home Screen**, **App Info**, **Hide App**. |
| Edit Home Screen | Every icon jiggles. Select picks an app up, the arrows move it, and select drops it. Back finishes. |
| Up on the dock | Shows the top shelf full screen. Select there opens what's shown; Down or Back returns. |
| Up again (or Up when there's no top shelf) | Focuses the clock pill. Select opens the **Control Center**. |
| Back / Home | Returns to the dock row. |

### Hiding and unhiding apps

**Hide App** in the long-press menu removes an app from the home screen. To bring it back, use
**Edit Home Screen**. Hidden apps appear greyed out in a **Hidden** section below your apps. Move an
app into that section to hide it, or move a hidden one up into your apps to unhide it. The order
and hidden apps are saved on the TV.

### Top shelf

While an app is focused in the dock, its top shelf fills the background. The image comes from what
the app publishes for the TV home screen:

1. Its **Watch Next** entry (what you're in the middle of), else
2. The first program in its **preview channel**.

Only one landscape image is used per app. It stays a still picture, with no preview videos and no
rotation. Images are downloaded once over HTTPS and cached. An app's shelf appears only once its
image is on disk, so a slow or missing network never blocks the UI. Local `content://` and
`android.resource://` images are only accepted from the app that published the recommendation.

- **HD images** (720p or larger) fill the screen.
- **Smaller images**, such as YouTube thumbnails, would look soft stretched, so they're shown as a
  card on the left with the program's name beside it, over a blurred copy.
- **Apps without recommendations** show their own banner or icon large, over a field of their
  colours, as tvOS does.

Below the dock, the last top shelf stays behind the grid, blurred and dimmed, as on tvOS.

You can override any app's image, or set a wallpaper to use instead of app logos, by pushing your
own images:

```bash
adb push wallpaper.jpg /sdcard/Android/data/app.tvhome/files/wallpaper.jpg
```

```bash
adb push poster.jpg /sdcard/Android/data/app.tvhome/files/topshelf/com.netflix.ninja.jpg
```

Text on the clock pill, the hint and the Control Center switches to dark over bright images, like
Liquid Glass. The clock follows the TV's 12/24-hour setting, and moving around plays the TV's
navigation sounds when **System sounds** are on in Settings.

### Control Center

| Tile | Does |
| --- | --- |
| Top row | As on tvOS: the time, glyphs for what's on (Bluetooth audio, Do Not Disturb, sleep timer), a **Settings** button that opens TV Settings, and your profile. |
| Profile (Up from the top tiles, then Right) | Pick the TV's Google account with Android's chooser. The pill then shows its letter avatar. Push `files/avatar.png` to use a picture instead. |
| Power Off | Puts the TV to sleep, like the remote's power button. |
| Wi-Fi / Ethernet | Shows the connection; opens network settings. |
| Do Not Disturb | Toggles priority-only notifications. |
| Bluetooth | Shows whether Bluetooth is on, or the connected headphones; opens Remotes & Accessories. |
| Sleep Timer | Each press cycles Off → 15 → 30 → 60 → 90 → 120 min. |
| Inputs, Accessibility, System, Search | Open TCL's input picker, accessibility settings, Settings > System, and Google search (the Assistant). |

## Permissions

| Permission | Why | Granted by |
| --- | --- | --- |
| `READ_TV_LISTINGS` | Lets the TV provider share other apps' Watch Next and preview programs. | A one-time prompt on first launch, or `adb install -g`. |
| `INTERNET` | Downloads recommendation images (HTTPS only; cleartext is disabled). | Install. |
| `ACCESS_NETWORK_STATE` | The Wi-Fi/Ethernet tile. | Install. |
| `ACCESS_NOTIFICATION_POLICY` | The Do Not Disturb tile. | adb (below). |
| `USE_EXACT_ALARM` | The sleep timer fires on time. | Install. |
| Device admin, `force-lock` only | Power Off and the sleep timer. It can't wipe, reset or read anything. Only adb can release it (see below). | adb (below), or a consent screen on the first Power Off. |

## Building

The toolchain comes from the nix-darwin config (`~/.config/nix#macos`): `jdk21`, plus the Android
SDK (platform 36, build-tools 36.0.0) exposed as `ANDROID_HOME`. Gradle comes from nixpkgs.

```bash
nix shell nixpkgs#gradle -c gradle assembleRelease
```

The release build is minified with R8 and signed with your local debug key (`~/.android/debug.keystore`).

## Installing on the TV

Connect over the LAN. If you use Tailscale's address, the TV's exit node also carries all its
internet traffic.

```bash
adb connect 192.168.1.27:5555
```

```bash
adb install -r -g app/build/outputs/apk/release/app-release.apk
```

Grant the two Control Center accesses that Google TV has no settings screen for:

```bash
adb shell cmd notification allow_dnd app.tvhome
```

```bash
adb shell dpm set-active-admin --user 0 app.tvhome/.PowerAdmin
```

To make it the home screen, set it as HOME. Google TV's own launcher and its recovery screen
outrank any third-party HOME app, so they also have to be disabled for the main user:

```bash
adb shell cmd package set-home-activity app.tvhome/.HomeActivity
```

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

```bash
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

A system update may re-enable them. If the Google TV home comes back, run the two `disable-user`
commands again.

### Going back to Google TV's home screen

Release the power admin first. Android blocks uninstalling an active admin, and Android TV has no
screen to switch it off. The receiver only accepts this from the adb shell; apps can't send it.

```bash
adb shell am broadcast -n app.tvhome/.ReleasePower
```

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
```

```bash
adb shell pm enable com.google.android.tungsten.setupwraith
```

```bash
adb uninstall app.tvhome
```

## Layout

```
app/src/main/java/app/tvhome/
  HomeActivity.java      lifecycle, background loading, launching apps and programs
  HomeView.java          all home-screen drawing, animation and remote input
  ControlCenter.java     Control Center layout, focus and drawing
  Controls.java          what the Control Center tiles read and do
  Apps.java              launchable apps, tile artwork, order and hidden apps
  Backdrops.java         wallpaper and top-shelf images, download cache, blur
  Recommendations.java   apps' Watch Next / preview programs from the TV provider
  Profile.java           Google account letter avatar (or avatar.png)
  PowerAdmin.java        force-lock device admin
  ReleasePower.java      adb-only release of that admin, before uninstalling
  SleepTimer.java        sleep timer alarm receiver
```
