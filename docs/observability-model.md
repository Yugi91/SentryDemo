# Observability data model

What the Android client emits, where it lands in Sentry, and how to find it
in the UI. Use this as a reference when extending the demo or debugging why
an event isn't showing up the way you expect.

## Sentry primitives in play

The demo uses four primitives from the Sentry data model:

| Primitive | What it represents | Where in Sentry UI |
| --- | --- | --- |
| **Event** | A point-in-time signal (error, message). Has stack trace, tags, contexts, breadcrumbs. | Issues |
| **Transaction** | A trace root — a named work unit with a start/end and a tree of spans. | Performance → Transactions |
| **Span** | A leaf or sub-tree under a transaction. Carries `op`, `description`, `data`, `status`. | Performance → Transaction detail |
| **Breadcrumb** | Lightweight log line attached to the next event/transaction. | Event detail → Breadcrumbs panel |

Plus two cross-cutting layers:

- **Scope** — global state (tags, contexts, user) that gets stamped onto every
  subsequent event/transaction emitted from the same thread.
- **Envelope** — the on-wire container the SDK ships to Sentry. One envelope
  can carry multiple events/transactions/attachments. The relay accepts
  envelopes.

## Per-button event schema

What each demo button emits, indexed by Sentry surface.

### 1. Delay action

Emits **one transaction**. No event.

```
Transaction
  name:        delay_action
  op:          task
  status:      OK
  duration:    ~2.0 s (configurable via durationMs)
  Span:
    op:          (no explicit op set)
    description: simulated long-running work
    data:        duration_ms = 2000
    status:      OK
```

Where to look:

- Sentry → **Performance → Transactions** → `delay_action`
- Click to open → see the `processing` child span

Source: `SimulateDelayUseCase.kt`

### 2. Crash action

Emits **one event** (no transaction). Delivered on the next app launch
because the SDK queues the envelope to disk in the dying process.

```
Event
  level:       fatal
  type:        error
  exception:   RuntimeException
  message:     "Demo crash triggered from UI at <epoch_ms>"
  stack trace: yes (deobfuscated when ProGuard mapping uploaded)
```

Where to look:

- Sentry → **Issues** → search for `RuntimeException: Demo crash`
- Issue detail → Events tab to see individual occurrences

Source: `TriggerCrashUseCase.kt`

### 3. ANR action (block 8 s)

Emits **one event** (no transaction). Delivered on the next app launch (Android
11+) via `ApplicationExitInfo`. On Android ≤ 10, ships immediately but without
held-locks info.

```
Event
  level:       error
  type:        error
  exception:   ApplicationNotResponding
  message:     "Application Not Responding for at least 5000 ms."
  stack trace: full main-thread trace at the moment the watchdog tripped
  threads:     all threads attached (because isAttachThreads = true)
```

Where to look:

- Sentry → **Issues** → filter by `error.type:ApplicationNotResponding`

Source: `TriggerAnrUseCase.kt`

### 4a. Photo workflow — happy path

Emits **one transaction** with three child spans. No event.

```
Transaction
  name:        photo_workflow
  op:          task
  status:      OK
  duration:    ~2.2 s (700 + 350 + 1100 ms simulated delays)

  ├─ Span "capture_image"
  │    status: OK
  │    data:
  │      filename     = img_<epoch_ms>.jpg
  │      size_bytes   = ~1.5 MB
  │      width        = 1920
  │      height       = 1080
  │
  ├─ Span "save_image"
  │    status: OK
  │    data:
  │      storage_path = /storage/emulated/0/.../<filename>
  │      storage_kind = internal
  │
  └─ Span "sync_image"
       status: OK
       data:
         upload_endpoint = https://api.example.com/v1/photos
         force_failure   = false
         upload_url      = https://cdn.example.com/.../<filename>
         server_ack      = true
```

Plus a breadcrumb trail attached to whatever comes next:

```
[INFO]    photo_workflow → workflow.started
[INFO]    photo_workflow → capture_image.started
[INFO]    photo_workflow → capture_image.finished status=OK
[INFO]    photo_workflow → save_image.started
[INFO]    photo_workflow → save_image.finished status=OK
[INFO]    photo_workflow → sync_image.started
[INFO]    photo_workflow → sync_image.finished status=OK
[INFO]    photo_workflow → workflow.finished
```

Where to look:

- Sentry → **Performance → Transactions** → `photo_workflow`
- Click to open → flame graph of capture/save/sync spans with their `data` payload

### 4b. Photo workflow — force fail

Same transaction structure, but `sync_image` is marked failed and an exception
is captured.

```
Transaction photo_workflow
  status: INTERNAL_ERROR
  ├─ Span capture_image  status: OK
  ├─ Span save_image     status: OK
  └─ Span sync_image     status: INTERNAL_ERROR
       data:
         force_failure = true
         error_reason  = "Upload failed: HTTP 500 from upstream"
       throwable: PhotoSyncException(...)

Event
  level:     error
  type:      error
  exception: PhotoSyncException("Upload failed: HTTP 500 from upstream")
  trace_id:  same as the transaction above (cross-linked in UI)
```

Where to look:

- Sentry → **Performance → Transactions** → `photo_workflow` → "Failed"
  status filter
- Sentry → **Issues** → `PhotoSyncException`
- Both surfaces link to each other via the shared `trace_id`

Source: `RunPhotoWorkflowUseCase.kt`, `PhotoWorkflowRepositoryImpl.kt`

## Context attached to every event

Stamped onto the Sentry scope by `SentryContextEnricher`. Two passes:

### Static (set once on app start)

Via `SentryContextEnricher.installOnce()` from `SentryDemoApplication.onCreate`:

| Field | Sentry primitive | Source | Example |
| --- | --- | --- | --- |
| `user.id` | `event.user.id` | `BuildConfig.DEMO_USER_ID` | `demo-user-001` |
| `device.manufacturer` | tag | `Build.MANUFACTURER` | `google` |
| `device.model` | tag | `Build.MODEL` | `sdk_gphone64_arm64` |
| `app.version` | tag | `BuildConfig.VERSION_NAME` | `1.0.0` |
| `app.version_code` | tag | `BuildConfig.VERSION_CODE` | `1` |
| `device_static.manufacturer` | context | `Build.MANUFACTURER` | … |
| `device_static.model` | context | `Build.MODEL` | … |
| `device_static.sdk_int` | context | `Build.VERSION.SDK_INT` | `35` |
| `device_static.app_version` | context | `BuildConfig.VERSION_NAME` | … |
| `device_static.app_version_code` | context | `BuildConfig.VERSION_CODE` | … |
| `device_static.abi` | context | `Build.SUPPORTED_ABIS[0]` | `arm64-v8a` |

**Why both tags and context?** Tags are indexed (searchable, groupable in the
UI); contexts are not but can hold richer data. We keep frequently-filtered
fields as tags and put the full set in the `device_static` context for
inspection.

### Volatile (refreshed per action)

Via `SentryContextEnricher.enrich(actionName)` from each use case:

| Field | Sentry primitive | Source |
| --- | --- | --- |
| `action_name` | tag | passed by the use case |
| `device_runtime.free_ram_mb` | context | `ActivityManager.getMemoryInfo().availMem / 1MB` |
| `device_runtime.total_ram_mb` | context | `ActivityManager.getMemoryInfo().totalMem / 1MB` |
| `device_runtime.free_storage_mb` | context | `StatFs(Environment.getDataDirectory())` |
| `device_runtime.network_speed_mbps` | context | `NetworkCapabilities.linkDownstreamBandwidthKbps / 1000` |
| `device_runtime.network_transport` | context | `wifi` / `cellular` / `ethernet` / `vpn` / `none` |
| `device_runtime.battery_pct` | context | `ACTION_BATTERY_CHANGED` sticky broadcast |

`action_name` is **the** key dimension for filtering. In the Sentry UI:

- Issues → filter `action_name:photo_workflow` → all crashes/ANRs that
  happened while the photo workflow ran.
- Performance → filter `action_name:delay_action` → only delay transactions.

## Auto-instrumentation from the Sentry Gradle plugin

The plugin instruments bytecode for four feature flags (set in
`app/build.gradle.kts`):

| Flag | Adds | Visible as |
| --- | --- | --- |
| `DATABASE` | Spans around Room queries | Child spans on the active transaction with `op=db`, description=SQL |
| `FILE_IO` | Spans around `java.io.File` calls | `op=file`, description=path |
| `OKHTTP` | Spans around OkHttp calls | `op=http.client`, description=URL |
| `COMPOSE` | Composable recomposition counters + per-screen render times | Performance → Mobile screens |

The demo doesn't use Room, OkHttp, or files directly — these are enabled
ready-to-go so you can drop in real I/O and get instrumentation for free.

## Auto-instrumentation from `SentryAndroid.init`

| Option | Adds |
| --- | --- |
| `isEnableAutoActivityLifecycleTracing = true` | Implicit transactions around `Activity.onResume → onPause` |
| `isEnableUserInteractionTracing = true` | Implicit transactions around significant touch interactions |
| `isAnrEnabled = true` | Watchdog thread that posts a sentinel to the main thread every 100 ms; if more than 5 s elapses, report ANR |
| `isAttachThreads = true` | All thread stack traces attached to every event |
| `isSendDefaultPii = true` | Includes IP address and user-agent in events |

## Releases

The release identifier is set in `SentryDemoApplication.onCreate`:

```kotlin
options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
// e.g. io.pula.sentrydemo@1.0.0
```

Sentry uses this to:

- Show release adoption (which versions are still active)
- Compare error rates across releases (regression detection)
- Link to source maps / ProGuard mappings (when uploaded by the Gradle plugin)

To bump:

1. `versionCode = 2; versionName = "1.0.1"` in `app/build.gradle.kts`.
2. Rebuild. New events tag as `io.pula.sentrydemo@1.0.1`.
3. (CI) Upload mapping via Sentry Gradle plugin with `autoUploadProguardMapping = true`.

## Trace context

Every transaction has a `trace_id`. Child spans share it. Errors captured
*during* a transaction inherit it. This is what lets the UI:

- Show an error and "this is the transaction it happened in" in one click.
- Show a transaction and "this is the error that fired during it" in one click.

The trace ID is propagated automatically — you don't have to set anything.
Just make sure your transaction is active when the error fires (i.e. the
exception is caught *inside* the `runRoot { ... }` block).

`SentryWorkflowTracker.runRoot` sets `isBindToScope = true` so the
transaction binds to the current scope and downstream `Sentry.captureException`
calls inherit it.

## Searching in the Sentry UI

Useful filters once data is flowing:

| Filter | Surface | What it shows |
| --- | --- | --- |
| `action_name:photo_workflow` | Issues / Performance | Anything that happened while photo workflow ran |
| `app.version:1.0.0` | Issues | Events from a specific app version |
| `device.manufacturer:samsung` | Issues | Events from Samsung devices only |
| `transaction:delay_action` | Performance | Only delay transactions |
| `transaction.status:internal_error` | Performance | Only failed transactions |
| `error.type:ApplicationNotResponding` | Issues | Only ANR events |
| `os.name:Android os.version:14` | Issues | Events from Android 14 devices |
| `release:io.pula.sentrydemo@1.0.0` | Issues / Performance | Events from this exact release |

Combine with `AND` / `OR`:

```
action_name:photo_workflow AND transaction.status:internal_error
release:io.pula.sentrydemo@1.0.1 AND device.manufacturer:google
```

## Verifying instrumentation in tests

`SentryCaptureRule` (in `app/src/test/`) gives you typed assertions against
captured `SentryTransaction` / `SentryEvent` / `Breadcrumb` objects without a
network. Example:

```kotlin
@get:Rule val sentry = SentryCaptureRule()

@Test fun photo_workflow_emits_three_spans() = runTest {
    useCase()                    // exercise

    val tx = sentry.transactions.single()
    assertThat(tx.transaction).isEqualTo("photo_workflow")
    val spans = tx.spans
    assertThat(spans.map { it.op }).containsExactly("capture_image", "save_image", "sync_image")
}
```

See [`android-app.md`](./android-app.md#test-architecture) for the full
testing model.

## Common assertions

When you're not sure if instrumentation is working as designed:

| What | How to verify in Sentry |
| --- | --- |
| Transaction name is right | Performance → Transactions → name column |
| Span has the data you expected | Click into the transaction → click the span → "Tags & Data" panel |
| Event is linked to a transaction | Issue detail → "Trace" tab shows the transaction tree |
| Breadcrumbs are present | Event detail → "Breadcrumbs" panel (bottom) |
| User ID is on the event | Event detail → top right → "User" card |
| `action_name` tag is on the event | Event detail → "Tags" panel |
| Device runtime context is fresh | Event detail → "Contexts" panel → "device_runtime" card → check `free_ram_mb` reflects the moment of the action |

If something's missing, the most common causes are:

1. **The action ran on a thread without the bound transaction.**
   `runRoot { ... }` uses `isBindToScope = true` so it's safe, but if you
   `withContext(Dispatchers.IO)` *inside* a `step { }`, the scope is
   inherited; if you launch a separate coroutine, it isn't.
2. **The DSN is wrong** — events go nowhere, you see no errors locally.
   Check `adb logcat -s Sentry` for init messages.
3. **Sampling dropped the event.** Demo runs at `tracesSampleRate = 1.0`
   (100%); production typically lower. Check `options.tracesSampleRate` if
   transactions are sporadic.
