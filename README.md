# atvOS Launcher

A replacement home screen for Google TV and Android TV (Android 12 or later), inspired
by the Apple TV home screen. It has a liquid-glass dock for the top row and a grid of app icons
below. Behind the dock sits a "top shelf" backdrop from each app's own recommendations. In the
corner, a clock pill opens a Control Center.

atvOS Launcher is an independent project, not affiliated with or endorsed by Apple. Apple TV and
tvOS are trademarks of Apple Inc.

- **Pure Java, one dependency.** It uses the Android framework plus Google Play billing (for
  optional donations), and nothing billing would bring along: no AndroidX, no Play services, no Kotlin
  stdlib (see [Building](#building)). The release APK is about 380 KB.
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
| Hold select (or Menu) | Opens the menu beside the icon, as on tvOS: **Edit Home Screen**, **Move to…** (a new or existing folder, **Hidden**, or back to the **Home Screen**), **Top Shelf…** with switches for the app's **Continue Watching** and **Recommended** (when it has them), **App Info**, and **Delete App** for apps that can be uninstalled. A folder can only be moved. |
| Edit Home Screen | The app is picked up and wiggles. The arrows move it; select (or Back) drops it there and ends the edit. |
| Hold Down on the last row | An arrow appears under the row and a ring fills round it; when it closes, focus drops into the greyed-out **Hidden** apps. |
| Up on the dock | On a full-screen top shelf, as on tvOS: the dock and clock slide away, the picture fills the screen with its title, details and **Play** at the bottom left, and Left and Right page through the app's other programs. On a row of cards: the first card is focused, and Left and Right move along the row. Select plays what's shown or focused; Down or Back returns. |
| Up again (or Up when there's no top shelf) | Focuses the clock pill. Select opens the **Control Center**. |
| Back / Home | Returns to the dock row. |

### Setup

On first run, setup takes you through what's left to do, one step at a time, on the TV itself
(**Not Now** skips it). Steps that are already done are skipped, and the Settings steps show a
picture of the TV's own screen and open it at the right row:

1. **Turn On Developer Options:** opens About, where you select the build row seven times.
2. **Turn On USB Debugging:** opens Developer options.
3. **Allow USB Debugging:** **Set Up** connects to the TV's own debugging (127.0.0.1:5555) and the
   TV asks you to allow it. atvOS Launcher then makes itself the home app and turns off the TV's own
   home screens. It only ever runs those fixed commands (and, to undo them, the matching
   `pm enable`); there is no way for it to run anything else. This is the only thing atvOS Launcher
   uses adb for: everything else goes through the TV's own Settings and Android's APIs.
4. **Make It Your Home Screen:** on TVs whose home app is an ordinary one, Android's home-app
   dialog instead.
5. **Add Your Profile Picture** (optional): the Google account chooser.

Why steps 1–3: Google TV and Android TV's own home screens (`launcherx`, `tvlauncher`) declare a
raised priority and keep the Home button even after another app is chosen in Android's dialog
(checked on both, Android 12). Only turning them off gets past them. The status comes from where the
Home button actually goes, not from Android's home-app setting.

Finishing setup the first time brings up the prompt for app recommendations.

### Settings

The two switches at the top of the Control Center (beside the TV's own Settings cog) open atvOS
Launcher's Settings, full screen and laid out as tvOS Settings: the page's title at the top, the
atvOS logo on the left with a few words about the focused row under it, and the rows on the right,
the focused one white. Back returns a page; Menu closes Settings.

| Page | Rows |
| --- | --- |
| Settings | **Home Screen ›** (only while another app has the Home button: the setup steps), **General ›**, **Profiles and Accounts ›**, **Uninstall ›**, **Support ›** |
| General | **About ›** (Model, atvOS version, Android TV version), **Appearance ›** (Light or Dark, for Settings and the top shelf's rows), **Privacy**, **Acknowledgements**, then under **Date & Time**, **Time Format** (12-Hour or 24-Hour; by default the TV's own) |
| Profiles and Accounts | **Account** (the Google account, by its address; select to choose one with Android's chooser), **Profile Picture ›** (Google Photo, Initial or None) |
| Uninstall | **Restore Default Launcher** (turns the TV's own home screens back on), **Remove Power Control** (gives up the device admin). Do both before uninstalling. |
| Support | **Website ›** (atvos.tv; on a TV without a web browser, just the address, with no chevron), **Open Play Store ›** (atvOS Launcher on Google Play), **App Info ›** (Android's App Info, with Uninstall), **Donate ›** (see below) |

The logo is atvOS's wordmark cut out of a grey tile with tvOS's continuous corners, as tvOS Settings
shows its own: dark grey on lighter grey when Settings is dark, the other way round when it's light.
The app's icon and TV banner are the same wordmark in dark grey on lighter grey.

#### Donations through Google Play

Create a one-time, consumable in-app product with the id `donation` in Play Console, priced per
country (₹99, $4.99, £4.99, 9,99 zł, ¥499, ₱99, ₩4,999). **Donate** shows the price Play reports for
the TV's country, opens Google Play's own purchase sheet, and uses the donation up once Play confirms
it, so it can be given again. It unlocks nothing. atvOS Launcher talks to Google Play only after
Settings > Support is opened, until Settings closes.

### Hiding and unhiding apps

**Move to… > Hidden** in the long-press menu removes an app from the home screen. Hidden apps live
greyed out in a **Hidden** section below your apps, which stays out of the way until you hold Down
on the last row of apps. In there, a hidden app's **Move to… > Home Screen** brings it back, or use
**Edit Home Screen** to move it back up among your apps. Moving an app down into Hidden while editing
hides it. Each time an app is hidden, a note on glass at the bottom right says how to get back to it:
"Long press ↓ to enter the Hidden section". Up out of the section (or Back) folds it away again. The
order, folders and hidden apps are saved on the TV.

### Folders and deleting apps

**Move to… > New Folder** puts the app in a new folder and opens it, as on tvOS; select the folder's
name to rename it. **Move to…** also lists your folders, and **Home Screen** takes an app back out.
A folder left empty goes away.

**Delete App** asks first, then hands over to Android's own uninstall screen. Apps that came with the
TV don't have it.

### Top shelf

While an app is focused in the dock, its top shelf fills the background. The image comes from what
the app publishes for the TV home screen:

1. Its **Watch Next** entries (what you're in the middle of), most recent first, then
2. The programs in its **preview channels**, first channel first.

Up to eight programs are kept per app: the first is the one behind the dock, and the full-screen
shelf pages through the rest, or the row shows them all (fetched when the app is focused in the
dock). The dock
keeps the last one you paged to, as tvOS does. They stay still pictures, with no preview videos and
no rotation. Images are downloaded once over HTTPS and cached. An app's shelf appears only once its
image is on disk, so a slow or missing network never blocks the UI. Local `content://` and
`android.resource://` images are only accepted from the app that published the recommendation.

- **Full HD images** (1080p or larger) fill the screen, with "Press up for full screen" above the
  dock, tinted by the picture as tvOS tints it. Up there is tvOS's full-screen shelf: the dock and
  clock slide away, and the bottom left shows the program's title, a line of details (the episode,
  or type, genre, year and length), its description and a white **Play** button, as the Apple TV
  app does. With more than one page, round arrows at the sides and page dots at the bottom show
  for a moment, then fade. Select plays what's shown.
- **Smaller images**, such as YouTube thumbnails, would look soft stretched, so the app's programs
  are shown as tvOS shows a Continue Watching row: 16:9 cards over a plain dark background, each
  section titled "Continue Watching" (Watch Next) or "Recommended" (its preview channels), with a progress
  bar on anything partly watched. Up focuses the first card, which grows on a drop shadow with the
  same glass rim as the dock and the clock pill, and shows its title. A title too long for the card starts at its left edge, faded out at the right, and after a
  second scrolls round in place as tvOS's does (45 units a second, two seconds' rest each time
  round). Left and Right move along the row, and Up again goes to the clock pill.
- **Apps without recommendations** show their launch screen when it's a real full-screen picture
  (like Crunchyroll's). Most TV apps launch on a flat colour or a small logo on black, which would
  look worse, so the rest show their logo over a glow in their own colour:
  - the icon's own artwork without its backdrop shape (an adaptive icon's foreground layer, or a
    legacy icon drawn on transparency), lit in the brand colour of the app's tile;
  - otherwise the banner's logo, cut out of the banner's background (flat or gradient) without a
    fringe, over a field in that background's colour, light or dark as the tile is.

  A tile with a gradient background (JioHotstar's blue to pink) gets that gradient across the
  screen, darkened toward the dock, instead of a single colour. A banner that's
    a picture rather than a logo on a background is shown whole, as a card.

  Every app's logo is shown at the same size (the area of a 160-unit square, round badge or long
  wordmark alike), never enlarged more than 1.5x the pixels the app ships, so they all look alike
  and none look soft.

**Top Shelf…** in each app's long-press menu has a switch for its **Continue Watching** and one for its
**Recommended** (shown only for apps that publish them). Turn off Continue Watching to see the
app's recommendations instead, turn off both to see just its logo. The switches are saved on the TV.

Below the dock, the last top shelf stays behind the grid, blurred and dimmed.

Text on the clock pill, the hint and the Control Center switches to dark over bright images, like
Liquid Glass. The clock is 12- or 24-hour as Settings > General > Time Format says (by default, as
the TV has it), and moving around plays the TV's navigation sounds when **System sounds** are on in
the TV's Settings.

### Control Center

| Tile | Does |
| --- | --- |
| Top row | The time, glyphs for what's on (Bluetooth audio, Do Not Disturb, sleep timer), two switches for atvOS Launcher's **Settings**, a cog that opens the TV's own Settings, and your profile. |
| Profile (Up from the top tiles, then Right) | Pick the TV's Google account with Android's chooser. The pill then shows its Google profile photo (see below), else its letter avatar, as Settings > Profile Picture says. |
| Power Off | Puts the TV to sleep, like the remote's power button. Hold select on it to give up power control (as Settings > Uninstall > Remove Power Control does). |
| Wi-Fi / Ethernet | Shows the connection; opens network settings. |
| Do Not Disturb | Toggles priority-only notifications. Needs Do Not Disturb access: Android's screen for it opens if the TV has one. Google TV doesn't have that screen, so there the tile says it can't. |
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
   SHA-1 of Google Play's app signing key (Play Console > **Test and release** > **App integrity**).

Then pick your account again in Settings > Profiles and Accounts > Account. The TV asks once to let atvOS Launcher
see your basic profile info. After that the photo is fetched over HTTPS, cached on the TV and
refreshed at most once a day. The access token is never stored. Until the client exists, the letter
avatar stays.

## Permissions

| Permission | Why | Granted by |
| --- | --- | --- |
| `READ_TV_LISTINGS` | Lets the TV provider share other apps' Watch Next and preview programs. | A one-time prompt on first launch. |
| `INTERNET` | Downloads recommendation images and your profile photo (HTTPS only; cleartext is disabled). | Install. |
| `ACCESS_NETWORK_STATE` | The Wi-Fi/Ethernet tile. | Install. |
| `ACCESS_NOTIFICATION_POLICY` | The Do Not Disturb tile. | Android's Do Not Disturb access screen, where the TV has one. |
| `REQUEST_DELETE_PACKAGES` | **Delete App** opens Android's uninstall screen. | Install. |
| `com.android.vending.BILLING` | Settings > Support > Donate, through Google Play (added by the billing library). | Install. |
| `SCHEDULE_EXACT_ALARM` | The sleep timer fires on time (an inexact alarm can be 10 minutes late). | Granted at install up to Android 13. From Android 14, the first Sleep Timer press opens **Alarms & reminders** to allow it. |
| Device admin, `force-lock` only | Power Off and the sleep timer. It can't wipe, reset or read anything. Settings > Uninstall > Remove Power Control releases it. | A consent screen on the first Power Off or Sleep Timer press. |

## Building

Open the project in Android Studio, or build from the command line with the Gradle wrapper (JDK 17
or later; the Android SDK location comes from `local.properties`, which Android Studio writes).

The one dependency, Google Play billing 9.1.0, is pulled in without its transitive libraries
(`isTransitive = false`). Its own code needs only three AndroidX methods, which
`app/src/main/java/androidx/core` stands in for in plain Java; its usage logging turns itself off
when its library is missing; and its alternative-billing screen (the only part needing
`androidx.activity`) is removed from the manifest. So there's no AndroidX, Play services or Kotlin in
the app. `app/proguard-rules.pro` tells R8 about the libraries left out.

atvOS Launcher ships one way: through Google Play. Build the bundle, minified with R8, with:

```bash
./gradlew bundleRelease
```

It's signed with the upload key in `keystore.properties` (never committed), and Play re-signs it with
the app signing key. The bundle includes Google's encrypted dependency report, so Play can flag
outdated SDKs.

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Testers install it from Play's internal testing track. Donations can only be tried in a copy from
Google Play, by an account listed as a licence tester.

## Uninstalling

Before uninstalling, open Settings > **Uninstall** and select **Restore Default Launcher** (the TV's
own home screen comes back) and **Remove Power Control** (Android won't uninstall an app that holds
a device admin). Then Settings > Support > **App Info** has **Uninstall**.

## Layout

```
app/src/main/java/app/atvos/launcher/
  HomeActivity.java      lifecycle, background loading, launching apps and programs
  HomeView.java          all home-screen drawing, animation and remote input
  SetupFlow.java         the setup steps, as a card over the home screen
  SettingsScreen.java    Settings (the switches in Control Center), as tvOS Settings
  Logo.java              the atvOS logo: the wordmark cut out of a tvOS-shaped tile
  Donation.java          Settings > Support > Donate, through Google Play billing
  SettingsArt.java       the setup steps' pictures of the TV's own Settings screens
  SelfAdb.java           the TV's own adb, for setup's fixed home-screen commands only
  A11y.java              the screen-reader tree for HomeView (TalkBack)
  ControlCenter.java     Control Center layout, focus and drawing
  Controls.java          what the Control Center tiles read and do
  Apps.java              launchable apps, tile artwork, order, folders and hidden apps
  Backdrops.java         top-shelf images, app logo backdrops, download cache, blur
  Recommendations.java   apps' Watch Next / preview programs from the TV provider
  Profile.java           Google profile photo or letter avatar
  PowerAdmin.java        force-lock device admin
  SleepTimer.java        sleep timer alarm receiver
app/src/main/java/androidx/core/   three tiny stand-ins for the AndroidX calls billing makes
```
