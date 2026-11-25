# Changes

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
