# android-app

Demo Android client wired to a Sentry self-hosted instance. Clean Architecture, Jetpack Compose, Hilt, Kotlin Coroutines, Sentry Android SDK 8.x.

## Architecture

```
app/src/main/java/io/pula/sentrydemo/
├── SentryDemoApplication.kt        Hilt + Sentry init (BEFORE super.onCreate)
├── MainActivity.kt                  @AndroidEntryPoint, hosts Compose tree
├── di/
│   └── AppModule.kt                 @Binds: PhotoWorkflowRepository
├── core/
│   ├── sentry/
│   │   ├── SentryWorkflowTracker.kt Tiny DSL: runRoot { step(name) { data(...); ... } }
│   │   └── SentryContextEnricher.kt Stamps user/device tags & contexts on scope
│   └── device/
│       └── DeviceInfoProvider.kt    RAM, storage, network speed, battery, ABI…
├── domain/
│   ├── model/                       Pure Kotlin DTOs (CapturedPhoto, WorkflowReport…)
│   ├── repository/
│   │   └── PhotoWorkflowRepository  capture / save / sync — no Sentry types here
│   └── usecase/
│       ├── SimulateDelayUseCase
│       ├── TriggerCrashUseCase
│       ├── TriggerAnrUseCase
│       └── RunPhotoWorkflowUseCase  orchestrates the workflow + workflowTracker
├── data/
│   └── repository/
│       └── PhotoWorkflowRepositoryImpl  fake delays + simulated 20% sync failure
└── presentation/
    ├── MainViewModel.kt             @HiltViewModel; one state, one action per button
    ├── DemoScreen.kt                Compose UI with 5 buttons + scrolling activity log
    └── theme/                       Color, Theme, Type (Material 3, dynamic color)
```

### Dependency flow

```
presentation/ViewModel
    ↓ injects
domain/usecase  ──→  core/sentry · core/device  (cross-cutting; no domain types)
    ↓ depends on
domain/repository (interface)
    ↑ binds
data/repository/Impl
```

`core/sentry/*` knows about the Sentry SDK; `domain/*` does not. The use case is the only place that opens a transaction.

## What each button maps to in Sentry

| Button | Sentry event(s) | Notes |
| --- | --- | --- |
| **Delay 2 s** | `transaction:delay_action` with span `processing` | Performance → Transactions tab |
| **Crash** | `error:RuntimeException` | Issues → caught by default uncaught-exception handler |
| **ANR 8 s** | `error:ApplicationNotResponding` | Busy-loop on main, not `Thread.sleep`, so the watchdog reports it like a real ANR |
| **Photo workflow (ok)** | `transaction:photo_workflow` with 3 child spans | Each span has `filename`, `size_bytes`, `storage_path`, `upload_url`, `server_ack` data |
| **Photo workflow (force fail)** | Same transaction, `sync_image` span status = `internal_error`, plus `error:PhotoSyncException` | Span `data.error_reason = upstream_500` |

## Context attached to every event

Set once on `Application.onCreate` via `SentryContextEnricher.installOnce`:

| Key | Source |
| --- | --- |
| `user.id` | `BuildConfig.DEMO_USER_ID` |
| tag `device.manufacturer` / `device.model` | `Build.MANUFACTURER` / `Build.MODEL` |
| tag `app.version` / `app.version_code` | `BuildConfig` |
| context `device_static.{sdk_int, abi}` | `Build.VERSION.SDK_INT`, `Build.SUPPORTED_ABIS[0]` |

Refreshed per-action via `SentryContextEnricher.enrich(actionName)`:

| Key | Source |
| --- | --- |
| tag `action_name` | passed by use case |
| context `device_runtime.free_ram_mb` / `total_ram_mb` | `ActivityManager.getMemoryInfo` |
| context `device_runtime.free_storage_mb` | `StatFs(Environment.getDataDirectory())` |
| context `device_runtime.network_speed_mbps` | `NetworkCapabilities.getLinkDownstreamBandwidthKbps` |
| context `device_runtime.network_transport` | `NetworkCapabilities.hasTransport(...)` (wifi / cellular / vpn) |
| context `device_runtime.battery_pct` | `Intent.ACTION_BATTERY_CHANGED` sticky broadcast |

## Setup

1. Bring Sentry up (see `../sentry-selfhost/README.md`).
2. In Sentry UI, create a project → platform **Android** → copy the DSN.
3. `cp local.properties.example local.properties` and fill in:
   - `sdk.dir` – your Android SDK root
   - `sentry.dsn` – use host `10.0.2.2` for emulator, your LAN IP for a physical device on Wi-Fi
4. `./gradlew :app:installDebug` (or open in Android Studio Giraffe+).

## Tests

Two layers — fast hermetic JVM/integration tests and emulator-driven UI tests.

### JVM (unit + integration)

```
./gradlew :app:testDebugUnitTest
```

These use `SentryCaptureRule` (`src/test/.../testing/SentryCaptureRule.kt`) which initializes the real Sentry SDK with `beforeSendTransaction`/`beforeSend`/`beforeBreadcrumb` interceptors that *capture and drop* every envelope — no network, no DSN required, fully deterministic. Asserts run against the typed `SentryTransaction` / `SentryEvent` / `Breadcrumb` objects directly.

Coverage:

| Suite | Cases | What it proves |
| --- | --- | --- |
| `SentryWorkflowTrackerTest` | 4 | Workflow DSL emits one named transaction with each `step()` as a child span; data attributes attach to the right span; failures mark spans `internal_error` and attach the throwable; `markFailed()` works without throwing; start/finish breadcrumbs are emitted in order |
| `PhotoWorkflowRepositoryImplTest` | 4 | capture/save/sync simulator returns sensible payloads; `forceFailure=true` always throws `PhotoSyncException` |
| `RunPhotoWorkflowUseCaseTest` | 2 | Happy-path workflow runs 3 OK spans + 3 step results; `forceFailure=true` marks only `sync_image` as FAILED, captures the exception, returns a partial report |
| `SimulateDelayUseCaseTest` | 1 | Delay action produces a `delay_action` transaction with a `processing` child span carrying `duration_ms` data |

### Instrumented (emulator/device)

```
./gradlew :app:connectedDebugAndroidTest
```

Uses a custom `HiltTestRunner` that boots `HiltTestApplication` (so the full Hilt graph wires up against test-time bindings) plus `MainActivity` (`@AndroidEntryPoint`).

Coverage:

| Suite | Cases | What it proves |
| --- | --- | --- |
| `DemoScreenInstrumentedTest` | 2 | All five demo buttons render with expected titles and there are exactly five `Run` actions; tapping the delay button drives the activity log to `delay_action: finished` within 5 s |

### Last verified results (Medium_Phone_API_35, Android 15)

| | Cases | Result | Wall |
| --- | --: | --- | --- |
| JVM tests | 11 | **all pass** | ~700 ms |
| Instrumented tests | 2 | **all pass** | ~5.2 s |
| **Total** | **13** | **all pass** | |

## ANR & crash delivery is asynchronous

On **Android 11 (API 30) and newer** Sentry uses `ApplicationExitInfo` (v2) for
ANR detection. The system records the ANR, but **the event only ships to Sentry
on the next app launch** — not at the moment the watchdog fires. Same story for
hard crashes: Sentry queues the event to disk in the dying process and the
queue flushes on the next start. So after tapping **Crash** or **ANR 8 s**:

1. Wait for the system to kill / restart the app (or kill it manually).
2. Re-open the app.
3. The event will appear in Sentry within a few seconds.

On Android ≤ 10 (watchdog v1), ANR events ship immediately, but you lose the
held-locks information that AppExitInfo provides.

## Troubleshooting

- **DSN warning at startup, no events show up** — `local.properties.sentry.dsn` is empty. The app still launches but Sentry is a no-op.
- **`InvalidDsnException`** — DSN must include scheme, host, port, public key, and project id. Compare to `http://abc123@10.0.2.2:9000/2`.
- **Crash event never appears** — see "ANR & crash delivery is asynchronous" above. Re-open the app after the crash.
- **ANR never reported** — same async caveat. Also confirm the busy-loop ran for the full 8 s (the watchdog threshold is 5 s).
- **Cleartext blocked** — your Sentry host is not in `network_security_config.xml`. Add its IP/host and rebuild.
- **Slow build / KSP errors** — Hilt requires `kapt → ksp` toolchain match. We use `ksp 2.0.21-1.0.27` against Kotlin `2.0.21`.

## Bumping Sentry

Update `gradle/libs.versions.toml`:

```toml
sentry = "8.x.y"
sentryGradle = "5.x.y"
```

Check release notes: <https://github.com/getsentry/sentry-java/releases>.
