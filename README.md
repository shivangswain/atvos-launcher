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
  HTTPS-only and used just for recommendation artwork and your Google profile photo.

## Using it

| Remote | Does |
| --- | --- |
| D-pad | Move focus. Down from the dock scrolls into the grid. |
| Select | Open the app (it zooms out of its icon). |
| Hold select (or Menu) | Opens the tvOS menu beside the icon: **Edit Home Screen**, **App Info**, switches for the app's **Continue Watching** and **Recommendations** (when it has them), and **Hide App**. |
| Edit Home Screen | Every icon jiggles. Select picks an app up, the arrows move it, and select drops it. Back finishes. |
| Up on the dock | On a full-screen top shelf: the dock slides down to peek in at the bottom and a **Play** button appears. On a small one's card: the card is selected, with **Play** under its title. Select plays what's shown; Down or Back returns. |
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

- **HD images** (720p or larger) fill the screen, with "Press up for full screen" above the dock.
  Up there leaves the dock peeking in at the bottom with a **Play** button. Left alone for a minute,
  both fade away so the picture is a wallpaper; the next press brings them back.
- **Smaller images**, such as YouTube thumbnails, would look soft stretched, so they're shown as a
  card on the left with the program's name beside it, over a blurred copy. The dock stays put: Up
  selects the card and shows **Play** under the title, and Up again goes to the clock pill.
- **Apps without recommendations** show their launch screen when it's a real full-screen picture
  (like Crunchyroll's). Most TV apps launch on a flat colour or a small logo on black, which would
  look worse, so the rest show their logo over a glow in their own colour, as tvOS does:
  - the icon's own artwork without its backdrop shape (an adaptive icon's foreground layer, or a
    legacy icon drawn on transparency), lit in the brand colour of the app's tile;
  - otherwise the banner's logo, cut out of the banner's background (flat or gradient) without a
    fringe, over a field in that background's colour, light or dark as the tile is. A banner that's
    a picture rather than a logo on a background is shown whole, as a card.

  Logos are never enlarged more than 1.5x the pixels the app ships, so they stay sharp; most TV
  apps ship small banners, so their logos are modest in size rather than soft.

Each app's long-press menu has a switch for its **Continue Watching** and one for its
**Recommendations** (shown only for apps that publish them). Turn off Continue Watching to see the
app's recommendations instead, turn off both to see just its logo. The switches are saved on the TV.

Below the dock, the last top shelf stays behind the grid, blurred and dimmed, as on tvOS.

Text on the clock pill, the hint and the Control Center switches to dark over bright images, like
Liquid Glass. The clock follows the TV's 12/24-hour setting, and moving around plays the TV's
navigation sounds when **System sounds** are on in Settings.

### Control Center

| Tile | Does |
| --- | --- |
| Top row | As on tvOS: the time, glyphs for what's on (Bluetooth audio, Do Not Disturb, sleep timer), a **Settings** button that opens TV Settings, and your profile. |
| Profile (Up from the top tiles, then Right) | Pick the TV's Google account with Android's chooser. The pill then shows its Google profile photo (see below), else its letter avatar. Push `files/avatar.png` to use another picture instead. |
| Power Off | Puts the TV to sleep, like the remote's power button. |
| Wi-Fi / Ethernet | Shows the connection; opens network settings. |
| Do Not Disturb | Toggles priority-only notifications. |
| Bluetooth | Shows whether Bluetooth is on, or the connected headphones; opens Remotes & Accessories. |
| Sleep Timer | Each press cycles Off → 15 → 30 → 60 → 90 → 120 min. |
| Inputs, Accessibility, System, Search | Open TCL's input picker, accessibility settings, Settings > System, and Google search (the Assistant). |

### Google profile photo

Google only shares profile photos with apps registered to it, so this needs a one-time OAuth client
for the app. It's free and has no secret to copy:

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project (or pick one).
2. Under **Google Auth Platform**, set up the consent screen as **External** and **publish** it. The
   app asks only for basic profile info, which needs no Google review.
3. Under **Clients**, create an **Android** client with package name `app.tvhome` and the SHA-1 of
   the key the APK is signed with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

Then pick your account again in the Control Center (Profile). The TV asks once to let Home see your
basic profile info. After that the photo is fetched over HTTPS, cached on the TV and refreshed at
most once a day. The access token is never stored. Until the client exists, the letter avatar stays.

## Permissions

| Permission | Why | Granted by |
| --- | --- | --- |
| `READ_TV_LISTINGS` | Lets the TV provider share other apps' Watch Next and preview programs. | A one-time prompt on first launch, or `adb install -g`. |
| `INTERNET` | Downloads recommendation images and your profile photo (HTTPS only; cleartext is disabled). | Install. |
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
  Backdrops.java         top-shelf images, app logo backdrops, download cache, blur
  Recommendations.java   apps' Watch Next / preview programs from the TV provider
  Profile.java           Google profile photo or letter avatar (or avatar.png)
  PowerAdmin.java        force-lock device admin
  ReleasePower.java      adb-only release of that admin, before uninstalling
  SleepTimer.java        sleep timer alarm receiver
```
