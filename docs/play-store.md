# Publishing atvOS Launcher on Google Play

This guide takes atvOS Launcher from the repository to a public listing on Google Play for Google TV
and Android TV, with donations through Google Play Billing. The store text and images are in
[`fastlane/metadata/android/en-GB/`](../fastlane/metadata/android/en-GB).

The app already meets the technical requirements of Google Play in 2026:

| Requirement | atvOS Launcher |
| --- | --- |
| Target API level (Android TV: 34 or later) | 36 |
| Play Billing Library (8 or later) | 9.1.0 |
| 64-bit and 16 KB page sizes (TV, from August 2026) | No native code, so both are met |
| Privacy policy in the app and in Play Console | <https://atvos.tv/privacy>, shown in Settings > General > Privacy |
| TV quality: Back leaves the app (TV-DB), no web browser (TV-WB), banner, D-pad only | Done |

## 1. Before you start

1. **Upload key.** Make it once and keep a backup outside the repository:

   ```bash
   keytool -genkeypair -v -keystore ~/keys/atvos-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

   Then either add `keystore.properties` at the root of the repository (see the README), or add the
   signing secrets to GitHub and GitLab:

   ```bash
   base64 -i ~/keys/atvos-upload.jks | pbcopy
   ```

   Paste the result as `ANDROID_KEYSTORE_BASE64`, and add `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS` (`upload`) and `ANDROID_KEY_PASSWORD`. Bundles without these are signed with
   a debug key, and Google Play refuses them.
2. **Domain.** In [Google Search Console](https://search.google.com/search-console), add
   `atvos.tv` as a domain property and verify it with the DNS TXT record (in Cloudflare: DNS >
   Records > Add record > TXT). The profile photo OAuth app needs this (step 9).

## 2. Developer account

1. Open [Play Console](https://play.google.com/console) and create a **personal** developer account
   (one-time fee of US$25).
2. Verify your identity with an official ID, and verify that you have an Android device with the
   Play Console app. This can take a few days.
3. New personal accounts must run a **closed test with at least 12 testers, opted in for 14 days in
   a row**, before the app can be published (step 10). An organisation account (with a D-U-N-S
   number) does not have this requirement.

## 3. Payments profile (for donations)

1. Go to **Setup > Payments profile** and create a merchant account: legal name, address, bank
   account for payouts, and tax details.
2. Google asks whether you are a **trader** under EU law. If you are, your name, address and contact
   details show on the store listing for users in the European Union.
3. Google keeps a service fee on each donation and pays out monthly.

## 4. Create the app

**Home > Create app**:

| Field | Value |
| --- | --- |
| App name | atvOS Launcher |
| Default language | English (United Kingdom) – en-GB |
| App or game | App |
| Free or paid | Free |

Accept the declarations (Developer Program Policies, US export laws).

Then **Setup > Advanced settings > Form factors > Add release type > Android TV**. Confirm the opt-in
and agree to the TV review policy. Do this early, so the TV review runs while the closed test runs.

## 5. App content (Policy > App content)

| Section | Answer |
| --- | --- |
| Privacy policy | `https://atvos.tv/privacy` |
| Ads | No, the app does not contain ads. |
| App access | All functionality is available without special access. Add instructions for reviewers anyway (below). |
| Content rating | Category: **Utility, Productivity, Communication, or Other**. Violence, sexuality, language, controlled substances, gambling: **No**. Users can interact or exchange content: **No**. Shares the user's location: **No**. Digital purchases: **Yes** (the donation). Expected rating: Everyone / PEGI 3, with "In-App Purchases". |
| Target audience | **13–15, 16–17 and 18 and over**. Choosing an age under 13 brings in the Families policy. Unintentional appeal to children: **No**. |
| News app | No |
| Data safety | See below. |
| Government app | No |
| Financial features | None |
| Health | No |
| Advertising ID | No, the app does not use an advertising ID. |

**Instructions for reviewers** (App access): "No account or login is needed. On first launch,
select Not Now to skip setup and use the home screen. On Google TV and Android TV, the built-in home
screen keeps the Home button. The optional setup turns it off through the TV's own debugging
connection (Developer options > USB debugging), with fixed commands, and Settings > Uninstall >
Restore Default Launcher turns it back on. Setup can use Accessibility instead: the user turns on
atvOS Launcher Remote Buttons. Donate is in atvOS Settings > Support."

**Data safety.** atvOS Launcher sends no user data to you or to anyone else:

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |

Why this is correct:
- The layout, settings, account address and profile photo stay on the TV, and backups are off.
- The profile photo comes from Google, at the user's request, with Android's own account and consent
  screen. atvOS sends nothing about the user to Google; it receives the photo address.
- Top shelf artwork is downloaded from the addresses that the user's apps publish. atvOS sends no
  user data with these requests.
- Donations are processed by Google Play. atvOS receives only that a purchase is complete, and keeps
  nothing.

If a future version adds analytics, crash reporting or a server, update this form first.

**Accessibility API (Policy > App content > Sensitive app permissions, or the AccessibilityService
declaration that Play Console asks for after the upload).** atvOS Launcher Remote Buttons is not an
accessibility tool (`isAccessibilityTool` is false), so Play asks for a declaration:

| Question | Answer |
| --- | --- |
| Core functionality | A home screen (launcher) for TVs. The service makes the remote's Home and Settings buttons work for it: holding Home opens the Control Center, the Settings button opens the TV's settings, and, without USB debugging, Home opens the launcher in place of the TV's built-in home screen. |
| Data accessed | Key events of the remote (it acts only on Home and Settings), and the window changes of the TV's built-in home app only, when it stands in for it. No window content (`canRetrieveWindowContent` is false). |
| Data use and sharing | Used only on the TV to open the launcher, the Control Center or Settings. Nothing is stored or sent. |
| Prominent disclosure | Setup shows it before the user turns the service on: the "Allow USB Debugging" step (`setup_allow_body`) and the "Turn On Remote Buttons" step (`setup_buttons_body`). Settings > General > Remote Buttons repeats it. |
| Video | Record setup on a TV: the disclosure, turning the service on, then holding Home for the Control Center. Upload it to YouTube (unlisted) and paste the link. |

Also add the service to the privacy policy (done: [atvos.tv/privacy](https://atvos.tv/privacy)) and
answer **No** to "collects or shares user data" in Data safety: the key events stay on the TV.

**Permissions.** No other declaration form applies:
- `SCHEDULE_EXACT_ALARM` (the sleep timer) needs no declaration. Only `USE_EXACT_ALARM` does.
- `REQUEST_DELETE_PACKAGES` opens Android's own uninstall screen and is not restricted.
- The device admin uses only the lock policy (Power Off and the sleep timer). Its description in the
  prompt says what it does and how to remove it.

## 6. Store listing (Grow users > Store presence > Main store listing)

Copy from [`fastlane/metadata/android/en-GB/`](../fastlane/metadata/android/en-GB):

| Field | File |
| --- | --- |
| App name (30) | `title.txt` |
| Short description (80) | `short_description.txt` |
| Full description (4,000) | `full_description.txt` |
| App icon, 512 × 512, 32-bit PNG | `images/icon.png` |
| Feature graphic, 1024 × 500, 24-bit PNG | `images/featureGraphic.png` |
| Android TV banner, 1280 × 720, 24-bit PNG | `images/tvBanner.png` |
| Android TV screenshots, 1920 × 1080, 24-bit PNG (5 of up to 8) | `images/tvScreenshots/` |

The Play Console also asks for **phone screenshots**. atvOS Launcher is TV-only
(`android.software.leanback` is required), so the TV screenshots are the ones that show; if the form
still insists on phone screenshots, upload two of the TV screenshots there too.

**Store settings:** category **Personalization**, tags such as Launcher and Home screen, contact
email (public), website `https://atvos.tv`.

The screenshots show demo apps with original artwork, not real streaming apps, so that no other
company's logos or programmes appear in the listing.

## 7. First build and internal test

1. **Test and release > Setup > App integrity:** keep **Play App Signing** on. Google holds the app
   signing key; you upload with the upload key.
2. Build the bundle: push a version tag (`git tag v1.0 && git push origin v1.0`) and download
   `atvos-launcher-v1.0.aab` from the draft release, or run `./gradlew bundleRelease` with
   `keystore.properties`. Check `SHA256SUMS` and, on GitHub,
   `gh attestation verify atvos-launcher-v1.0.aab --repo shivangswain/atvos-launcher`.
3. **Testing > Internal testing > Create new release:** upload the `.aab`, add release notes from
   `changelogs/1.txt`, and roll out to an email list with your own account.
4. Every later upload needs a higher `versionCode` in `app/build.gradle.kts`.

## 8. The donation product

The first upload adds the billing permission, which Play Console needs before you can add products.

1. **Monetize with Play > Products > One-time products > Create one-time product.**
2. Product ID: **`donation`**, exactly: the app looks for this ID. An ID can never be reused.
3. Name "Donation", description "A one-time donation to support atvOS Launcher. It unlocks
   nothing."
4. Add a purchase option: type **Buy**. Set the prices, for example ₹99, US$4.99, £4.99, 9,99 zł,
   ¥499, ₱99 and ₩4,999, and let Play convert the rest.
5. **Activate.** The app consumes each donation at once, so people can donate again.
6. **Setup > License testing:** add your Google account. Test donations are then free.

**Test on your TV.** The copy from Google Play is signed with Google's key, so it cannot install over
a copy that you installed yourself. On the TV: atvOS Settings > Uninstall > **Restore Default
Launcher**, then **Remove Power Control**, then uninstall atvOS. Join the internal test from the
link in Play Console, install atvOS from Google Play on the TV, and run setup again. Settings >
Support > Donate shows the price.

## 9. Profile photo for every user (Google Cloud console)

1. Open the project of the app in the [Google Cloud console](https://console.cloud.google.com/) >
   **Google Auth Platform**.
2. **Branding:** app name, support email, home page `https://atvos.tv`, privacy policy
   `https://atvos.tv/privacy`, authorized domain `atvos.tv` (verified in step 1).
3. **Data access:** only `.../auth/userinfo.profile`. It is a non-sensitive scope, so the app does
   not need Google's app verification.
4. **Clients:** an **Android** client for package `app.atvos.launcher` with the SHA-1 of the **app
   signing key** from Play Console > **Test and release > App integrity**. Keep the clients for your
   upload and debug keys for copies that you install yourself.
5. **Audience > Publish app.** The status changes from Testing to **In production**, and every
   Google account can show its photo.

## 10. Closed test (12 testers, 14 days)

1. **Testing > Closed testing > Create track**, upload the same bundle, and add at least 12 testers
   (an email list or a Google Group). Each tester needs a Google TV or Android TV device.
2. Share the opt-in link. Testers must stay opted in for **14 days in a row**, and Google checks that
   they use the app.
3. Fix what they report with new builds (higher `versionCode`) on the same track.

## 11. Production

1. **Dashboard > Apply for production.** Answer the questions about the closed test, the app and
   its readiness. Google replies in about seven days.
2. **Production > Create new release**, upload the bundle, and roll out. Start with a staged
   rollout (for example 20%) if you want to watch for problems.
3. The Android TV review runs separately. Its status is under **Pricing and distribution >
   Android TV**: Pending, Approved or Not approved.

## 12. After launch

- Set `onPlay: true` in `website/src/site.ts` and push: the website shows **Get it on Google
  Play**.
- The Google Play link in the README works from now on.
- Each new version: raise `versionCode` and `versionName`, push a version tag, and upload the
  bundle from the draft release.
