# Changes

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
