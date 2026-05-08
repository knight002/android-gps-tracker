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

## Release
- Keystore: regenerated at `/tmp/release.jks`, password `password`.
- Sign: `zipalign` + `apksigner` from build-tools/34.0.0.
- Upload: `gh release create` with APK (asset name `GPSTracker-signed.apk`).
- Version naming: `v2.6` tag → `2.6` versionName in build.gradle.
