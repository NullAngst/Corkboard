# Maintenance

Do this pass every couple of months, or right after a new Android release lands on your phone. It takes about an hour plus a day of normal use.

## 1. Merge Dependabot pull requests

Dependabot watches the Android Gradle Plugin version in `build.gradle.kts`, the two test libraries (JUnit and org.json) in `app/build.gradle.kts`, the Gradle wrapper, and the GitHub Actions versions in `.github/workflows/build.yml`.

1. Open the repo's Pull requests tab.
2. For each Dependabot PR, wait for the **build** check to go green, then merge it.
3. If an AGP bump fails, read the AGP release notes for that version (developer.android.com/build/releases/gradle-plugin). Major AGP versions sometimes change the DSL. Fix `app/build.gradle.kts`, push to the PR branch, merge once green.
4. If an Actions major version bump fails, check that action's release notes for renamed inputs.
5. The test libraries never reach the APK, so a bump there can only break the unit tests. If one does, read that library's release notes, fix the test, merge once green.

## 1.5. Unit tests and the library check

The build check runs `testDebugUnitTest` and `.github/scripts/check_runtime_deps.sh` on every pull request.

1. If a test fails after a change you meant to make, fix the test in the same pull request, and say why in `changes.md`. If it fails after a change you didn't expect to touch that code, the change broke something: fix the code.
2. If the library check fails, something added a library to the release APK. Corkboard ships Android's framework and the Kotlin standard library only. Find what pulled it in with `bash ./gradlew :app:dependencies --configuration releaseRuntimeClasspath` and remove it. Only add it to the `allowed` line in the script if it is truly needed, and say why in `changes.md`.
3. The tests live in `app/src/test/kotlin`. Add one when a bug turns up in the solver, layout repair or reading `layout.json`, so it can't come back.

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
   - Add a widget, resize it, remove it. With gesture navigation on, put a widget against the left edge, open Resize, and drag its left handle: the widget resizes and Android's back gesture doesn't fire. Same for the right edge.
   - "Add to home screen" from Chrome: a pinned shortcut lands on the home screen.
   - Long-press an app: its app shortcuts show at the top of the menu.
   - If you have a work profile: Work tab, pause and resume work apps, greyed icons while paused.
   - Edit icon on an old square icon: Auto fills the shape.
   - Reboot the phone and time the first home screen draw. Then set Icon cache size to Off, reboot, and compare. If the cache isn't making a difference, find out why before the next release.
   - Switch the phone's language to Spanish and German and skim Launcher settings and the long-press menus for text that overflows or reads wrong.
   - Change theme and icon shape from settings.
   - Import the backup you exported in step 2.

## 4.2. Haptics, rotation, drag-to-remove, automatic backup

1. Haptics: with Vibration feedback on, check each moment buzzes. Over USB, `adb shell dumpsys vibrator_manager` lists recent vibrations; Corkboard's should show as `finished`, not `cancelled_superseded`. Check each moment buzzes (open the drawer, lift an icon, make a folder, place an icon, push icons aside, flip a page while dragging, hover Remove). Turn one switch off and confirm only that moment goes quiet. Read the new Android version's notes for changes to `HapticFeedbackConstants`.
2. Rotation: turn the phone sideways on the home screen; it should stay upright. Turn on Allow home screen rotation and check it follows.
3. Drag an app to Remove and to Uninstall. Remove takes it off the home screen; Uninstall opens the system dialog and the icon returns until the app is gone.
4. Automatic backup: open the backup folder and confirm the newest `corkboard-auto-*.zip` is recent and that no more than the chosen number are there. Tap Back up now once. If the status shows an error, pick the folder again.

## 4.2.5. Coming home animation

Open any app, then press Home. The dock and icons should rise and fade in, bottom rows first, after the app's close animation, and end exactly in place. Lock the screen with an app open, unlock, and check the icons are simply there (the entry is skipped when the screen was off). Turn on Remove animations in Accessibility and check it is skipped. If a new Android version makes the icons sit hidden too long before moving, lower `ENTER_WAIT_MS` in `EnterAnimation.kt`; it is the longest they wait for `onEnterAnimationComplete`. The speed of the motion itself is `ENTER_DURATION_MS` and the two row delays next to it.

## 4.3. Themed and generated icons

With Themed icons and "Theme apps without a monochrome icon" on, scroll the drawer and look for generated icons that came out as a solid blob or an inverted plate. The generator lives in `generateMono` in `IconRenderer.kt`; the contrast curve (0.3 to 0.7) and the white backdrop for old icons are the two knobs. Compare against Pixel Launcher on the same apps if one is at hand.

## 4.5. Translations

When a string is added or changed in `values/strings.xml`, add or change it in `values-es/strings.xml` and `values-de/strings.xml` too. Lint fails the build otherwise. If a native speaker sends corrections, apply them and note it in `changes.md`.

## 4.6. Security checks

1. The build check runs `gradle/actions/wrapper-validation` before anything else. If it fails right after a Dependabot Gradle wrapper bump, rerun the job once, since the action fetches new checksums from gradle.org. If it still fails, DON'T MERGE: `gradle-wrapper.jar` is not a real Gradle release.
2. In Chrome, open any page, then menu, Add to home screen, Add. Corkboard's own "Add to home screen?" dialog should appear naming Chrome. Cancel adds nothing; Add places the icon.
3. Export a layout, then import it again. It should restore with a "Layout restored" message. The backup limits (4 MB layout file, 4 MB per image, 64 MB in total, 4096 files) are constants near the bottom of `Store` in `LayoutStore.kt`. Raise them only if a real backup ever hits them.
4. Private space (on the phone, if it has one; see 4.7 for the full check): with Settings, Privacy, Private space off, the drawer has no Private tab and none of its apps are in Personal or Work.
5. Read the new Android version's behavior changes for anything touching `LauncherApps.PinItemRequest`, `AppWidgetHost` or the storage access framework.

## 4.7. Privacy features

1. Settings, Privacy, the permission list: it is read from the installed app, so it can't lie. Check every entry has a plain description. An entry showing a raw permission name with "Not described in Corkboard yet" means a new permission got into the manifest. Find out why; if it belongs, add a description in `describe()` in `PrivacyActivity.kt` and in the tables in README.md and PRIVACY.md.
2. Notification dots, off: Android's Notification access screen (Settings, Apps, Special app access) should not list Corkboard at all, since the listener is disabled. Turn dots on, allow access, and check a dot shows on an app with a notification and goes when the notification is cleared. Try both dot colors. Turn dots off: the dots go at once, and Corkboard drops off the Notification access screen again.
3. Read the new Android version's notes for changes to `NotificationListenerService`, `Ranking.canShowBadge`, or how Notification access is granted.
4. Private space (Android 15+): turn the switch on, open the drawer, use the Private tab's Lock and Unlock. Long-press a private app: no Edit icon, Rename, Hide or Add to home screen. Export a backup and check `layout.json` in it has no keys ending in the private profile's number (`adb shell dumpsys user` lists it). On Android 16+, set the private space to hide while locked and check the tab hides when locked, and tap the tab's Settings button: Android's private space settings should open. If nothing opens, check the new version's notes for changes to `ActivityOptions.setPendingIntentBackgroundActivityStartMode`.
5. Read the new Android version's notes for changes to `LauncherApps.getLauncherUserInfo`, `LauncherUserInfo.getUserConfig`, `ACCESS_HIDDEN_PROFILES` and the private space settings intent.
5.5. Lock private space automatically (Android's own setting, in private space settings) set to Every time device locks: unlock the private space, lock and unlock the phone, open the Private tab. It should show locked. Android does the locking; Corkboard only shows it.
6. Undo: remove an icon, tap Undo, it's back in place. Remove a widget, tap Undo, the same widget is back (not a new setup). Remove one and wait: after five seconds the bar goes and the change is final.
7. Long-press between two icons: the home menu opens. Long-press an icon: its menu opens.

## 5. Write it down

Add an entry to `changes.md` for anything you changed, in the usual format, even if it is only dependency bumps.

## If the keystore is ever lost

No future APK can install over the current one. Export a layout backup from the installed version, uninstall it, make a new keystore (see "Signing" in README.md), replace the four repo secrets, release, install, and import the backup.
