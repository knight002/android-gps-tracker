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
- `ShortcutStartActivity` (v2.11+): transparent theme, starts tracking service directly without showing app UI
- If permissions missing, opens MainActivity to request them
- Theme: `Theme.GPSTracker.Transparent` with `android:excludeFromRecents="true"`, `android:noHistory="true"`

## Tracking Status
- Three states: `READY`, `TRACKING`, `DWELLING`
- Dwell = configurable seconds (default 15, stored in prefs as `dwell_time_seconds`) without exceeding movement threshold (default 20m).
- GPS interval adjusts dynamically: tracking interval (default 5s) while moving, dwelling interval (default 30s) while stationary. Both configurable in Settings.
- `dwellJob` must be set to `null` both inside the completed dwell coroutine AND in the DWELLING→TRACKING transition, otherwise the dwell timer only fires once (completed Job ref prevents `null` check from passing).
- GPS noise reduction: spike rejection (discard fixes with speed >15 m/s) + EMA smoothing (α=0.3) on coordinates for dwell detection. Raw coords saved to DB.
- **v2.10 fix**: Dwell exit uses FIXED `dwellOriginLat/Lng` (set when entering DWELLING), not step-by-step from previous fix. Previously: with 50m threshold, GPS drift of 20m + movement of 30m = both steps <50m = never exited DWELLING.
- **v2.10 fix**: Spike rejection uses `lastFixLat/Lng/Timestamp` (updates every valid fix), not `lastRecorded*` (only updates in TRACKING). Previously: spike rejection broken in DWELLING because `lastRecordedTimestamp` was frozen.

## Session Settings (v2.12+)
- Each session stores its settings in the DB (4 columns in `sessions` table)
- TrackingService saves current prefs when creating session
- SessionExporter reads settings from session row (not from current prefs)
- Old sessions get default values via migration

## Release
- Keystore: regenerated at `/tmp/release.jks`, password `password`.
- Sign: `apksigner` from build-tools/34.0.0.
- Upload: `gh release create` with APK (asset name `GPSTracker-signed.apk`).
- Version naming: `v2.12` tag → `2.12` versionName in build.gradle.
- DB migration (v1→v2): Adds 4 settings columns to `sessions` table (movementThresholdM, dwellTimeS, trackingIntervalS, dwellingIntervalS) with defaults (20.0, 15, 5, 30).