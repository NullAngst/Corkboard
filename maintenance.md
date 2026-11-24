# Maintenance

Do this pass every couple of months, or right after a new Android release lands on your phone. It takes about an hour plus a day of normal use.

## 1. Merge Dependabot pull requests

Dependabot watches the Android Gradle Plugin version in `build.gradle.kts`, the Gradle wrapper, and the GitHub Actions versions in `.github/workflows/build.yml`.

1. Open the repo's Pull requests tab.
2. For each Dependabot PR, wait for the **build** check to go green, then merge it.
3. If an AGP bump fails, read the AGP release notes for that version (developer.android.com/build/releases/gradle-plugin). Major AGP versions sometimes change the DSL. Fix `app/build.gradle.kts`, push to the PR branch, merge once green.
4. If an Actions major version bump fails, check that action's release notes for renamed inputs.

## 2. Bump targetSdk and compileSdk for the newest Android

1. Check the newest stable API level at developer.android.com/about/versions.
2. Check the AGP release notes say the current AGP supports that API level. If not, wait for the AGP bump first.
3. In `app/build.gradle.kts`, set `compileSdk` and `targetSdk` to the new level.
4. Read that Android version's "Behavior changes: apps targeting Android N" page. Things that have bitten launchers before: edge-to-edge enforcement, predictive back, broadcast receiver export flags, package visibility, background activity starts.
5. Push to a branch, open a PR, make sure the check job is green, merge.

## 3. Swipe-down check (launcher-specific)

Swipe down for notifications calls a hidden API, `StatusBarManager.expandNotificationsPanel()`, through reflection. It fails safe, but there is one failure the launcher cannot see: the call succeeds and the shade stays shut.

1. On the newest Android release, and the current beta if the phone runs one, swipe down mid-screen on the home screen. The shade should open.
2. Open Launcher settings and confirm "Swipe down for notifications" is not greyed out as unsupported.
3. Read the new Android version's behavior changes and its non-SDK interface restrictions list for anything touching `StatusBarManager`.
4. If it broke, see how other open-source launchers such as Lawnchair handle it now and port the fix. If nobody has one, set `swipeDown` to off by default in `Settings` in `LayoutStore.kt` and note it in `changes.md`.

## 3.5. Themed icons check

Themed icons read each app's monochrome layer (`AdaptiveIconDrawable.getMonochrome()`) and the wallpaper colors (`android.R.color.system_accent1_100`, `system_neutral1_800`, `system_neutral2_700`). Both are public framework API, and every failure falls back to the normal icon, so a break shows up as icons quietly not theming rather than a crash.

1. With Themed icons on, check a few apps known to ship monochrome icons (Google apps, most Pixel apps). They should be tinted.
2. Change the wallpaper. Icons should re-tint after the home screen comes back.
3. Read the new Android version's notes for changes to themed icons. Pixel Launcher has started forcing a themed look onto apps with no monochrome layer; if that becomes a framework API, consider using it.



1. Make a release (see "Making a release" in README.md), or install the debug APK from the check job.
2. Export a layout backup first, from Launcher settings.
3. Install over the current version. It must install as an update; if Android refuses, the signing key changed. Stop and find the original keystore.
4. Use it as your home screen for a day. Check:
   - Install and uninstall an app: it appears on the home screen and drawer, then disappears from both.
   - Drag an app onto another app, hold, drop: a folder forms. Drag one back out.
   - Drag an app onto an occupied cell: neighbors slide aside.
   - Add a widget, resize it, remove it.
   - "Add to home screen" from Chrome: a pinned shortcut lands on the home screen.
   - Long-press an app: its app shortcuts show at the top of the menu.
   - If you have a work profile: Work tab, pause and resume work apps, greyed icons while paused.
   - Edit icon on an old square icon: Auto fills the shape.
   - Reboot the phone and time the first home screen draw. Then set Icon cache size to Off, reboot, and compare. If the cache isn't making a difference, find out why before the next release.
   - Switch the phone's language to Spanish and German and skim Launcher settings and the long-press menus for text that overflows or reads wrong.
   - Change theme and icon shape from settings.
   - Import the backup you exported in step 2.

## 4.5. Translations

When a string is added or changed in `values/strings.xml`, add or change it in `values-es/strings.xml` and `values-de/strings.xml` too. Lint fails the build otherwise. If a native speaker sends corrections, apply them and note it in `changes.md`.

## 5. Write it down

Add an entry to `changes.md` for anything you changed, in the usual format, even if it is only dependency bumps.

## If the keystore is ever lost

No future APK can install over the current one. Export a layout backup from the installed version, uninstall it, make a new keystore (see "Signing" in README.md), replace the four repo secrets, release, install, and import the backup.
