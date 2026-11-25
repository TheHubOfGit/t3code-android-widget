<img src="docs/icon.png" width="96" height="96" alt="" />

# T3 Code Android Widget

an android home-screen widget for keeping an eye on your T3 Code threads

A standalone Android home-screen widget that shows the live state of a T3 Code server
(running threads, requests waiting on you, failures), grouped by project.

It is a separate app: it never modifies T3 Code, so there is nothing to merge when T3 Code updates.
It talks to the server's HTTP API over Tailscale (or LAN) using a scoped bearer token.

**Status:** read-only widget in daily use (Material 3 Expressive pills, card or transparent style, tap opens the thread in the T3 Code app).

## Install

Every push to `main` runs `.github/workflows/release-apk.yml`, which tests, builds a signed release APK and publishes it as a
GitHub release (`t3-widget-v1.0.<run>.apk`). It is also listed in the personal app store. The older `v0.x-debug` releases are
debug-signed, so switching from one of those to a signed release needs a one-time uninstall.
See [docs/HANDOFF.md](docs/HANDOFF.md) for the full design, API reference, M0 spike results and open questions.

## Build

Needs JDK 17–21 (AGP 8.13 does not run on newer JDKs; Android Studio's bundled JBR works) and the Android SDK.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On an emulator, the host's server is at `http://10.0.2.2:3773`.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/app/t3widget/data/T3Constants.kt` | Every path/header/version coupled to T3 Code |
| `data/T3Models.kt`, `T3Api.kt` | Lenient DTOs + OkHttp client, URL/cleartext policy |
| `data/Classifier.kt` | Pure needs-you / failed / running / waiting / done logic; project icon, monogram and color ported from T3 Code |
| `data/FaviconStore.kt` | Repo favicons for projects without a custom icon: signs URLs via the WebSocket RPC `assets.createUrl` (read scope is enough), rasterizes SVG/PNG/ICO, caches 6 h |
| `data/ShellRepository.kt`, `SettingsStore.kt` | Cached snapshot (DataStore), Keystore-encrypted token |
| `sync/ShellSyncWorker.kt` | 15-min periodic + on-demand refresh |
| `widget/` | Glance widget, refresh action, tap-through |
| `settings/SettingsActivity.kt` | URL, token, test connection, hide previews |
| `scripts/record-fixture.sh` | Record a sanitized real snapshot as a test fixture |

## Quick start (once built)

On the host computer:

```bash
t3 serve --tailscale-serve
t3 auth session issue --scope orchestration:read --ttl 30d --label "android-widget" --token-only
```

Open **T3 Widget**, paste the server URL and token, tap **Save**, then add the *T3 Code status* widget.

## Planned stack

Kotlin · Jetpack Glance · WorkManager · OkHttp · kotlinx.serialization · DataStore
