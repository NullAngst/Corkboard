# Changes

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
