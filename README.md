# SentryDemo

Monorepo demo for **Sentry self-hosted** + an **Android client** that exercises
the main observability surfaces: errors, ANRs, performance transactions,
custom workflow spans, and rich device/user context.

```
SentryDemo/
├── android-app/        Android demo app · Clean Architecture · Jetpack Compose · Sentry SDK 8.x
├── sentry-selfhost/    Docker-compose driven self-hosted Sentry backend (pinned to 25.8.0)
├── docs/               Technical & operational documentation (this is the index below)
└── screenshots/        UI captures of each demo action
```

## Quickstart

### 1. Bring up Sentry self-hosted

```bash
cd sentry-selfhost
./setup.sh            # clones official getsentry/self-hosted@25.8.0, runs install.sh
docker compose --project-directory .self-hosted up -d
```

Open <http://localhost:9000>, finish the first-run wizard, create a project
(platform: **Android**), and copy the DSN.

Hardware floor: 4 CPU cores · 16 GB RAM + 16 GB swap · 20 GB disk · Docker
19.03.6+ · Docker Compose 2.32.2+.

### 2. Configure & run the Android app

```bash
cd ../android-app
cp local.properties.example local.properties
# Edit local.properties: set sentry.dsn=<your DSN> and sdk.dir=<your Android SDK path>
./gradlew :app:installDebug
```

Or open `android-app/` in Android Studio Giraffe+.

## What the app demonstrates

| Button | Sentry signal | Where it lands |
| --- | --- | --- |
| **Delay action** | Transaction `delay_action` + child span `processing` | Performance |
| **Crash action** | Uncaught `RuntimeException` | Issues *(visible after relaunch)* |
| **ANR action** | Blocks main thread → `ApplicationNotResponding` | Issues *(visible after relaunch)* |
| **Photo workflow (ok)** | Transaction `photo_workflow` with `capture_image` / `save_image` / `sync_image` child spans | Performance |
| **Photo workflow (fail)** | Same transaction, `sync_image` marked failed + `PhotoSyncException` | Performance + Issues |

Every event is enriched with `user.id`, `device.manufacturer`/`model`,
`app.version`, free RAM, free storage, network speed, network transport,
battery percentage, and the `action_name` tag. Schema details in
[`docs/observability-model.md`](./docs/observability-model.md).

## Documentation index

All technical and operational documentation lives in [`docs/`](./docs/).
Pick the one closest to your task.

### Understanding the system

| Document | When to read it |
| --- | --- |
| [`docs/architecture.md`](./docs/architecture.md) | **Start here.** Top-down view of the whole stack — Android client, self-hosted backend, event flow, dependency direction, and why the design is the way it is. |
| [`docs/observability-model.md`](./docs/observability-model.md) | Reference for what the app emits per button: transactions, spans, events, tags, contexts, breadcrumbs, and how to find them in the Sentry UI. Use this when extending the demo or debugging missing data. |

### Component deep dives

| Document | When to read it |
| --- | --- |
| [`docs/android-app.md`](./docs/android-app.md) | Android client technical reference: package map, every class' responsibility, the Sentry SDK init order, the workflow DSL, test architecture (`SentryCaptureRule`, Hilt test runner), extension points, and the Sentry Gradle plugin setup. |
| [`docs/sentry-selfhost.md`](./docs/sentry-selfhost.md) | Sentry self-hosted technical reference: the ~30-service compose topology, what our wrapper overrides, networking from AVD/physical devices, volumes, and the multi-arch story. |

### Operating the backend

| Document | When to read it |
| --- | --- |
| [`docs/deployment.md`](./docs/deployment.md) | **End-to-end deployment guide for infra**. Prerequisites matrix, host setup, step-by-step install of Sentry + Android client, production hardening (TLS, SMTP, backups, SSO, auth tokens), upgrade procedure, and disaster recovery. |
| [`docs/configuration.md`](./docs/configuration.md) | **Single source of truth for every knob**: backend env vars, Android `local.properties`, Sentry SDK options, Gradle plugin flags, ports, volumes. Use this whenever you need to know "what's the variable for X?". |
| [`docs/operations.md`](./docs/operations.md) | **Day-2 runbook**: start/stop, health checks, backups, restore, upgrades, capacity tuning, log rotation, secret rotation, common incidents (disk full, port conflicts, stuck services, consumer reset), and full-reset procedures. |

### Component-local quickstarts

Each subdirectory also has its own README focused on the fastest path to a
working setup. Use the deep-dive docs above when those quickstarts aren't
enough.

| Document | Focus |
| --- | --- |
| [`android-app/README.md`](./android-app/README.md) | Build, run, test the Android client. Mirrors the architecture summary and lists the verified test results. |
| [`sentry-selfhost/README.md`](./sentry-selfhost/README.md) | Bring the backend up, the wrapper's file layout, and the caveats specific to upstream's installer. |

### Stakeholder presentation

| Document | Focus |
| --- | --- |
| [`docs/Sentry_SelfHost_Plan.pptx`](./docs/Sentry_SelfHost_Plan.pptx) | 11-slide infra plan covering Sentry self-hosted deployment, configuration, account setup, smoke testing, production hardening, capacity planning for 5k → 10k devices, and the GlitchTip-backend alternative (client keeps Sentry SDK). |

## Recommended reading paths

Depending on what you're doing, follow these:

- **First-time deploy** — `README.md` → [`deployment.md`](./docs/deployment.md) → [`configuration.md`](./docs/configuration.md) (as a reference while you fill in `.env` and `local.properties`).
- **Architecting on top of this demo** — [`architecture.md`](./docs/architecture.md) → [`android-app.md`](./docs/android-app.md) → [`observability-model.md`](./docs/observability-model.md).
- **Inheriting the running stack** — [`operations.md`](./docs/operations.md) → [`sentry-selfhost.md`](./docs/sentry-selfhost.md) → [`configuration.md`](./docs/configuration.md).
- **Debugging "why isn't my event showing up?"** — [`observability-model.md`](./docs/observability-model.md) → [`android-app.md`](./docs/android-app.md#troubleshooting) → [`operations.md`](./docs/operations.md#common-incidents).

## Screenshots

| File | Shows |
| --- | --- |
| [`screenshots/01_main_screen.png`](./screenshots/01_main_screen.png) | The five-button demo screen |
| [`screenshots/02_delay_log.png`](./screenshots/02_delay_log.png) | Activity log during a delay action |
| [`screenshots/02_after_delay.png`](./screenshots/02_after_delay.png) | UI after the delay completes |
| [`screenshots/03_photo_workflow_ok.png`](./screenshots/03_photo_workflow_ok.png) | Photo workflow happy path |
| [`screenshots/04_photo_workflow_fail.png`](./screenshots/04_photo_workflow_fail.png) | Photo workflow with forced sync failure |

## Versions pinned by this demo

| Component | Version |
| --- | --- |
| Sentry self-hosted | 25.8.0 |
| Sentry Android SDK | 8.38.0 |
| Sentry Gradle plugin | 5.1.0 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| Hilt | 2.52 |
| AGP | 8.7.3 |
| JVM target | 17 |
| `compileSdk` / `targetSdk` | 35 (Android 15) |
| `minSdk` | 26 (Android 8.0) |

Bump procedure for each is documented in
[`configuration.md`](./docs/configuration.md) and the upgrade section of
[`operations.md`](./docs/operations.md#upgrades).
