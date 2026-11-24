# Corkboard

A home screen launcher for Android that looks and behaves like Pixel Launcher or Lawnchair, with most of their features cut. It is for people who want a plain, fast home screen with folders, widgets, a drawer and real control over icon shapes, and nothing else.

Install an app and it shows up on the home screen and in the app drawer. Drag it around, drop it into a folder, give it the icon shape or custom icon you want, add a widget an app provides. That is the whole product.

- No third-party libraries. Android framework and the Kotlin standard library only. No AndroidX, no Compose.
- No `INTERNET` permission. A launcher has no reason to talk to the network, and leaving the permission out proves it.
- Android 10 (API 29) and newer.

## What it does

- Home screen grid with multiple pages and free placement (default 4 x 6, adjustable from 3 to 6 columns and 4 to 8 rows).
- Dock: one row under the pages, same on every page.
- App drawer: swipe up. Alphabetical, search on top, Personal and Work tabs.
- New apps land on the home screen (toggle, on by default). Uninstalled apps disappear everywhere.
- Long-press and drag to rearrange. Items in the way slide aside, Pixel-style. Drag to the screen edge to change page.
- Folders: hold an app over another app for half a second. Rename, reorder, drag out.
- Widgets from installed apps, placed and resized on the grid. Corkboard ships no widgets of its own.
- Pinned shortcuts ("Add to home screen" from Chrome) and app shortcuts in the long-press menu.
- Icon shapes: system default, circle, squircle, rounded square, square, teardrop. Per item: shape, custom image, scale, Fill or Fit treatment, background color.
- Old square icons that Android parks inside a white circle get scaled up to fill the shape instead.
- Work profile: badged icons, Work tab, pause work apps.
- Hide apps, rename labels, lock the layout.
- Themes for the launcher's own surfaces: Follow system, Light, Dark, Verdigris, Kawaii Light, Kawaii Dark.
- Backup and restore of the layout and custom icons to one zip file.
- Swipe down anywhere on the home screen for the notification shade.

Not in v1, on purpose: a Google feed page, notification dots, icon packs, themed (monochrome) icons, Private space, double-tap to lock.

## Installing

1. Download `Corkboard-vX.Y.Z.apk` from the [Releases](https://github.com/NullAngst/Corkboard/releases) page.
2. Open it on the phone and allow your browser or file manager to install apps if Android asks.
3. Open Corkboard. While it is not the default home app, a card at the top says so. Tap **Set as default**.

App shortcuts, pinned shortcuts and pausing work apps only work once Corkboard is the default home app. Before that, those features hide themselves.

## Using it

- **Tap** an app to open it. **Long-press** to lift it. Let go without moving to get its menu.
- **Swipe up** for the drawer, **swipe down** for notifications, **swipe left or right** for pages.
- **Long-press empty space** for Wallpaper, Widgets and Launcher settings.
- **Home button** while on the home screen closes whatever is open and jumps to page 1.

### Fixing an ugly legacy icon

Long-press the app, pick **Edit icon**. Treatment **Auto** already fills full-bleed square icons into the shape. If it still looks off, try **Fill** or **Fit**, nudge **Scale**, or set a **Background** color for Fit. The preview updates live.

## Things to know

- **Recents and gesture animations.** On a Pixel, the recents screen and the swipe-up-to-home animation belong to Pixel Launcher. With any third-party launcher set as home, recents keeps working but the home transition is plainer. Lawnchair gets around this only with root (QuickSwitch). Nothing to build here, just expect it.
- **Swipe down for notifications** uses a hidden Android API (`StatusBarManager.expandNotificationsPanel`). Google can block it in any release. If that happens the gesture quietly does nothing and the setting greys out with "Not supported on this Android version". Swiping down from the very top edge always works.
- **Widgets** need no permission in the manifest. Android shows its own "Allow Corkboard to create widgets" prompt the first time you add a widget from each app.
- **Restoring a backup** brings back the layout and custom icons. Widget IDs belong to the phone, so restored widget spots ask to be set up again with one tap each. Android's own cloud backup is off, so the only backup is the one you export.

## Permissions

| Manifest entry | Why |
|---|---|
| `REQUEST_DELETE_PACKAGES` | So Uninstall can open the system uninstall dialog. Normal permission, granted at install. |
| `EXPAND_STATUS_BAR` | So swipe down can open the notification shade. Normal permission, granted at install. |
| `<queries>` for MAIN + LAUNCHER | Lets Android 11+ show the launcher every app with a launcher icon, without `QUERY_ALL_PACKAGES`. |
| HOME + DEFAULT intent filter | Makes it selectable as the home app. |

## Building

Prerequisites: JDK 17 or newer (the workflow uses 21) and the Android SDK with platform 37, or Android Studio.

1. Clone the repo: `git clone https://github.com/NullAngst/Corkboard.git`
2. `cd Corkboard`
3. Point Gradle at your SDK, either with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=/path/to/Android/Sdk`.
4. `bash ./gradlew assembleDebug`, since the executable bit on `gradlew` may be missing.
5. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install`, or whichever way you prefer.

Local builds without a tag get version 0.0.0.

The only version numbers in the build are the Android Gradle Plugin in `build.gradle.kts` and the Gradle wrapper in `gradle/wrapper/gradle-wrapper.properties`. AGP 9 compiles Kotlin itself, so there is no separate Kotlin plugin version. Dependabot opens pull requests for both, and for the GitHub Actions versions, so nobody edits them by hand.

## Signing

Releases are signed by GitHub Actions with a keystore stored in repo secrets. Make it once:

1. `keytool -genkeypair -v -keystore corkboard.jks -alias corkboard -keyalg RSA -keysize 4096 -validity 10000`
2. `base64 -w0 corkboard.jks > corkboard.jks.b64` (on macOS: `base64 -i corkboard.jks -o corkboard.jks.b64`)
3. In the repo, go to Settings, Secrets and variables, Actions, and add four repository secrets:
   - `KEYSTORE_BASE64`: the contents of `corkboard.jks.b64`
   - `KEYSTORE_PASSWORD`: the keystore password
   - `KEY_ALIAS`: `corkboard`, or whichever alias you used
   - `KEY_PASSWORD`: the key password
4. Delete `corkboard.jks.b64`.

BACK UP THE KEYSTORE AND ITS PASSWORDS. If it is lost, no future APK can install over the current one. The only way forward is uninstalling the launcher, which wipes the layout and every custom icon unless you exported a backup first.

## Making a release

1. Go to Releases, draft a new release, create a new tag there (like `v1.2.0`), and publish it.
2. Go to Actions, click the **build** workflow, click **Run workflow**.
3. Pick that tag under "Use workflow from" and run it.
4. The workflow builds from that tag and attaches the signed `Corkboard-v1.2.0.apk` to the existing release, with a signed SLSA build provenance attestation.

Versions come from the tag: `v1.6.0` becomes versionName `1.6.0` and versionCode `10600`. Releases bump the minor number.

The workflow fails with a clear message if it was run on a branch instead of a tag, or if no release exists for that tag yet. Rerunning it on the same tag replaces the APK. Pushes to `main` and pull requests only build a debug APK and run lint; nothing is released on a tag push.

To check an APK's provenance: `gh attestation verify Corkboard-v1.2.0.apk --repo NullAngst/Corkboard`

## Credits and licence

GPL-3.0, same as Lawnchair. See [LICENSE](LICENSE).

Corkboard borrows ideas and ported logic from [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) (GPL-3.0) and [AOSP Launcher3](https://android.googlesource.com/platform/packages/apps/Launcher3/) (Apache 2.0). The cell reorder solver, the legacy icon content measuring and the widget span math are rewritten from Launcher3's approach to fit a framework-only stack. Files with ported logic keep the original copyright line and say where it came from.
