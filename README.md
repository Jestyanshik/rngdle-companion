# RNGdle Companion

An unofficial Android client for [rngdle.com](https://www.rngdle.com/). It opens the original game in an Android WebView and adds bottom navigation, roll reminders and signed APK updates. The site supplies the roll, EP, badges, player data and animations.

See the [research and implementation stages](docs/RNGDLE.md) for the evidence, screen map and authenticated test gaps.

## Build and test

Use JDK 17, Android SDK 35 and Build Tools 35.0.0. Run the following command on Linux or macOS.

```sh
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use a checkout path with ASCII characters. Gradle's test worker can fail to load test classes when a parent directory contains Cyrillic characters.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Install the debug APK from app/build/outputs/apk/debug/app-debug.apk on Android 9 or later. The debug package is app.rngdle.companion.debug. The distribution package is app.rngdle.companion.

## Sign in and play

1. Tap Sign in in the Android header. This opens RNGdle's original email form inside the app. Enter your email and submit through the site's own Send Magic Link button. Complete any challenge presented by RNGdle.
2. When the app tells you to check your email, copy the direct HTTPS link from the message before opening it elsewhere. Return to the app and tap Paste link from email. It opens the link in the same WebView and confirms sign-in when the site displays its signed-in profile control. A manual paste dialog is available if Android does not provide a link from the clipboard.
3. Use Generate on the original page to roll. Browse rankings, friends and profile from the bottom bar. Site Settings are in the original page's menu. Android reminders and updates are in the bottom-bar settings.

The app uses Android's persistent WebView cookie and website storage. The website controls authentication, expiration and sign-out. There is no direct game API client. The native dialog holds the magic link in memory and submits it directly to the first-party site. Android backups are disabled to keep website state local to the installation.

RNGdle does not currently publish a Digital Asset Links association for the app. Email clients can open the link in the system browser, which has separate cookies. Copy the link first so that the app can redeem it inside its own session. The exact callback path and token format remain controlled by RNGdle.

For WebView diagnostics, filter Logcat on RNGdleWeb. Events contain status codes, dimensions, and state names. URLs, console contents, email addresses, and cookies are omitted.

If the site stays black, enable compatible site rendering in the app's settings. The WebView draws through the Android view hierarchy and uses a software layer when the option is enabled. It keeps the existing site session. The Android workflow can be run manually to capture signed-out game and rankings screens on an API 35 emulator.

## Reminders

Set the number of daily reminders from zero to eight. The default is three. The app picks a random time within each evenly spaced interval across the full calendar day in the phone's time zone. Once the original Play page shows a post-roll countdown, the app skips any remaining reminders for that UTC game day. A roll made elsewhere is detected when its result and countdown appear in the app.

The regular notification uses the Rare Roll chime. Its volume is adjustable from 0% to 100% and defaults to 40%. Android applies its normal notification settings on top of that level. The Alarm switch selects the Game Rise melody at the system alarm volume and uses the system alarm clock when Android grants exact-alarm access. Android can defer delivery when exact access is unavailable. WorkManager provides a delayed backup and the app restores the pending schedule after reboot.

The test notification uses the current sound mode and appears immediately when Android allows it. It has a separate notification ID and leaves the daily schedule and count alone.

## Publish and update

Create one distribution keystore outside the repository and back it up securely. Configure the following Actions secrets in a public GitHub repository.

| Secret | Value |
| --- | --- |
| RNGDLE_KEYSTORE_B64 | Base64-encoded bytes of the distribution keystore |
| RNGDLE_KEYSTORE_PASSWORD | Keystore password |
| RNGDLE_KEY_ALIAS | Distribution key alias |
| RNGDLE_KEY_PASSWORD | Distribution key password |

Set the repository variable RNGDLE_CERT_SHA256 to the distribution signing certificate's SHA-256 fingerprint. Use the same signing key and application ID for every release. Increase versionCode and versionName in app/build.gradle.kts, then push a tag matching the versionName, such as v0.1.0. The release workflow verifies the signing certificate and requires versionCode to exceed previous published release assets.

If a build fails before publication, run the Signed Android release workflow manually from the default branch and provide the existing release tag.

CI embeds the public repository name and publishes one asset named rngdle-companion-vcVERSION_CODE.apk. The app checks the latest stable GitHub Release and displays its notes. It compares the asset's versionCode, checks its GitHub SHA-256 digest and byte count, and validates the package, APK version and signing certificate before opening the Android installer. Android asks the user to approve installation and retains the existing app data when it updates the same package signed with the same key. Debug builds omit self-updates.

Use **Choose version** in the update settings to view stable GitHub releases and select a version. For an older version, download the verified APK and save it to **Downloads** using the Android file picker. Android requires uninstalling the current build before installing an APK with a lower versionCode. Uninstalling clears local settings and the WebView session. Open the saved APK from the device's file manager and sign in again. The APKs are also available from [GitHub Releases](https://github.com/Jestyanshik/rngdle-companion/releases).

## Manual verification

- Sign in on a physical Android device, restart the app, and verify the session survives. Exercise expired links and sign-out.
- With an authorized account, press Generate once and verify the original result, EP, badges, history, collection and countdown. Check profile edits, friend requests and Party if offered by the site.
- Exercise offline loading and retry, notification denial, exact-alarm denial, reboot, clock changes, and Android or HyperOS power management. Verify the daily reminder count, cancellation after a roll, and the chime and alarm melody in their respective modes.
- Publish two signed test releases with increasing version codes and use the updater to install the second over the first. Verify that the original account and reminder settings survive.
- Select the older release from **Choose version**, save its APK to Downloads, uninstall the current build, and install the saved APK from the file manager.
