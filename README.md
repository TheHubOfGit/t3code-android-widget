# T3 Code Android Widget

A standalone Android home-screen widget that shows the live state of a T3 Code server
(running threads, requests waiting on you, failures), grouped by project.

It is a separate app: it never modifies T3 Code, so there is nothing to merge when T3 Code updates.
It talks to the server's HTTP API over Tailscale (or LAN) using a scoped bearer token.

**Status:** scoped, not started. See [docs/HANDOFF.md](docs/HANDOFF.md) for the full design,
API reference, build plan, and open questions.

## Quick start (once built)

On the host computer:

```bash
t3 serve --tailscale-serve
t3 auth session issue --scope orchestration:read --ttl 30d --label "android-widget" --token-only
```

Paste the server URL and token into the widget app's settings.

## Planned stack

Kotlin · Jetpack Glance · WorkManager · OkHttp · kotlinx.serialization · DataStore
