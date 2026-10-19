# atvOS Launcher

A replacement home screen for Google TV and Android TV (Android 12 or later), inspired
by the Apple TV home screen. It has a liquid-glass dock for the top row and a grid of app icons
below. Behind the dock sits a "top shelf" backdrop from each app's own recommendations. In the
corner, a clock pill opens a Control Center.

atvOS Launcher is an independent project, not affiliated with or endorsed by Apple. Apple TV and
tvOS are trademarks of Apple Inc.

- **Pure Java with zero dependencies.** It uses only the Android framework: no AndroidX, no Kotlin
  stdlib. The release APK is about 115 KB.
- **One custom `View`.** It draws everything on a single canvas in 1920×1080 design units. The
  animations are interruptible exponential eases, and the view only redraws while something is
  moving. It runs at 60 fps on the TV's MT5896, with about 1 ms of UI-thread work per frame.
- **Works with TalkBack.** The view gives screen readers a tree of virtual items (app tiles, the
  clock pill, Control Center tiles with their states, menu options, Play) and moves their focus
  with the remote's, so each item is read as it's reached. With no screen reader on, this costs a
  single check per key press.
- **Small, explained permission set.** Each permission is listed with its reason below. Only the
  launcher activity and the system-only device admin receiver are exported. Network access is
  HTTPS-only and used just for recommendation artwork and your Google profile photo.

## Using it

| Remote | Does |
| --- | --- |
| D-pad | Move focus. Down from the dock fills the screen with your apps, the top row just under the clock. |
| Select | Open the app (it zooms out of its icon). |
| Hold select (or Menu) | Opens the menu beside the icon: **Edit Home Screen**, **App Info**, switches for the app's **Continue Watching** and **Recommendations** (when it has them), **Setup & Support**, and **Hide App** (**Unhide App** for a hidden app). The selected option pops out of the menu. |
| Edit Home Screen | The app is picked up and wiggles. The arrows move it; select (or Back) drops it there and ends the edit. |
| Hold Down on the last row | An arrow appears under the row and a ring fills round it; when it closes, focus drops into the greyed-out **Hidden** apps. |
| Up on the dock | On a full-screen top shelf: the dock slides down to peek in at the bottom and a **Play** button appears. On a small one's card: the card is selected, with **Play** under its title. Select plays what's shown; Down or Back returns. |
| Up again (or Up when there's no top shelf) | Focuses the clock pill. Select opens the **Control Center**. |
| Back / Home | Returns to the dock row. |

### Setup & Support

On first run, a sheet says whether atvOS Launcher is the home screen and how to make it so. It
opens again from **Setup & Support** in any app's long-press menu.

- The status comes from where the Home button actually goes, not from Android's home-app setting.
- **Google TV and Android TV:** their own home screens (`launcherx`, `tvlauncher`) declare a raised
  priority and keep the Home button even after another app is chosen in Android's dialog (checked
  on both, Android 12). Only turning them off with adb gets past them, so the sheet shows the
  steps and the exact commands for the TV's own home apps. It turns green as soon as they run.
- **Other TVs:** where an ordinary home app has the button, **Set as Default Home App** opens
  Android's home-app dialog.
- **Remove Power Control** appears while the app holds the device admin, so it can be uninstalled.

Closing the sheet the first time brings up the prompt for app recommendations.

### Hiding and unhiding apps

**Hide App** in the long-press menu removes an app from the home screen. Hidden apps live greyed out
in a **Hidden** section below your apps, which stays out of the way until you hold Down on the last
row of apps. In there, a hidden app's menu has **Unhide App**, or use **Edit Home Screen** to move
it back up among your apps. Moving an app down into Hidden while editing hides it. Up out of the
section (or Back) folds it away again. The order and hidden apps are saved on the TV.

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
  look worse, so the rest show their logo over a glow in their own colour:
  - the icon's own artwork without its backdrop shape (an adaptive icon's foreground layer, or a
    legacy icon drawn on transparency), lit in the brand colour of the app's tile;
  - otherwise the banner's logo, cut out of the banner's background (flat or gradient) without a
    fringe, over a field in that background's colour, light or dark as the tile is. A banner that's
    a picture rather than a logo on a background is shown whole, as a card.

  Every app's logo is shown at the same size (the area of a 160-unit square, round badge or long
  wordmark alike), never enlarged more than 1.5x the pixels the app ships, so they all look alike
  and none look soft.

Each app's long-press menu has a switch for its **Continue Watching** and one for its
**Recommendations** (shown only for apps that publish them). Turn off Continue Watching to see the
app's recommendations instead, turn off both to see just its logo. The switches are saved on the TV.

Below the dock, the last top shelf stays behind the grid, blurred and dimmed.

Text on the clock pill, the hint and the Control Center switches to dark over bright images, like
Liquid Glass. The clock follows the TV's 12/24-hour setting, and moving around plays the TV's
navigation sounds when **System sounds** are on in Settings.

### Control Center

| Tile | Does |
| --- | --- |
| Top row | The time, glyphs for what's on (Bluetooth audio, Do Not Disturb, sleep timer), a **Settings** button that opens TV Settings, and your profile. |
| Profile (Up from the top tiles, then Right) | Pick the TV's Google account with Android's chooser. The pill then shows its Google profile photo (see below), else its letter avatar. Push `files/avatar.png` to use another picture instead. |
| Power Off | Puts the TV to sleep, like the remote's power button. Hold select on it to give up power control (so the app can be uninstalled). |
| Wi-Fi / Ethernet | Shows the connection; opens network settings. |
| Do Not Disturb | Toggles priority-only notifications. Needs Do Not Disturb access: Android's screen for it opens if the TV has one (Google TV doesn't; there, only adb can grant it, see below). |
| Bluetooth | Shows whether Bluetooth is on, or the connected headphones; opens Remotes & Accessories. |
| Sleep Timer | Each press cycles Off → 15 → 30 → 60 → 90 → 120 min. |
| Inputs, Accessibility, System, Search | Open TCL's input picker, accessibility settings, Settings > System, and Google search (the Assistant). |

### Google profile photo

Google only shares profile photos with apps registered to it, so this needs a one-time OAuth client
for the app. It's free and has no secret to copy:

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project (or pick one).
2. Under **Google Auth Platform**, set up the app as **External** and leave it in **Testing**. Under
   **Audience**, add the TV's Google account as a test user. (Publishing would need a public home
   page and privacy policy. Testing is fine here: access to basic profile info doesn't expire after
   seven days, as it does for other scopes.)
3. Under **Clients**, create an **Android** client with package name `app.atvos.launcher` and the
   SHA-1 of the key the APK is signed with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

Then pick your account again in the Control Center (Profile). The TV asks once to let atvOS Launcher
see your basic profile info. After that the photo is fetched over HTTPS, cached on the TV and
refreshed at most once a day. The access token is never stored. Until the client exists, the letter
avatar stays.

## Permissions

| Permission | Why | Granted by |
| --- | --- | --- |
| `READ_TV_LISTINGS` | Lets the TV provider share other apps' Watch Next and preview programs. | A one-time prompt on first launch, or `adb install -g`. |
| `INTERNET` | Downloads recommendation images and your profile photo (HTTPS only; cleartext is disabled). | Install. |
| `ACCESS_NETWORK_STATE` | The Wi-Fi/Ethernet tile. | Install. |
| `ACCESS_NOTIFICATION_POLICY` | The Do Not Disturb tile. | adb (below). |
| `SCHEDULE_EXACT_ALARM` | The sleep timer fires on time (an inexact alarm can be 10 minutes late). | Granted at install up to Android 13. From Android 14, the first Sleep Timer press opens **Alarms & reminders** to allow it. |
| Device admin, `force-lock` only | Power Off and the sleep timer. It can't wipe, reset or read anything. Hold select on Power Off to release it, or use adb (below). | A consent screen on the first Power Off, or adb (below). |

## Building

Open the project in Android Studio, or build from the command line with the Gradle wrapper (JDK 17
or later; the Android SDK location comes from `local.properties`, which Android Studio writes):

```bash
./gradlew assembleRelease
```

The release build is minified with R8. It's signed with the key in `keystore.properties` if that
file exists (it's never committed):

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, release builds are signed with your local debug key (`~/.android/debug.keystore`),
for sideloading. For Google Play, build a bundle with `./gradlew bundleRelease`; it includes
Google's encrypted dependency report so Play can flag outdated SDKs.

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
adb shell cmd notification allow_dnd app.atvos.launcher
```

```bash
adb shell dpm set-active-admin --user 0 app.atvos.launcher/.PowerAdmin
```

To make it the home screen, set it as HOME. Google TV's own launcher and its recovery screen
outrank any third-party HOME app, so they also have to be disabled for the main user:

```bash
adb shell cmd package set-home-activity app.atvos.launcher/.HomeActivity
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

Release the power admin first: Android blocks uninstalling an active admin, and Android TV has no
screen to switch it off. Hold select on **Power Off** in the Control Center, or use adb (the
receiver only accepts this from senders holding `DUMP`, such as the adb shell):

```bash
adb shell am broadcast -n app.atvos.launcher/.ReleasePower
```

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
```

```bash
adb shell pm enable com.google.android.tungsten.setupwraith
```

```bash
adb uninstall app.atvos.launcher
```

## Layout

```
app/src/main/java/app/atvos/launcher/
  HomeActivity.java      lifecycle, background loading, launching apps and programs
  HomeView.java          all home-screen drawing, animation and remote input
  A11y.java              the screen-reader tree for HomeView (TalkBack)
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
