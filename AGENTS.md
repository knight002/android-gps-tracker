# GPS Tracker — Project Notes

## MapView Initialization (tap-on-list hang fix)
- `MapView` must NOT be inflated in XML. Use `FrameLayout` container + programmatic creation after data loads.
- Reason: osmdroid `MapView` constructor blocks main thread (tile cache DB, tile provider, renderer).
- Flow: spinner from XML → coroutine loads data on IO → `MapView` built on main thread after.
- See `SessionDetailActivity.kt` header comment for full details.

## Auto-Update
- URL: `https://github.com/knight002/android-gps-tracker/releases/latest` (web, not API — avoids rate limiting)
- Follows redirect, extracts tag from final URL, constructs download URL as `.../download/{tag}/GPSTracker-signed.apk`
- Tag `v` prefix stripped before version comparison.

## App Shortcut
- Dynamic shortcut registered in `MainActivity.onCreate()` via `ShortcutManager.setDynamicShortcuts()`
- Static XML fallback in `res/xml/shortcuts.xml` for stock launchers.
- Intent uses `android.intent.action.VIEW` with `shortcut_action` extra.

## Tracking Status
- Three states: `READY`, `TRACKING`, `DWELLING`
- Dwell = configurable seconds (default 15, stored in prefs as `dwell_time_seconds`) without exceeding movement threshold (default 20m).
- GPS interval adjusts dynamically: tracking interval (default 5s) while moving, dwelling interval (default 30s) while stationary. Both configurable in Settings.
- `dwellJob` must be set to `null` both inside the completed dwell coroutine AND in the DWELLING→TRACKING transition, otherwise the dwell timer only fires once (completed Job ref prevents `null` check from passing).
- GPS noise reduction: spike rejection (discard fixes with speed >15 m/s) + EMA smoothing (α=0.3) on coordinates for dwell detection. Raw coords saved to DB.

## Release
- Keystore: regenerated at `/tmp/release.jks`, password `password`.
- Sign: `zipalign` + `apksigner` from build-tools/34.0.0.
- Upload: `gh release create` with APK (asset name `GPSTracker-signed.apk`).
- Version naming: `v2.8` tag → `2.8` versionName in build.gradle.
