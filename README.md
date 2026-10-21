# atvOS Launcher

atvOS Launcher is a home screen for Google TV and Android TV, inspired by the tvOS Home Screen. It
puts your favourite apps in a glass dock, shows each app's top shelf behind it, and keeps the rest
of your apps in a grid below. A clock in the corner opens a Control Center.

atvOS Launcher is an independent project. It is not affiliated with or endorsed by Apple. Apple TV
and tvOS are trademarks of Apple Inc.

## Features

- **Top shelf.** The focused app in the dock fills the screen with what it recommends: a full-screen
  picture with a **Play** button, or a row of **Continue Watching** and **Recommended** cards.
- **Your layout.** Reorder apps, make folders and hide the apps you don't use.
- **Control Center.** Power, network, Do Not Disturb, Bluetooth, a sleep timer and shortcuts to
  inputs, accessibility, system settings and search.
- **Settings.** Light or dark appearance, 12- or 24-hour clock, and a profile picture from your
  Google account.
- **TalkBack.** Every tile, button and menu option is read aloud as you reach it.
- **Private.** atvOS Launcher collects nothing. It uses the network only for artwork and your
  profile photo, over HTTPS.

## Requirements

- A Google TV or Android TV device with Android 12 or later.
- On most Google TV and Android TV devices, **Developer options** and **USB debugging**, so that the
  Home button can open atvOS Launcher (see [Set up](#set-up)).

## Install

Install atvOS Launcher from
[Google Play](https://play.google.com/store/apps/details?id=app.atvos.launcher).

## Set up

On first launch, a card on the TV walks you through the steps that are left. It skips the steps
that are already done, shows a picture of each TV Settings screen, and opens that screen for you.
Select **Not Now** to skip setup. To run it later, open atvOS Settings > **Home Screen**.

1. **Turn On Developer Options.** Opens the TV's About screen. Select the build row seven times.
2. **Turn On USB Debugging.** Opens Developer options.
3. **Allow USB Debugging.** Select **Set Up**, then **Allow** on the TV's prompt. atvOS Launcher
   becomes the home app and turns off the TV's built-in home screen.
4. **Make It Your Home Screen.** On a TV whose home app is not locked, Android's home app dialog
   opens instead of steps 1 to 3.
5. **Add Your Profile Picture** (optional). Choose your Google account.

**Why steps 1 to 3:** the built-in home screens of Google TV and Android TV keep the Home button even
after you choose a different home app. atvOS Launcher turns them off through the TV's own debugging
connection (127.0.0.1:5555). It sends only two fixed commands: one that turns off the built-in home
screen and one that turns it back on. It uses the debugging connection for nothing else.

When setup ends the first time, the TV asks you to allow access to app recommendations.

## Use

### Remote

| Button | Action |
| --- | --- |
| D-pad | Moves the focus. Down from the dock shows all your apps. |
| Select | Opens the focused app. |
| Long press Select, or Menu | Opens the menu of the focused app (see [Apps and folders](#apps-and-folders)). |
| Up from the dock | Opens the app's top shelf (see [Top shelf](#top-shelf)). |
| Up again, or Up on an app with no top shelf | Focuses the clock. Select opens the Control Center. |
| Long press Down on the last row | Opens the **Hidden** section. |
| Back or Home | Goes back to the dock. |

### Top shelf

When an app in the dock has the focus, its top shelf fills the background. The top shelf comes from
what the app publishes for the TV home screen:

1. its **Watch Next** programs (what you are watching), the most recent first;
2. the programs in its **preview channels**.

atvOS Launcher keeps up to eight programs for each app.

- **Full-screen pictures (1080p or larger).** "Press up for full screen" shows above the dock. Press
  Up: the dock and the clock move away, and the title, the details, the description and **Play**
  show at the bottom left. If the app has more programs, Left and Right show them. Select plays the
  program. The dock keeps the last program that you showed.
- **Smaller pictures.** The programs show as a row of 16:9 cards, or square cards for music and
  podcasts, in a **Continue Watching** section and a **Recommended** section. A progress bar shows on
  programs that you started. Press Up to focus the first card, and Left and Right to move along the
  row. A title that is too long for its card scrolls.
- **Apps with no recommendations.** The app's launch screen shows if it is a full-screen picture.
  Otherwise, the app's logo shows large over a glow in the app's colour.

To choose what an app shows, open its menu > **Top Shelf…** and turn **Continue Watching** or
**Recommended** on or off. With both off, the app shows its logo.

When the focus moves into the grid, the top shelf stays behind it, blurred and dimmed.

### Apps and folders

The menu of an app has these options:

| Option | Action |
| --- | --- |
| **Edit Home Screen** | The app wiggles. Move it with the arrow keys. Select or Back puts it down. |
| **Move to…** | Moves the app to a **New Folder**, to one of your folders, to **Hidden**, or back to the **Home Screen**. |
| **Top Shelf…** | Turns the app's **Continue Watching** and **Recommended** on or off. Shows only for apps that publish them. |
| **App Info** | Opens the app's page in the TV Settings. |
| **Delete App** | Asks you to confirm, then opens Android's uninstall screen. Shows only for apps that you can uninstall. |

A folder can only be moved. Select a folder's name to rename it. An empty folder goes away.

Hidden apps are greyed out in the **Hidden** section below your apps. To open it, long press Down on
the last row. To show a hidden app again, open its menu > **Move to…** > **Home Screen**, or move it
up with **Edit Home Screen**. When you hide an app, a message shows how to open the Hidden section.

The order, the folders and the hidden apps are saved on the TV.

### Control Center

Focus the clock and press Select.

| Tile | Action |
| --- | --- |
| Status row | Shows the time and what is on (Bluetooth audio, Do Not Disturb, sleep timer). The switches open atvOS Settings. The cog opens the TV Settings. |
| Profile | Choose the Google account for your profile picture. |
| Power Off | Puts the TV to sleep, like the power button of the remote. A long press removes the permission to lock the screen. |
| Wi-Fi or Ethernet | Shows the connection. Opens the network settings. |
| Do Not Disturb | Turns Do Not Disturb on or off. The first time, the TV asks for access. Google TV has no screen for this access, so on Google TV the tile tells you that it is not available. |
| Bluetooth | Shows whether Bluetooth is on, or the connected headphones. Opens Remotes & Accessories. |
| Sleep Timer | Each press goes to the next time: Off, 15, 30, 60, 90 and 120 minutes. |
| Inputs, Accessibility, System, Search | Open the TV's input list, accessibility settings, system settings and search. |

The first time that you use Power Off or the sleep timer, the TV asks you to allow atvOS Launcher to
lock the screen.

### Settings

Open the Control Center and select the switches. Back goes to the previous page. Menu closes
Settings.

| Page | Rows |
| --- | --- |
| Settings | **Home Screen** (only when a different app has the Home button), **General**, **Profiles and Accounts**, **Uninstall**, **Support** |
| General | **About** (model, atvOS Launcher version, Android TV version), **Appearance** (Light or Dark), **Privacy**, **Acknowledgements**, **Time Format** (12-Hour or 24-Hour; the TV setting by default) |
| Profiles and Accounts | **Account** (your Google account), **Profile Picture** (Google Photo, Initial or None) |
| Uninstall | **Restore Default Launcher** (turns the TV's built-in home screen back on), **Remove Power Control** (removes the permission to lock the screen) |
| Support | **Website** (atvos.tv), **Open Play Store**, **App Info**, **Donate** (an optional tip through Google Play; it unlocks nothing) |

## Privacy and permissions

atvOS Launcher collects and shares nothing. Your Google account address and profile photo stay on
the TV. The access token for the photo is not stored. Donations go through Google Play.

| Permission | Use | How you allow it |
| --- | --- | --- |
| Access to TV listings (`READ_TV_LISTINGS`) | Reads the Watch Next and preview programs of your apps for the top shelf. | A prompt after the first setup. |
| Internet (`INTERNET`) | Downloads top shelf artwork and your profile photo, over HTTPS only. | At install. |
| Network state (`ACCESS_NETWORK_STATE`) | Shows the connection in the Control Center. | At install. |
| Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`) | The Do Not Disturb tile. | Android's Do Not Disturb access screen, if the TV has one. |
| Delete apps (`REQUEST_DELETE_PACKAGES`) | Opens Android's uninstall screen for **Delete App**. | At install. |
| Google Play billing (`com.android.vending.BILLING`) | **Donate**. | At install. |
| Alarms & reminders (`SCHEDULE_EXACT_ALARM`) | Puts the TV to sleep at the exact time of the sleep timer. | At install up to Android 13. From Android 14, the first sleep timer press opens **Alarms & reminders**. |
| Device admin, lock screen only | Power Off and the sleep timer. It cannot erase, reset or read anything. | A prompt the first time that you use Power Off or the sleep timer. |

## Uninstall

1. Open atvOS Settings > **Uninstall** and select **Restore Default Launcher**. The TV's built-in
   home screen comes back.
2. Select **Remove Power Control**. Android cannot uninstall an app that has device admin access.
3. Open Settings > **Support** > **App Info** and select **Uninstall**.

## Build from source

You need JDK 21 or later and the Android SDK. Android Studio writes the SDK location to
`local.properties`.

```bash
./gradlew assembleDebug
```

The only dependency is Google Play Billing, without its transitive libraries. The app has no
AndroidX, Google Play services or Kotlin.

To build a signed bundle for Google Play, add a `keystore.properties` file with your upload key.
Without it, release builds use the debug key.

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

```bash
./gradlew bundleRelease
```

### Continuous integration

- **GitHub Actions** builds and lints each push to `main` and each pull request. It then runs `ci/smoke-test.sh` on two Android TV emulators: Android 12 (Google TV) and Android 16 (Android TV).
- **Pre-release tags** (for example, `v1.0-pre.1`) also make a draft pre-release on GitHub, with the APKs and the app bundle.
- **GitLab CI** builds the release APK and the app bundle for pre-release tags and for `main`.
- **Release signing:** to sign GitHub builds with your upload key, add these repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

### Google profile photo

Google gives profile photos only to registered apps. To show photos in your own build:

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project.
2. Under **Google Auth Platform**, set up the app as **External**, with a home page and a privacy
   policy. Under **Audience**, select **Publish app** to make it **In production**. In Testing, only
   test users get a photo.
3. Under **Clients**, create an **Android** client with the package name of your build and the SHA-1
   of its signing key. For Google Play, use the app signing key from Play Console > **Test and
   release** > **App integrity**.

Without a client, the profile picture shows the first letter of the account.

### Donations

Donations need a consumable in-app product with the id `donation` in Play Console. Donations work
only in a copy installed from Google Play.

## License

atvOS Launcher is free software, licensed under the GNU General Public License v3.0. See
[LICENSE.md](LICENSE.md).
