# T3 Code Android Widget — Handoff Document

**Status:** Scoped, not started
**Date:** 2026-11-25
**Source reviewed:** `pingdotgg/t3code` @ `e2c3c425` (Nov 24, 2026). All file references below are relative to that repo.

---

## 1. Goal

A **standalone Android app** whose only job is a home-screen widget that shows the live state of a T3 Code server: which agent threads are running, which are waiting on the user (approvals / questions), and which have failed, grouped by project. A later version adds quick actions: approve/decline a pending request and send a quick prompt.

### Hard constraints
- **No changes to T3 Code.** Separate repo, separate app, no fork, nothing to merge when T3 Code updates.
- **Survive T3 Code updates** as far as possible by depending on the smallest possible API surface and parsing it leniently.

### Non-goals
- Replacing the T3 Code app. Tapping into a thread hands off to the official app or the web UI.
- Using T3 Connect (the hosted relay). It needs a Clerk sign-in tied to the official clients; not viable for a third-party app.

---

## 2. Key findings

| Question | Answer |
|---|---|
| Is there a stable public API? | **No.** The only outsider-documented surface is MCP (`docs/user/outside-agents.md`). The HTTP + WebSocket API used here is internal but shared by the official web/desktop/mobile clients. |
| Can a third party read status? | **Yes**, with one HTTP GET (`/api/orchestration/shell`). |
| Can a third party approve / send prompts? | **Yes**, but only over the WebSocket RPC (`orchestration.dispatchCommand`). No HTTP equivalent. |
| How does it authenticate? | Scoped bearer token issued from the host CLI (`t3 auth session issue`). |
| Can it get push notifications? | **No.** Push is only delivered via T3 Connect's relay to the official app. No outbound webhooks. Must poll or hold a socket. |
| How does it reach the server remotely? | Tailscale (`t3 serve --tailscale-serve`) recommended; `--lan` for home Wi-Fi only. |
| What breaks it? | An orchestration protocol bump (currently `2`) → HTTP 426. Field renames/removals in the few fields we read. |
| Does the official Android app already have this widget? | **No.** The official "Agent Activity" widget is iOS-only (`apps/mobile/app.config.ts:193-197`, `android: null`). Android only gets a "Subscription Usage" widget (`:183-191`). |

---

## 3. Architecture

```
┌──────────────── Android phone ────────────────┐        ┌──────── Host computer ────────┐
│                                                │        │                               │
│  Widget (Jetpack Glance)                       │        │  T3 Code server (port 3773)   │
│     ▲  renders cached state                    │        │   • GET /api/orchestration/   │
│     │                                          │ HTTPS  │       shell                   │
│  Repository ── DataStore cache ◄── Sync worker ─┼───────►│   • POST /api/auth/           │
│                                  (WorkManager) │Tailnet │       websocket-ticket/rpc    │
│                                                │        │   • /ws (Effect RPC)          │
│  Settings activity (URL + token, encrypted)    │        │                               │
│  [v2] ActionService (WebSocket client)  ───────┼───────►│  `t3 serve --tailscale-serve` │
└────────────────────────────────────────────────┘        └───────────────────────────────┘
```

**Coupling to T3 Code is limited to:**
1. One HTTP route + two headers (v1).
2. One WebSocket RPC method with two command shapes (v2).
3. Its Android package name / `https://app.t3.codes/threads/<id>` link for tap-through.

---

## 4. Server setup (on the host computer)

### 4.1 Make the server reachable from the phone

| Option | Command | Reach | Notes |
|---|---|---|---|
| **Tailscale (recommended)** | `t3 serve --tailscale-serve` | Anywhere, HTTPS | Phone must be on the same tailnet. See `docs/user/remote-access.md` (lines ~91-232). |
| LAN | `t3 serve --lan` | Home Wi-Fi only | Binds `0.0.0.0` (`apps/server/src/cli/config.ts:437-439`). Plain HTTP → needs a cleartext network-security exception on Android. |
| SSH tunnel | — | Anywhere | Awkward on a phone; not recommended. |
| T3 Connect | — | Anywhere | **Not usable** (Clerk sign-in + DPoP credentials minted by the hosted relay; `docs/internals/06_t3-connect.md`). |

Default port is **3773** (`apps/server/src/config.ts:30`). The desktop app binds `127.0.0.1` only, so one of the options above is required.

### 4.2 Issue a token

Read-only (v1):
```bash
t3 auth session issue --scope orchestration:read --ttl 30d --label "android-widget" --token-only
```

Read + actions (v2, opt-in):
```bash
t3 auth session issue --scope orchestration:read --scope orchestration:operate --ttl 30d --label "android-widget-actions" --token-only
```

- Defined at `apps/server/src/cli/auth.ts:293-321` ("Issue a scoped bearer access token for headless or remote clients").
- Scopes: `orchestration:read`, `orchestration:operate` (`packages/contracts/src/auth.ts:31-32`). Per-RPC scope requirements: `apps/server/src/auth/RpcAuthorization.ts`.
- **Bearer tokens without DPoP do not renew** (`apps/server/src/auth/EnvironmentAuth.ts:1169-1185`). Plan on re-issuing roughly monthly. Whether `--ttl` accepts longer values is **unverified**.

### 4.3 Alternative: pairing code (optional, nicer UX)
Pairing URLs look like `https://host/#token=CODE` (`apps/mobile/src/features/connection/pairing.ts:33-76`). Codes live 5 minutes (`apps/server/src/auth/PairingGrantStore.ts:326`). Exchange via form POST to `/oauth/token` (`packages/contracts/src/auth.ts:231-251`):

```
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
subject_token=CODE
subject_token_type=urn:t3:params:oauth:token-type:environment-bootstrap
requested_token_type=urn:ietf:params:oauth:token-type:access_token
scope=orchestration:read            (optional)
```

Without a DPoP proof this yields a 30-day non-renewing bearer token; with DPoP, a 1-hour token renewable within a 30-day window (the official mobile app does DPoP: `apps/mobile/src/features/cloud/dpop.ts`, `packages/client-runtime/src/authorization/remote.ts:125-165`). **Recommendation:** v1 uses pasted CLI tokens; pairing/QR + DPoP is a v3 nicety.

---

## 5. API reference — v1 (read-only)

### 5.1 Health / compatibility probe
`GET /.well-known/t3/environment` (`packages/contracts/src/environmentHttp.ts:590`)
Returns the server descriptor including `serverVersion`, `orchestrationProtocolVersion`, and a `capabilities` list (`packages/contracts/src/environment.ts:200-245`). Use it in the settings screen "Test connection" button and to detect protocol changes **before** the main call fails.

### 5.2 Shell snapshot (the one call the widget needs)
```
GET /api/orchestration/shell?omitIdleSubagents=true&omitOlderSettled=true
Authorization: Bearer <token>
x-t3-orchestration-protocol: 2
```
- Route: `packages/contracts/src/environmentHttp.ts:775`.
- Header constant: `ORCHESTRATION_PROTOCOL_HEADER = "x-t3-orchestration-protocol"`, version `"2"` (`packages/contracts/src/environment.ts:13-16`).
- Query params (`environmentHttp.ts:741-749`): `omitIdleSubagents`, `omitOlderSettled`, `resumeFrom`. **Never send `resumeFrom`**: with it the server may answer `{"resumable": true}` instead of a snapshot (`orchestrationV2.ts:2577`). The exact accepted string values for the two `omit*` flags are **unverified**; test `true`/`1`, or omit them.

Response (`OrchestrationV2ShellHttpSnapshot`, `packages/contracts/src/orchestrationV2.ts:2551-2575`), trimmed to what we read:

```jsonc
{
  "schemaVersion": 1,
  "snapshotSequence": 12345,
  "projects": [
    {
      "id": "proj_…",
      "title": "my-app",
      "workspaceRoot": "/Users/…/my-app",
      "pinnedAt": null,        // optional
      "archivedAt": null       // optional
      // …many more fields, ignore
    }
  ],
  "threads": [
    {
      "id": "thr_…",
      "projectId": "proj_…",
      "title": "Fix login bug",
      "status": "running",                 // see 5.3
      "activityRunStatus": "running",      // optional: preparing|starting|running|waiting|null
      "pendingRuntimeRequest": {           // null when nothing is waiting on the user
        "id": "req_…",
        "kind": "permission",              // see 5.3
        "createdAt": "2026-11-25T14:03:00Z",
        "blocking": true,                  // optional; false = async question, run continues
        "userOnly": false                  // optional
      },
      "lastError": null,                   // optional string
      "latestVisibleMessage": {            // nullable
        "id": "msg_…",
        "role": "assistant",
        "text": "I've updated the handler…",
        "updatedAt": "2026-11-25T14:02:51Z"
      },
      "updatedAt": "2026-11-25T14:03:00Z",
      "archivedAt": null,
      "settledAt": null,
      "snoozedUntil": null                 // optional
      // ~70 fields total; ignore the rest
    }
  ],
  "archivedThreads": [ /* same shape; ignore */ ]
}
```

Thread shell schema: `orchestrationV2.ts:2402-2470`. Project shell schema: `packages/contracts/src/orchestrationProject.ts:8-30`.

### 5.3 Enums

**`status`** (`orchestrationV2.ts:718-729, 2375`):
`idle` · `preparing` · `queued` · `starting` · `running` · `waiting` · `completed` · `interrupted` · `failed` · `cancelled` · `rolled_back`

**`pendingRuntimeRequest.kind`** (`packages/contracts/src/providerPolicy.ts:49`, `orchestrationV2.ts:1420-1423`):
`command` · `file-read` · `file-change` · `mcp-elicitation` · `permission` · `user_input` · `dynamic_tool_call` · `auth_refresh`

Treat **unknown enum values as a generic state**, never as a parse failure.

### 5.4 Widget classification logic

```
needsYou   = pendingRuntimeRequest != null && pendingRuntimeRequest.blocking != false
running    = status in {preparing, queued, starting, running}
             || activityRunStatus in {preparing, starting, running}
waiting    = status == "waiting" && !needsYou          // e.g. blocked on another thread
failed     = status == "failed" || (lastError != null && status not in running)
done/idle  = everything else
hidden     = archivedAt != null || (snoozedUntil != null && snoozedUntil > now)
```

Sort: `needsYou` (oldest request first) → `failed` → `running` → recently `completed` (by `updatedAt`). Show project title from `projects[]` by `projectId`.

---

## 6. API reference — v2 (actions)

There is **no HTTP route** for actions; they go through the WebSocket RPC.

### 6.1 Get a WebSocket ticket
```
POST /api/auth/websocket-ticket/rpc
Authorization: Bearer <token with orchestration:operate>
```
Ticket is valid **5 minutes** (`environmentHttp.ts:626`, `apps/server/src/auth/SessionStore.ts:493`).

### 6.2 Connect
```
GET /ws?wsTicket=<ticket>&orchestrationProtocol=2     (WebSocket upgrade)
```
(`apps/server/src/ws.ts:3818-3833`.) Wrong protocol → **HTTP 426 `orchestration_protocol_incompatible`**.

### 6.3 Wire format (Effect RPC, JSON serialization — *not* JSON-RPC)
Client request (from `apps/server/src/ws.test.ts:1182-1189`):
```json
{"_tag":"Request","id":"1","tag":"orchestration.dispatchCommand","payload":{ … },"headers":[]}
```
For streaming responses the client must send acks (flow-control window, `ws.ts:3860`):
```json
{"_tag":"Ack","requestId":"1"}
```
Server replies arrive as `Chunk` / `Exit` envelopes from Effect's RPC library. **Their exact JSON shape is unverified** — capture real traffic (browser devtools on the T3 web UI → Network → WS) before writing the parser.

RPC name: `orchestration.dispatchCommand` (`orchestrationV2.ts:4093`), requires `orchestration:operate`. Command union: `orchestrationV2.ts:~3299` onward.

### 6.4 Approve / decline a pending request
(`orchestrationV2.ts:3735-3743`; decisions at `providerPolicy.ts:58-64`)
```json
{
  "type": "runtime-request.respond",
  "commandId": "<new UUID>",
  "threadId": "<thread id>",
  "requestId": "<pendingRuntimeRequest.id>",
  "decision": "accept"
}
```
`decision`: `accept` · `acceptForSession` · `acceptAlways` · `decline` · `cancel`.
For `user_input` requests send `answers` instead of `decision` (schema `ProviderUserInputAnswers`, not mapped yet). Optional `feedback` string.

**Widget scope:** only offer Accept / Decline for `kind in {command, file-change, file-read, permission}`. Everything else → "Open in T3 Code".

### 6.5 Send a quick prompt to an existing thread
(`orchestrationV2.ts:3598-3643`)
```json
{
  "type": "message.dispatch",
  "commandId": "<new UUID>",
  "threadId": "<thread id>",
  "messageId": "<new UUID>",
  "text": "Run the tests again",
  "attachments": [],
  "dispatchMode": { "type": "queue_after_active" }
}
```
`dispatchMode.type`: `start_immediately` · `queue_after_active` · `steer_active` (needs `targetRunId`) · `restart_active` (needs `targetRunId`) · `defer_start`. Use `queue_after_active` when the thread is busy, `start_immediately` when idle.

IDs (`CommandId`, `MessageId`, …) are branded non-empty strings (`packages/contracts/src/baseSchemas.ts:287-302`); client-generated UUIDs **should** be accepted — verify.

### 6.6 Alternative considered: MCP endpoint
`/mcp` (Streamable HTTP, JSON-RPC 2.0; `apps/server/src/httpCors.ts:16`) exposes `t3_thread_list`, `t3_project_list`, `t3_thread_read`, `t3_thread_send`, `t3_pending_request` (`apps/server/src/mcp/toolkits/thread/tools.ts:129-147`). It is the **only documented** outside interface and arguably more stable, but `t3_pending_request` **cannot approve permission requests**. Viable fallback for v1 reads and quick prompts if the internal HTTP route proves unstable.

---

## 7. Android implementation plan

### 7.1 Stack
| Concern | Choice |
|---|---|
| Language | Kotlin |
| Widget UI | Jetpack Glance (`androidx.glance:glance-appwidget`) |
| Background refresh | WorkManager periodic work (15-min floor) + on-demand one-shot work |
| HTTP / WebSocket | OkHttp |
| JSON | kotlinx.serialization with `ignoreUnknownKeys = true`, `coerceInputValues = true`, `explicitNulls = false` |
| Storage | DataStore (cached snapshot) + EncryptedSharedPreferences / Tink for URL + token |
| Min SDK | 26 (Glance requirement is 23+; 26 keeps things simple) |

### 7.2 Modules / files
```
app/
  data/
    T3Api.kt             // fetchShell(), fetchEnvironment(); headers, timeouts, error mapping
    T3Models.kt          // ~10 fields per DTO, all nullable/defaulted, enums as String
    ShellRepository.kt   // fetch → classify → cache; exposes WidgetState
    SecretStore.kt       // encrypted URL + token
  sync/
    ShellSyncWorker.kt   // periodic (15 min) + one-shot "refresh now"
  widget/
    T3StatusWidget.kt    // GlanceAppWidget: header counts + list of top N threads
    T3WidgetReceiver.kt
    actions/             // Refresh, OpenThread, [v2] Approve/Decline
  settings/
    SettingsActivity.kt  // URL, token paste, "Test connection", refresh interval
  ws/ (v2)
    EffectRpcClient.kt   // ticket → connect → Request/Ack/Exit handling
    ActionService.kt     // short-lived; perform one command and exit
```

### 7.3 Widget layout (4×2 default, resizable)
```
┌ T3 Code ─────────────── ⟳ 2m ago ┐
│ ● 2 need you  ▶ 3 running  ✕ 1    │
│ ● my-app · Fix login bug          │
│   permission: run `npm test`      │
│   [Approve] [Decline]   (v2)      │
│ ▶ api · Add rate limiting         │
│   "Updating middleware…"          │
└───────────────────────────────────┘
```
States: **not configured**, **loading**, **ok**, **offline** (show last-good data + age), **auth expired** (401 → "Re-issue token"), **update needed** (426 / protocol mismatch).

### 7.4 Tap-through
- Thread row → `https://app.t3.codes/threads/<threadId>`. The official Android app registers an intent filter for this path (`apps/mobile/app.config.ts:321`), so it opens the app if installed, else the web app. *Whether that link resolves to threads on a self-hosted server is unverified* — fallback is launching the app by package.
- Packages: `com.t3tools.t3code` (prod), `.preview`, `.dev` (`apps/mobile/app.config.ts:80-104`). Custom schemes `t3code://`, `t3code-preview://`, `t3code-dev://`.
- Last resort: open `<serverUrl>` in the browser (web UI).

### 7.5 Refresh strategy
| Mode | Latency | Battery | Notes |
|---|---|---|---|
| WorkManager periodic | ≥15 min | Low | Default. |
| Refresh on tap / widget resize / app open | Instant | Low | Always on. |
| Foreground service holding `orchestration.subscribeShell` WS stream | Seconds | Medium-high | Optional toggle. Requires a persistent notification. `subscribeShell` takes `{afterSequence?}` and streams `snapshot` / `thread.updated` / `project.updated` / `*.removed` items (`orchestrationV2.ts:2582-2620, 4112, 4206`). Needs Ack handling. |

---

## 8. Resilience rules (how it survives T3 Code updates)

1. **Read the fewest fields possible.** Every DTO field nullable with a default.
2. **Ignore unknown keys and unknown enum values.** Map unknown `status`/`kind` to `OTHER` and still render the row.
3. **Probe `/.well-known/t3/environment` on settings save and once a day.** If `orchestrationProtocolVersion != 2`, show "T3 Code updated its API — widget update needed" instead of failing silently.
4. **Handle HTTP 426** the same way.
5. **Keep the last good snapshot** and show its age when offline or broken.
6. **Keep the protocol version, header names, and paths in one constants file** so a protocol bump is a one-line change plus model fixes.
7. **Feature-flag v2 actions** off by default; if the WS handshake or command fails, hide action buttons and fall back to "Open in T3 Code".
8. **Integration test against a real server** (Tailscale or emulator → `10.0.2.2:3773`) as a release check whenever T3 Code ships a new version.

---

## 9. Security

- Store URL + token encrypted; never log the token.
- **v1 token is read-only.** The `orchestration:operate` scope lets the phone instruct agents that can run commands on your machine — issue it separately, only if v2 actions are enabled, and label it so it can be revoked.
- Prefer Tailscale HTTPS. If LAN HTTP is used, scope the cleartext exception to that single host in `network_security_config.xml`.
- Widget content is visible on the lock screen/home screen; add a "hide message previews" setting.
- Revoke tokens from the host if the phone is lost (check `t3 auth session --help` for the list/revoke subcommands).

---

## 10. Milestones

| # | Deliverable | Done when |
|---|---|---|
| M0 | Spike | `curl` the shell endpoint with a CLI token over Tailscale; record a real response as a test fixture. Capture a real WS `dispatchCommand` exchange from the web UI devtools. |
| M1 | v1 read-only widget | Settings + test connection, periodic + manual refresh, classification, tap-through, all error states, fixture-based parser tests. |
| M2 | Polish | Resizable layouts, project filter, hide previews, dark mode, last-updated age. |
| M3 | v2 actions | Effect RPC client, Approve/Decline for permission-type requests, quick prompt via a small dialog activity. |
| M4 | Optional live mode | Foreground service on `subscribeShell`, battery-aware toggle. |
| M5 | Optional pairing | QR/pairing-code exchange + DPoP for auto-renewing tokens. |

Rough size: M1 ≈ a few hundred lines of Kotlin; M3 roughly doubles it (most of it the WS client).

---

## 11. Open questions / unverified items

1. Exact JSON shape of Effect RPC `Chunk` / `Exit` server frames (capture real traffic).
2. Accepted values for `omitIdleSubagents` / `omitOlderSettled` query params.
3. Whether `--ttl` can exceed 30 days, and the exact revoke command.
4. Bind address when `t3 serve` is run without `--lan` / host flags.
5. Whether client-generated UUIDs are accepted for `commandId` / `messageId`.
6. Whether `https://app.t3.codes/threads/<id>` opens the right thread for a self-hosted (non-Connect) server in the official Android app.
7. Shape of `ProviderUserInputAnswers` (only needed if answering questions from the widget).
8. Exact semantics of `status: "waiting"` vs. `pendingRuntimeRequest` (validate with fixtures).

---

## 12. Alternatives (for the record)

- **Official app's Android notifications:** With T3 Connect, the official Android app already shows ongoing agent-activity notifications/Live Updates (`apps/mobile/modules/t3-agent-notifications/android/…/AgentNotifications.kt`). Check whether that already meets the need before building.
- **Upstream contribution:** The official "Agent Activity" widget exists for iOS with `android: null` (`apps/mobile/app.config.ts:193-197`). An upstream PR would be the most durable fix but contradicts the "no merges" constraint.
- **Launcher-only widget (option 1):** Zero API coupling, no live data.

---

## 13. Key file index (T3 Code repo)

| Area | File |
|---|---|
| HTTP routes | `packages/contracts/src/environmentHttp.ts` |
| Protocol version constants | `packages/contracts/src/environment.ts:13-16` |
| Thread/shell/command schemas | `packages/contracts/src/orchestrationV2.ts` |
| Project shell schema | `packages/contracts/src/orchestrationProject.ts` |
| Approval decisions, request kinds | `packages/contracts/src/providerPolicy.ts` |
| Auth scopes, token exchange | `packages/contracts/src/auth.ts` |
| Token-issue CLI | `apps/server/src/cli/auth.ts:293-321` |
| WebSocket handler | `apps/server/src/ws.ts:3818-3900` |
| WS envelope examples | `apps/server/src/ws.test.ts:1182-1189` |
| RPC scope enforcement | `apps/server/src/auth/RpcAuthorization.ts` |
| Remote access docs | `docs/user/remote-access.md` |
| Outside-agent (MCP) docs | `docs/user/outside-agents.md` |
| Push notification docs | `docs/user/mobile-notifications.md` |
| Official mobile widgets config | `apps/mobile/app.config.ts:133-206` |
