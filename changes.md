# Changes

2026.11.27 - Search order

* Drawer search puts the best matches first: apps whose name starts with what you typed, then apps with a later word starting with it, then apps that only contain it. Alphabetical within each group. Typing "ins" now lists Insget and Instagram before Universal Installer, and Gubbins last
* Since Enter opens the top result, it now opens the app whose name starts with what you typed
* Search matching moved into its own file, Search.kt, with unit tests

Reasons for changes: requested. Search kept the drawer's alphabetical order, so a name that only contained the typed letters could come first and Enter would open it.

2026.11.26 - Widget resize fix

* A widget made as wide as the grid can be resized back down again. Its minimum size never counts as more than the size it was placed at, the same rule Pixel Launcher (Launcher3) follows
* A widget's minimum resize size is ignored when it is larger than its minimum size, as Android's documentation says it should be
* The widget size math moved into its own file, WidgetSpans.kt, with unit tests

Reasons for changes: reported bug. A widget whose minimum width needs more cells on Corkboard's grid than the app expected was placed at its default size, could be grown to full width, and then couldn't be made smaller again. The handles took the touch but had nowhere smaller to go.

2026.11.26 - Bug fixes, Material You theme

* Widget resize: the handles near the left and right screen edges no longer trigger Android's back gesture under gesture navigation. Their grab areas are kept out of the edge gesture zone
* Private space: the Settings button in the Private tab now opens Android's private space settings (Android 16+). Android was silently dropping the launch, since the launcher has to say it allows that kind of start
* Private space: if Settings can't be opened, a short message says so, instead of nothing happening
* New theme: Material You (Android 12+). The launcher's own surfaces take your wallpaper's colors from Android, light or dark with the system, like Pixel Launcher
* TalkBack can no longer reach home screen icons hidden behind the open drawer, a folder or a menu
* README: notes on private space auto-lock, which Android itself handles

Reasons for changes: reported bugs (resize near the screen edges, the Settings button doing nothing) and a requested theme. The TalkBack issue turned up while testing on the emulator.

2026.11.26 - UX tweak

* The coming-home animation is a little quicker: each icon rises in 240 ms instead of 300, and rows follow each other 18 ms apart instead of 22. With a full grid the motion ends about a fifth sooner. The wait for Android's own close transition is unchanged
* The timings are named constants at the top of EnterAnimation.kt, so they're easy to adjust

Reasons for changes: the animation felt slightly slow after pressing Home.

2026.11.25 - Privacy page, notification dots, private space, undo, letter strip, code split into feature files

* New Settings, Privacy page: a switch for each feature that needs extra access, all off until turned on, each saying why it's off. Below them, every permission the installed app asks Android for, read from the app itself
* Notification dots, off by default. They need Android's Notification access, which can read every notification; Corkboard reads only which app each one is from, keeps that in memory, and never stores it. The listener ships disabled, so Android can't connect to it until the switch is on. Dot color: from the app icon, or the theme accent
* Private space (Android 15+), off by default. With it on, private apps show in a Private tab in the drawer with Unlock, Lock and, on Android 16+, Android's private space settings. They are never put on the home screen, in folders, in the layout file, in backups or in the icon cache. Respects Android 16's "hide when locked"
* Restoring a backup never turns a privacy switch on
* Undo bar after Remove, Remove from folder, Hide and drag to Remove, like Pixel Launcher. Deleting a widget, unpinning a shortcut and deleting a custom icon file wait until the bar is gone, so Undo brings back the same widget. It stays five seconds, or longer when Android's "Time to take action" accessibility setting asks
* Long-pressing in the gaps between icons opens the home menu (Wallpaper, Widgets, Launcher settings), like Pixel Launcher. Only an icon and its label count as the icon
* Widget settings in a widget's long-press menu, for widgets whose app supports setting them up again
* Letter strip down the side of the app drawer: touch or drag to jump to a letter, with a bubble and a light tick per letter (its own haptics switch). Hidden while searching
* The home screen code is split into one file per feature: HomeRoot (gestures), DragDrop, DropBar, Menus, Undo, EnterAnimation, Workspace, Reorder, IconView, PageDots, Shade, Ui. HomeActivity keeps only the views, lifecycle and launching
* Unit tests for the reorder solver, layout repair and reading layout.json, hostile files included. They run on every pull request
* The build check fails if the release APK carries any library besides the Kotlin standard library
* New PRIVACY.md: what is stored where, what each privacy switch reads, and every permission
* New strings in English, Spanish and German

Reasons for changes: requested features, with privacy-respecting defaults: nothing that needs extra access is on until it's chosen. Splitting the code by feature makes each part small enough to read and audit on its own. The tests and the library check keep the two things most likely to break quietly (layout repair, no extra libraries) from regressing.

2026.11.25 - Long-press fixes

* Long-pressing an app, folder, shortcut or widget opens its menu right away, while the finger is still down, like Pixel Launcher. Let go to use the menu, or keep moving: the menu closes and the drag carries on. Before, the menu only appeared after letting go
* Same in the drawer and in open folders
* Fixed: the Rename box looked empty in light mode; its text was white on the light dialog

Reasons for changes: the menu waiting for release felt broken next to Pixel Launcher. The Rename bug turned up while checking it on an Android 15 emulator.

2026.11.25 - New app icon

* New launcher icon: a heavy round C in cream with a copper pin as its full stop, on ink (#15191C). It replaces the tilted verdigris pushpin
* The themed icon (Android 13+) is the same C and dot as one silhouette
* Still three plain vector drawables (background, foreground, monochrome), so the APK size doesn't change

Reasons for changes: the old icon's shading, patina speckle and mid teal background read as AI-made. This one was picked from five flat designs shown in four deep palettes.

2026.11.25 - Bug fixes and security hardening

* Fixed: a widget dragged to a new spot turned invisible there until the launcher restarted
* Fixed: a long-press on a widget could fire twice, buzzing twice and opening the widget's menu over the drag
* Fixed: an open folder stayed on screen after an app inside it was uninstalled, though the folder itself was gone
* Fixed: holding an app over another copy of the same app made a folder holding one app twice
* Fixed: an image picked in Edit icon could be deleted before saving if any app installed or updated meanwhile; edits in progress now also survive the photo picker and rotation
* Fixed: a custom color could be typed as a name like "red", which then drew as Auto; only #RRGGBB and #AARRGGBB are taken
* Fixed: a failed automatic backup left a half-written zip that counted toward the backups kept and could push a good one out
* Fixed: a failing scheduled backup was retried on every return to the home screen; it now waits 30 minutes between tries, and a scheduled backup never starts while one is running
* Fixed: an unreadable layout.json was silently replaced by an empty layout. It is kept as layout.json.bad, and apps go to the drawer instead of spilling onto the home screen
* Fixed: long-press menus could run off the screen in landscape; they scroll now
* Fixed: widget ids from a setup that was cut off stayed allocated forever; they are released at start
* Fixed: on Android 11+, apps that provide widgets but have no launcher icon were missing from the widget picker when their widget is exported to other apps, and a restored widget spot from one was dropped as if the app were gone
* Export and import run off the main thread, so a big icon set can't freeze the launcher
* Drawer search ignores accents ("musica" finds "Música"), and the drawer sorts by the phone's language ("Ärzte" next to "Apps", not after "Zoom")
* The widget picker loads previews once instead of on every keystroke in its search box
* Less work per icon: shapes are built once, pixel scans aren't repeated, cache keys are cheaper
* Security: "Add to home screen" requests from other apps show a confirmation with the shortcut's name, icon and the app asking, as Android expects of launchers. Before, any app in the foreground could add icons without asking
* Security: backups are read into a scratch folder and checked before anything changes. Size limits per file and in total, file names that try to leave the icons folder are skipped, and damaged or deeply nested layout files are refused instead of crashing
* Security: layouts are repaired on load: duplicate or runaway ids, items off the grid or on top of each other, two dock icons in one slot, apps with spans
* Security: custom icon images are sampled down when decoded, so an oversized image in a backup can't crash the launcher on every start, and stored icon names are never used as file paths
* Security: automatic backup only writes to a folder this phone still has permission for, and restoring a backup keeps this phone's own backup and swipe-down settings
* Security: every screen has an empty task affinity, so another app can't place its activities in Corkboard's task
* Security: CI checks gradle-wrapper.jar against Gradle's published checksums, and the checkout no longer leaves the GitHub token on disk while the build runs
* Spanish and German strings for everything new

Reasons for changes: a full code review and security pass. Checked on an Android 15 emulator with a deliberately broken layout, a 10000 x 10000 icon image, oversized and hostile backup zips, a corrupt layout file, and test apps that request a pinned shortcut and provide a widget.

2026.11.25 - UX polish

* Coming back to the home screen from an app, the dock, icons and widgets now rise, grow and fade in, dock first and then the grid from the bottom row up, instead of appearing all at once
* The entry waits for Android's own close transition (up to 220 ms) so it plays on screen rather than under the closing app
* Skipped after unlocking, with a folder, menu or the drawer open, and when Android's "Remove animations" is on; Android's animation scale applies

Reasons for changes: returning home looked abrupt next to the unlock and recents transitions. Timing checked on an Android 15 emulator against the system's transition-finished callback.

2026.11.25 - Icon and UX fixes

* Fixed: old rounded-square icons (My Boy, My OldBoy, Note Pad) were shrunk onto a white circle; Auto now crops their transparent corners and fills the shape
* Fixed: Auto's background sampling only applies to icons on a solid plate, so open logos no longer vanish into their own edge color
* Fixed: Edit icon's Background did nothing on adaptive icons with a white plate (Total Commander); it now replaces the background layer, except for apps that draw their logo in that layer (Chrome)
* Fixed: folder previews didn't update after editing an app's icon
* Fixed: dragging a widget to the top row could drop it on Remove; the Remove bar now has its own band above the grid, and items land where they are drawn, not under the finger
* Fixed: haptics only working sometimes; Android's own long-press buzz cut Corkboard's off, the empty-space long-press fired twice, and the Android 14 tick effects were too faint. Haptics now use the vibrator's click, heavy click, tick and double click effects
* Generated monochrome icons for apps without one (PayPal and others), on by default with themed icons, with a contrast curve and Pixel-style white backdrop for old icons
* Per-app Themed look in Edit icon: Auto, Generate from icon, Your image, Off
* Search box in the widget picker
* Optional Apps button in the dock instead of swipe up to open the drawer
* VIBRATE permission added for haptics
* Spanish and German strings for everything new

Reasons for changes: bug reports and requests from real-device use, checked on an Android 15 emulator over adb, including the vibrator's own log of what played.

2026.11.24 - UX additions

* Haptics through Android's built-in effects: master switch plus a switch per moment (drawer, lift, folder, place, reorder, page flip, Remove and Uninstall)
* Home screen stays in the natural orientation; "Allow home screen rotation" setting
* Drag-to-remove bar at the top while dragging: Remove and Uninstall, like Pixel Launcher
* Automatic backup to a folder picked through Android's folder picker: on/off, after changes, daily, weekly or monthly, keep 1, 3, 5, 10 or 30, Back up now, status with last time or error
* Restoring a backup keeps this phone's automatic backup settings
* Spanish and German strings for everything new

Reasons for changes: requested additions to make rearranging and backups feel like Pixel Launcher without new permissions or dependencies.

2026.11.24 - Bug fixes

* Fixed: no gestures worked on an empty home screen (no drawer, no long-press menu, no page swipes). The gesture code read Android's built-in View.getOverlay(), which is never empty, instead of the launcher's own menu overlay, so it always thought a menu was open
* Fixed: a quick swipe up could close the drawer instead of opening it when the first frame of the drawer was slow to draw; the drawer now uses the lift-off point and opens on any upward swipe past a fifth of the screen or any upward fling
* A drawer left half-closed by an interrupted animation resets itself when the home screen comes back
* Long-pressing an app in the drawer and moving it now closes the drawer straight away so you can place it on the home screen
* Drawer background is fully opaque, so home screen icons no longer show through
* Status bar icons turn dark over a light drawer
* Popup menus no longer clip their last row
* README screenshots of the home screen, drawer and an open folder

Reasons for changes: the first real-device install showed a dead home screen. Reproduced and verified on an Android 15 emulator driven over adb.

2026.11.24 - Feature update

* Themed icons on Android 13+: each app's monochrome layer tinted with the wallpaper colors, toggle in settings, off by default
* Themed icons fall back to the normal icon when an app has no monochrome layer, the layer draws nothing, a custom image is set, or Android throws; launcher theme colors stand in if the wallpaper colors can't be read
* Disk icon cache for app icons on the home screen, dock, folders and drawer: 25 MB default, adjustable (Off, 10, 25, 50, 100, 200, 500 MB), least recently used out first, clear button
* Every UI string moved into resources
* Spanish and German translations (machine-drafted, not yet checked by a native speaker)

Reasons for changes: the v1 size and line targets were for the first release only. Themed icons match Pixel Launcher without trusting third-party icon packs, the cache cuts cold start work, and translations open it up to Spanish and German speakers.

2026.11.24 - Initial release

* Home screen grid with multiple pages, free placement, page dots and a dock
* Pixel-style drag and drop: items in the way slide aside, red cells when there is no room, drag to the edge to change page
* App drawer with search, Personal and Work tabs, and pause work apps
* New apps land on the first free cell (toggle), uninstalled apps disappear everywhere
* Folders: hold an app over another to make one, rename, reorder, drag out
* App-provided widgets: picker, bind and setup flow, resize handles, "Can't load widget" fallback
* Pinned shortcuts from "Add to home screen" and app shortcuts in the long-press menu
* Icon renderer: six global shapes, per-item shape, custom image, Fill or Fit, scale, background color, work badge
* Legacy full-bleed square icons fill the shape instead of sitting in a white circle
* Hide apps, rename labels, lock layout
* Themes: Follow system, Light, Dark, Verdigris, Kawaii Light, Kawaii Dark
* Backup and restore of the layout and custom icons to one zip
* Swipe down anywhere for the notification shade, failing safe if Android blocks the hidden API
* Verdigris pin adaptive icon with a monochrome layer
* GitHub Actions: debug build and lint on pushes and PRs, signed release with SLSA provenance on workflow_dispatch from a tag

Reasons for changes: first build, everything in the Corkboard build spec in one go.
