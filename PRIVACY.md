# Privacy

Corkboard is a home screen. It shows your apps, widgets and shortcuts and keeps a layout file. It collects nothing, sends nothing and has nothing to sell.

## What it can't do

- It has no `INTERNET` permission, so Android doesn't let it open network connections. Nothing it knows can leave the phone over the network.
- No analytics, crash reporting, ads or tracking code. The release APK holds Android's framework and the Kotlin standard library only. The build check fails if any other library ends up in it (`.github/scripts/check_runtime_deps.sh`).
- Android's cloud backup is off (`allowBackup="false"`), so your layout isn't copied to Google's backup either.

## What it stores, and where

Everything below is in Corkboard's private storage on the phone, which other apps can't read.

| What | Where | Holds |
|---|---|---|
| Layout | `files/layout.json` | Pages, dock, folders, which app or shortcut or widget sits where (by component name and id), hidden apps, renamed labels, icon settings, launcher settings. |
| Custom icons | `files/icons/` | Images you picked in Edit icon. |
| Icon cache | `cache/icons/` | Rendered app icons, so start-up is quick. 25 MB by default, can be turned off. Android may clear it at any time. |

Backups exist only when you make them: an export to a file you pick, or automatic backups to a folder you pick. A backup is `layout.json` plus your custom icons, nothing else.

Nothing about your notifications or your private space is ever written to storage or to a backup.

## Off until you turn them on

These are under Launcher settings, **Privacy**. Each is off on a new install, and restoring a backup never turns one on.

### Notification dots

Off because Android only shares notification counts with apps that have **Notification access**, and that access lets an app read every notification in full: messages, senders, one-time codes. Android has no narrower version of it.

With dots on, Corkboard reads this from each notification and nothing more:

- which app posted it, and from which profile (personal, work, private)
- whether it is a group summary, so a group isn't counted twice
- whether the app allows dots on that notification's channel
- whether it is an ongoing notification in the app's catch-all channel (music, downloads), which Pixel Launcher also skips

It never reads the title, text, people, pictures or actions. The result is a set of "app#profile" names held in memory and redrawn as dots. It is never saved, and it's dropped when dots go off.

The notification listener ships disabled in the app's manifest. Until you turn dots on, Android has nothing to connect, even if Notification access were granted by mistake. Turning dots off disables it again right away. You can also take Notification access back in Android's settings; the Privacy page links there and shows whether it's granted.

All of this is in `Dots.kt`. The class `DotListener` is the only code that touches notifications.

### Private space (Android 15 and newer)

Off so a private space, and the apps in it, never appear unless you choose.

Android only shows a private space to the default home app, and only if that app asks for `ACCESS_HIDDEN_PROFILES`. Corkboard asks for it (a normal permission, granted at install) so the switch can work. With the switch off, Corkboard notes that a private profile exists, so it can leave it out, and lists none of its apps.

With it on:

- Private apps show in a **Private** tab in the drawer and nowhere else. They are never put on the home screen, in the dock, in folders, in the hidden apps list, in `layout.json` or in backups.
- Their icons stay in memory only, never in the icon cache on disk.
- Their app menu has no entries that would save anything (no Edit icon, Rename, Hide or Add to home screen), and they can't be dragged out of the drawer.
- "Add to home screen" requests from private apps are refused, and their widgets aren't offered in the widget picker.
- Unlock and Lock go through Android's own private space lock. On Android 16, if you set the private space to hide while locked, the tab hides too.

All of this is in `PrivateSpace.kt`, plus the separate private app list in `AppRepository.kt`.

## Permissions

Settings, Privacy lists every permission the installed copy of Corkboard asks for, read from the app itself. At the time of writing:

| Permission | Why |
|---|---|
| `REQUEST_DELETE_PACKAGES` | Uninstall opens Android's own uninstall prompt. Android asks you to confirm; Corkboard can't remove anything by itself. |
| `VIBRATE` | Haptic feedback. |
| `EXPAND_STATUS_BAR` | Swipe down on the home screen for the notification shade. |
| `ACCESS_HIDDEN_PROFILES` | Lets Android show a private space to the default home app. Unused while the Private space switch is off. |

All four are normal permissions, granted at install with no prompt. Notification access is not a permission in that sense: Android grants it only in its own settings, when you allow it there.

Widgets need no permission. Android asks "Allow Corkboard to create widgets?" the first time you add a widget from each app.

## Questions and reports

Open an issue at [github.com/NullAngst/Corkboard](https://github.com/NullAngst/Corkboard/issues). The code is GPL-3.0, so anything above can be checked against the source.
