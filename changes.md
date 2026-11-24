# Changes

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
