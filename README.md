# SentryDemo

Monorepo demo for Sentry self-hosted + an Android client that exercises the main observability surfaces (errors, ANR, performance, custom workflow spans, rich device context).

```
SentryDemo/
├── sentry-selfhost/   Docker-compose driven self-hosted Sentry (BE + infra)
└── android-app/       Android demo app, Clean Architecture + Jetpack Compose
```

## Quickstart

### 1. Bring up Sentry self-hosted

```bash
cd sentry-selfhost
./setup.sh            # clones official getsentry/self-hosted, runs install.sh
docker compose --project-directory .self-hosted up -d
```

After install, open `http://localhost:9000`, finish first-run wizard, create a project (platform: Android) and copy the **DSN**.

Hardware requirement: 4 CPU cores, 16 GB RAM + 16 GB swap, 20 GB disk, Docker 19.03.6+, Docker Compose 2.32.2+.

### 2. Configure & run the Android app

```bash
cd ../android-app
cp local.properties.example local.properties
# Edit local.properties: set sentry.dsn=<your DSN> and sdk.dir=<your Android SDK path>
./gradlew :app:installDebug
```

Or just open `android-app/` in Android Studio (Giraffe+).

## What the app demonstrates

| Button | Sentry signal |
| --- | --- |
| **Delay action** | Custom transaction with a long span, performance tab |
| **Crash action** | Uncaught `RuntimeException` → Issues tab |
| **ANR action** | Blocks main thread → ANR event with thread dump |
| **Photo workflow** | Parent transaction `photo_workflow` with `capture_image`, `save_image`, `sync_image` child spans, each annotated with timestamp, status, data, and error |

Every event/transaction is enriched with:
- `user.id` (random per install, persisted)
- `device.type` (manufacturer + model)
- `app.version` (BuildConfig)
- `device.free_ram_mb`
- `device.free_storage_mb`
- `device.network_speed_mbps`
- `action_name` (tag)

See `android-app/README.md` for architecture & extension points.
