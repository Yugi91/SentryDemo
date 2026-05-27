# Architecture

Top-down view of the SentryDemo monorepo: the Android client, the self-hosted
Sentry backend, and the events that flow between them.

## System diagram

```
┌──────────────────────────────────────────────┐        ┌──────────────────────────────────────────────────────┐
│              Android client                  │        │                Sentry self-hosted                    │
│                                              │        │                                                      │
│  MainActivity (Compose)                      │        │  ┌────────┐   ┌──────────┐   ┌──────────────────┐   │
│     │                                        │        │  │ nginx  │──▶│  relay   │──▶│  kafka + snuba   │   │
│     ▼                                        │        │  └────────┘   └──────────┘   └──────────────────┘   │
│  MainViewModel ──── HiltViewModel            │        │     ▲                              │                │
│     │                                        │        │     │                              ▼                │
│     ▼                                        │ HTTP/S │  ┌────────┐   ┌──────────┐   ┌──────────────────┐   │
│  Use cases (domain/)                         │ ─────▶ │  │  web   │   │ workers  │──▶│ postgres + ch    │   │
│     │  delay, crash, anr, photo_workflow     │ events │  │ (UI)   │   │ (events) │   │ + redis caches   │   │
│     ▼                                        │        │  └────────┘   └──────────┘   └──────────────────┘   │
│  SentryWorkflowTracker  ─── DSL              │        │     │                                                │
│     │                                        │        │     ▼                                                │
│  SentryContextEnricher  ─── scope tags       │        │  Issues / Performance / Replays UI                   │
│     │                                        │        │  on http://localhost:9000                            │
│  Sentry Android SDK (8.x)                    │        │                                                      │
│                                              │        │  + symbolicator, vroom, taskbroker, uptime-checker   │
└──────────────────────────────────────────────┘        └──────────────────────────────────────────────────────┘
```

## Repository layout

```
SentryDemo/
├── README.md                  Documentation index (entry point)
├── android-app/               Android client (Clean Architecture + Compose)
├── sentry-selfhost/           Sentry self-hosted wrapper (docker-compose)
├── docs/                      Technical & operational documentation
└── screenshots/               UI captures showing each demo action
```

## Components

### Android client — `android-app/`

A single-screen Compose app whose only purpose is to **exercise the observable
surfaces of Sentry**: crashes, ANRs, performance transactions, custom workflow
spans, and rich device/user context. Clean Architecture keeps the Sentry-aware
code in `core/sentry/` so the `domain/` layer stays platform-agnostic.

Key entry points:

- `SentryDemoApplication` — Hilt `@HiltAndroidApp`. Calls `SentryAndroid.init(...)`
  **before** `super.onCreate()` so the uncaught-exception handler and ANR
  watchdog also cover failures during DI setup.
- `MainViewModel` — single state, one action per demo button; dispatches into
  the four use cases.
- `SentryWorkflowTracker` — tiny DSL that wraps a Sentry transaction into a
  `runRoot { step(name) { data(...) } }` block, emits breadcrumbs on every
  step transition, marks spans `OK` / `INTERNAL_ERROR` based on outcome.
- `SentryContextEnricher` — sets static tags/contexts once on `Application.onCreate`,
  then re-stamps volatile state (free RAM, free storage, network speed, battery)
  per action.

Deep dive: [`android-app.md`](./android-app.md).

### Sentry self-hosted — `sentry-selfhost/`

Thin wrapper around [`getsentry/self-hosted`](https://github.com/getsentry/self-hosted)
pinned at tag **`25.8.0`**. Our `setup.sh` clones upstream into `.self-hosted/`,
layers `docker-compose.override.yml` on top (log rotation + bound port), and
runs the upstream `install.sh`.

The official compose graph spins up ~30 services in three tiers:

| Tier | Services | Purpose |
| --- | --- | --- |
| Edge | `nginx`, `relay` | TLS termination, ingestion shaping |
| App | `web`, `worker`, `cron`, `symbolicator`, `vroom`, `taskbroker`, `uptime-checker` | UI, async workers, symbolication, profiling |
| Data | `postgres`, `clickhouse`, `kafka`, `redis`, `memcached` | Primary store, OLAP, queue, caches |

Deep dive: [`sentry-selfhost.md`](./sentry-selfhost.md).

## Event flow (happy path)

```
Android use case
    │
    │ 1. SentryContextEnricher.enrich("photo_workflow")
    │       └─ stamps action_name tag + device_runtime context onto Sentry scope
    │
    │ 2. SentryWorkflowTracker.runRoot("photo_workflow") {
    │       step("capture_image") { ... }
    │       step("save_image")    { ... }
    │       step("sync_image")    { ... }
    │    }
    │       └─ one ITransaction with 3 child ISpan, breadcrumbs on every transition
    │
    │ 3. Sentry Android SDK serializes the transaction as an envelope and POSTs to
    │    DSN → http://10.0.2.2:9000/api/<project_id>/envelope/
    │
    ▼
nginx → relay (deduplication, sampling, normalization)
    │
    ▼
kafka topic
    │
    ▼
snuba ingest consumer → clickhouse (events, transactions, profiles)
                     → postgres   (issues, project metadata, users)
    │
    ▼
web UI surfaces it under Issues / Performance / Replays / Profiles
```

Crash & ANR events follow a different path because they're captured **as the
process dies**. The SDK persists the envelope to disk first; the queue flushes
on the next app launch. See [`observability-model.md`](./observability-model.md)
for the per-button event schema.

## Dependency direction

```
presentation ─► domain ─► core/sentry · core/device
                ▲             │
                │             │ knows io.sentry.*
       implemented by         │
                │             ▼
              data        sentry-android SDK
```

Rules enforced by package layout:

- `domain/` has **zero** dependency on `io.sentry.*` or Android APIs.
- `core/sentry/` is the **only** place that opens a transaction or touches the
  Sentry scope.
- `data/repository/` simulates I/O with `delay(...)`; no Sentry calls.
- Tests of `domain/` use `SentryCaptureRule` to drive the real SDK with a
  no-network DSN and capture every envelope.

## Why Clean Architecture for a demo

The demo is small, but the layering matters because the value proposition is
**"this is how you wire Sentry into a real Android codebase"**. If we put
`Sentry.startTransaction(...)` directly in `MainViewModel`, the demo would
collapse into one screen of imperative code that nobody could lift into a
production codebase. By keeping the Sentry-aware DSL inside `core/sentry/` and
the use cases describing *what* the workflow does (not how it's instrumented),
the demo doubles as a copy-paste-ready template.

## Build & deployment topology

The Android app and the Sentry backend deploy independently.

- **Backend**: pull image tags pinned in `.self-hosted/.env` (`SENTRY_IMAGE`,
  `SNUBA_IMAGE`, `RELAY_IMAGE`, etc., all aligned to `25.8.0`). Volumes are
  Docker-managed; no external storage backend required for the demo. See
  [`deployment.md`](./deployment.md).
- **Frontend**: Gradle build with the Sentry Gradle plugin. Source-context and
  ProGuard mapping uploads to the self-hosted backend are wired but **disabled
  by default** — flip `sentry.autoUploadProguardMapping` and provide an auth
  token in CI to enable. See [`android-app.md`](./android-app.md#sentry-gradle-plugin).
