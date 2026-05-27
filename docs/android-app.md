# Android app — technical reference

Complete walk-through of `android-app/`: package map, class responsibilities,
data flow, build configuration, and test architecture.

> **Quickstart** lives in [`android-app/README.md`](../android-app/README.md).
> This document goes deeper: what each class does, why it's structured the way
> it is, and how to extend it.

## Tech stack

| Layer | Choice | Version |
| --- | --- | --- |
| Language | Kotlin | 2.0.21 |
| Build | Gradle (AGP) | 8.7.3 |
| KSP | Kotlin Symbol Processing | 2.0.21-1.0.27 |
| DI | Hilt (Dagger) | 2.52 |
| UI | Jetpack Compose (BOM) | 2024.12.01 + Material 3 1.3.1 |
| Async | Kotlin Coroutines | 1.9.0 |
| Observability | Sentry Android SDK | 8.38.0 |
| Sentry Gradle plugin | `io.sentry.android.gradle` | 5.1.0 |
| JVM target | 17 | — |
| `compileSdk` / `targetSdk` | 35 (Android 15) | — |
| `minSdk` | 26 (Android 8.0 Oreo) | — |

Versions are centralized in `gradle/libs.versions.toml`. Bump Sentry by editing
the `sentry`/`sentryGradle` keys and matching the SDK release notes.

## Package map

```
app/src/main/java/io/pula/sentrydemo/
├── SentryDemoApplication.kt      Application class — initializes Sentry, calls Hilt
├── MainActivity.kt               @AndroidEntryPoint — hosts the Compose tree
│
├── di/
│   └── AppModule.kt              @Binds for PhotoWorkflowRepository
│
├── core/
│   ├── sentry/
│   │   ├── SentryWorkflowTracker.kt   DSL: runRoot { step(name) { data(...) } }
│   │   └── SentryContextEnricher.kt   Stamps user/device tags onto Sentry scope
│   └── device/
│       └── DeviceInfoProvider.kt      RAM, storage, network, battery, ABI…
│
├── domain/
│   ├── model/                     CapturedPhoto, SavedPhoto, SyncedPhoto, WorkflowReport
│   ├── repository/
│   │   └── PhotoWorkflowRepository    Interface — no Sentry types
│   └── usecase/
│       ├── SimulateDelayUseCase
│       ├── TriggerCrashUseCase
│       ├── TriggerAnrUseCase
│       └── RunPhotoWorkflowUseCase     Orchestrates the workflow
│
├── data/
│   └── repository/
│       └── PhotoWorkflowRepositoryImpl  Fake delays + 20% sync failure
│
└── presentation/
    ├── MainViewModel.kt          @HiltViewModel — one state, one action per button
    ├── DemoScreen.kt             Compose — 5 buttons + scrolling activity log
    └── theme/                    Material 3 color/typography/shape
```

## Class responsibilities

### `SentryDemoApplication`

```kotlin
@HiltAndroidApp
class SentryDemoApplication : Application() {
    @Inject lateinit var contextEnricher: SentryContextEnricher

    override fun onCreate() {
        // Sentry init BEFORE super.onCreate so the uncaught-exception handler
        // and ANR watchdog also cover failures during DI graph construction.
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = BuildConfig.SENTRY_ENV
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"

            options.tracesSampleRate = 1.0
            options.profilesSampleRate = 1.0
            options.isAnrEnabled = true
            options.anrTimeoutIntervalMillis = 5_000L
            options.isAnrReportInDebug = true
            options.isAttachThreads = true
            options.isSendDefaultPii = true
            options.isEnableAutoActivityLifecycleTracing = true
            options.isEnableUserInteractionTracing = true
        }

        super.onCreate()
        contextEnricher.installOnce()
    }
}
```

**Why init before `super.onCreate()`?** The Sentry uncaught-exception handler
chains *after* the system's default. Initializing it before Hilt means any
exception thrown while building the DI graph (e.g. a misconfigured `@Provides`)
is also reported. The order is documented in the Sentry Android SDK release
notes.

**DSN is empty by default.** If `local.properties` is missing the `sentry.dsn`
key, the app logs a warning and Sentry becomes a no-op. The app still launches
— useful for screenshot/UI work without a backend running.

### `SentryWorkflowTracker`

Three nested types form the DSL:

```kotlin
suspend fun <T> runRoot(name, operation, block: suspend WorkflowScope.() -> T): T
interface WorkflowScope { suspend fun <T> step(name, block: suspend StepScope.() -> T): T }
interface StepScope { fun data(key, value); fun markFailed(reason) }
```

Lifecycle of one call to `runRoot("photo_workflow", "task") { ... }`:

1. `Sentry.startTransaction("photo_workflow", "task", { isBindToScope = true })`
2. Breadcrumb `category=photo_workflow, message=workflow.started, level=info`
3. Block runs. Each `step("capture_image") { ... }`:
   - `transaction.startChild("capture_image")`
   - Breadcrumb `capture_image.started`
   - Block runs. `data("key", v)` calls `span.setData("key", v ?: "null")`.
     `markFailed("reason")` sets `span.data["error_reason"]` and marks the
     `StepScope` so the wrapper finalizes the span as `INTERNAL_ERROR`.
   - On exception: `span.throwable = t`, `span.finish(INTERNAL_ERROR)`,
     breadcrumb `capture_image.failed: <msg>`, rethrow.
   - On success without `markFailed`: `span.finish(OK)`, breadcrumb
     `capture_image.finished status=OK`.
4. On block success: `transaction.finish(OK)`, breadcrumb `workflow.finished`.
5. On block exception: `transaction.throwable = t`, `transaction.finish(INTERNAL_ERROR)`,
   breadcrumb `workflow.failed`, `Sentry.captureException(t)`, rethrow.

**Why also `captureException`?** Sentry marks the transaction itself with the
throwable, but to make the error show up in the *Issues* tab (vs. only the
*Performance* tab) we need an explicit `captureException`. The exception ends
up cross-linked: clicking the Issue links to the transaction and vice versa.

### `SentryContextEnricher`

Two methods, two intents:

- `installOnce()` — call once in `Application.onCreate`. Sets `user.id`,
  static tags (`device.manufacturer`, `device.model`, `app.version`,
  `app.version_code`) and the `device_static` context.
- `enrich(actionName)` — call right before each demo action. Sets the
  `action_name` tag and refreshes the `device_runtime` context (free RAM, free
  storage, network speed, network transport, battery percentage).

`device_runtime` is **not** part of static install — those fields change while
the app runs, so we re-read them per action to ensure the captured event
reflects the device state at the moment the action ran (not at app start).

### `DeviceInfoProvider`

Wraps the system services so `SentryContextEnricher` stays trivial. Two data
classes (`StaticInfo`, `RuntimeSnapshot`). Notable choices:

- Battery via `ACTION_BATTERY_CHANGED` sticky broadcast (no permission needed
  on any SDK level).
- Free storage via `StatFs(Environment.getDataDirectory())` — measures
  internal-data space, the bucket where Sentry caches envelopes.
- Network speed via `NetworkCapabilities.linkDownstreamBandwidthKbps` →
  Mbps. Returns `0` when no active network or no caps.
- Transport label resolved through `hasTransport(TRANSPORT_WIFI)` etc.

### Use cases

Each use case is a single-method class injected into `MainViewModel`. They are
the only classes besides `core/sentry/*` that touch the Sentry SDK.

| Use case | Sentry signal | Sentry call |
| --- | --- | --- |
| `SimulateDelayUseCase` | Performance transaction | `Sentry.startTransaction("delay_action", "task")` + child span `processing` |
| `TriggerCrashUseCase` | Issue (uncaught) | none — relies on `UncaughtExceptionHandler` set by `SentryAndroid.init` |
| `TriggerAnrUseCase` | ANR event | none — relies on ANR watchdog (5 s) |
| `RunPhotoWorkflowUseCase` | Transaction with 3 spans (+ optional exception) | delegates to `SentryWorkflowTracker.runRoot { step("capture_image") { ... } step("save_image") { ... } step("sync_image") { ... } }` |

### `MainViewModel` & `DemoScreen`

`MainViewModel` exposes a single `DemoUiState`:

```kotlin
data class DemoUiState(
    val busy: Boolean = false,
    val lastAction: String? = null,
    val lastReport: WorkflowReport? = null,
    val statusLog: List<String> = emptyList(),
)
```

`busy` gates the two coroutine-launched buttons (delay, photo workflow) so
double-tap can't fire two workflows simultaneously. Crash and ANR buttons are
**not** gated — by design, since the whole point is to demonstrate them.

`DemoScreen` is a single scrollable Column with five `DemoButton` cards and an
activity log fed by `MainViewModel.log(...)`. The log is bounded
to the last 120 lines so long sessions don't OOM the recomposition tree.

## Configuration

Three values come in via `local.properties` and surface as `BuildConfig` fields:

| `local.properties` key | `BuildConfig` field | Default | Where it's used |
| --- | --- | --- | --- |
| `sentry.dsn` | `SENTRY_DSN` | `""` | `SentryDemoApplication.onCreate` → `options.dsn` |
| `sentry.environment` | `SENTRY_ENV` | `"debug"` | `options.environment` |
| `sentry.userId` | `DEMO_USER_ID` | `"demo-user-001"` | `SentryContextEnricher.installOnce` |
| `sdk.dir` | n/a | — | Android SDK path (read by Gradle, not used at runtime) |

`local.properties` is **not** checked into git. See
[`configuration.md`](./configuration.md) for the canonical reference.

## Network security configuration

`res/xml/network_security_config.xml` whitelists cleartext HTTP for local
Sentry self-hosted addresses only:

```xml
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">10.0.2.2</domain>       <!-- AVD loopback -->
    <domain includeSubdomains="true">127.0.0.1</domain>
    <domain includeSubdomains="true">localhost</domain>
    <domain includeSubdomains="true">192.168.0.0</domain>
    <domain includeSubdomains="true">192.168.1.0</domain>
  </domain-config>
</network-security-config>
```

For production builds: switch the Sentry backend to HTTPS, remove the cleartext
exceptions, and rebuild.

## Sentry Gradle plugin

```kotlin
// app/build.gradle.kts
sentry {
    autoUploadProguardMapping.set(false)
    includeProguardMapping.set(false)
    autoUploadNativeSymbols.set(false)
    tracingInstrumentation {
        enabled.set(true)
        features.set(setOf(
            InstrumentationFeature.DATABASE,
            InstrumentationFeature.FILE_IO,
            InstrumentationFeature.OKHTTP,
            InstrumentationFeature.COMPOSE,
        ))
    }
}
```

`tracingInstrumentation` enables **bytecode-level** auto-instrumentation:

- `DATABASE` — Room queries become child spans on the active transaction.
- `FILE_IO` — `java.io.File` calls become spans.
- `OKHTTP` — OkHttp calls (none in the demo, but ready) become spans.
- `COMPOSE` — recomposition counts and per-screen render times are reported.

**ProGuard mapping & source context uploads are disabled** by default. To
enable in CI:

1. Generate a project auth token: Sentry → Settings → Account → API → Auth
   Tokens → New (scope: `project:releases`).
2. Set `SENTRY_AUTH_TOKEN` env var in the build job.
3. Flip the plugin flags:
   ```kotlin
   autoUploadProguardMapping.set(true)
   includeProguardMapping.set(true)
   ```
4. Set `org` and `projectName` in the plugin block, or provide them via
   `sentry-cli` env (`SENTRY_ORG`, `SENTRY_PROJECT`).

Release builds without uploaded mappings show **obfuscated** stack frames in
Sentry — readable only with a corresponding `mapping.txt`.

## Crash & ANR delivery is asynchronous

On Android 11+ (API 30+), Sentry uses `ApplicationExitInfo` (v2) for ANR
detection. The system records the ANR, but the event **only ships to Sentry on
the next app launch** — the dying process can't reliably finish a network
request. Same story for hard crashes: the SDK queues the envelope to disk in
the dying process; the queue flushes on the next start.

Operationally, after tapping **Crash** or **ANR 8 s**:

1. Wait for the system to kill / restart the app (or kill it manually).
2. Re-open the app.
3. The event will appear in Sentry within a few seconds.

On Android ≤ 10 (watchdog v1), ANR events ship immediately, but you lose the
held-locks information that AppExitInfo provides.

## Test architecture

Two layers — JVM tests (hermetic, fast) and instrumented tests
(emulator/device, slow but real).

### JVM tests — `app/src/test/`

Uses **Robolectric** (`org.robolectric:robolectric:4.14`) so we can pull in real
Android classes (`Build.MANUFACTURER`, `ActivityManager`, etc.) without an
emulator.

The key piece is `SentryCaptureRule` (a JUnit `@Rule`):

```kotlin
class SentryCaptureRule : TestWatcher() {
    val transactions: MutableList<SentryTransaction> = mutableListOf()
    val events: MutableList<SentryEvent> = mutableListOf()
    val breadcrumbs: MutableList<Breadcrumb> = mutableListOf()

    override fun starting(description: Description) {
        Sentry.init { options ->
            options.dsn = "http://publickey@localhost/1"
            options.tracesSampleRate = 1.0
            options.beforeSendTransaction = { tx, _ -> transactions += tx; null }
            options.beforeSend = { event, _ -> events += event; null }
            options.beforeBreadcrumb = { crumb, _ -> breadcrumbs += crumb; crumb }
        }
    }
    override fun finished(description: Description) { Sentry.close() }
}
```

By returning `null` from `beforeSend*`, the SDK **drops** the envelope before
transport — no network, no DSN, fully deterministic. Tests assert against the
typed `SentryTransaction` / `SentryEvent` / `Breadcrumb` objects.

Suites:

| Suite | Cases | Asserts |
| --- | --- | --- |
| `SentryWorkflowTrackerTest` | 4 | One named transaction; each `step()` is a child span; `data()` lands on the right span; failures mark `INTERNAL_ERROR` and attach the throwable; breadcrumbs emitted in start/finish order |
| `PhotoWorkflowRepositoryImplTest` | 4 | Fake capture/save/sync return sensible payloads; `forceFailure=true` always throws `PhotoSyncException` |
| `RunPhotoWorkflowUseCaseTest` | 2 | Happy-path = 3 OK spans + 3 step results; `forceFailure=true` marks only `sync_image` FAILED, captures the exception, returns a partial report |
| `SimulateDelayUseCaseTest` | 1 | `delay_action` transaction carries a `processing` child span with `duration_ms` data |

Run:

```bash
./gradlew :app:testDebugUnitTest
```

### Instrumented tests — `app/src/androidTest/`

Uses a custom test runner that boots `HiltTestApplication`:

```kotlin
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, ctx: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, ctx)
}
```

Wired in `app/build.gradle.kts` via:

```kotlin
testInstrumentationRunner = "io.pula.sentrydemo.HiltTestRunner"
```

Suite:

| Suite | Cases | Asserts |
| --- | --- | --- |
| `DemoScreenInstrumentedTest` | 2 | All five demo buttons render; exactly five "Run" actions; tapping delay drives the activity log to `delay_action: finished` within 5 s |

Run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

**Last verified** (Medium_Phone_API_35, Android 15):
- JVM tests: 11/11 pass in ~700 ms
- Instrumented: 2/2 pass in ~5.2 s

## Extension points

Adding a new demo action takes four edits:

1. **`domain/usecase/`** — new use case class. Wrap your work in
   `workflowTracker.runRoot(...)` or `Sentry.startTransaction(...)`.
2. **`MainViewModel`** — inject the use case, expose `onXxx()`, gate with
   `busy` if it's async.
3. **`DemoScreen`** — add a `DemoButton(title=..., subtitle=...)`.
4. **`MainViewModel` + tests** — add a `runXxx` test in
   `domain/usecase/…UseCaseTest.kt` and assert the captured transaction/spans.

Adding a new field to every event:

1. Extend `DeviceInfoProvider.RuntimeSnapshot` (or `StaticInfo`).
2. Read it in `SentryContextEnricher.enrich(...)` (or `installOnce(...)`).
3. Add an assertion in `SentryContextEnricherTest` (you'll need to write this).

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| DSN warning at startup, no events appear | `local.properties.sentry.dsn` is empty | Set it; re-run `./gradlew installDebug` |
| `InvalidDsnException` | Malformed DSN | Compare to `http://abc123@10.0.2.2:9000/2` — needs scheme, host, port, public key, project id |
| Crash event never appears | See "Crash & ANR delivery is asynchronous" above | Re-open the app after the crash |
| ANR never reported | Same async caveat | Confirm the busy-loop ran for the full 8 s (watchdog threshold is 5 s) |
| Cleartext blocked | Sentry host not in `network_security_config.xml` | Add its IP/host and rebuild |
| Slow build / KSP errors | Toolchain mismatch | We use KSP `2.0.21-1.0.27` against Kotlin `2.0.21` — keep them in lockstep |
