# Sentry self-hosted — technical reference

Map of the upstream Sentry self-hosted compose graph, the surfaces this wrapper
exposes, and the failure modes you'll hit in practice.

> **Quickstart** lives in [`sentry-selfhost/README.md`](../sentry-selfhost/README.md).
> **Deployment** (prerequisites, end-to-end install on a fresh box) is in
> [`deployment.md`](./deployment.md). This document explains the topology and
> the override layer.

## What this directory is

A wrapper around [`getsentry/self-hosted`](https://github.com/getsentry/self-hosted)
pinned at tag **`25.8.0`**. Our `setup.sh` does three things:

1. Shallow-clones the upstream repo into `.self-hosted/` at the pinned tag.
2. Copies `docker-compose.override.yml` and `.env` into the clone.
3. Runs upstream `install.sh --skip-user-creation`.

Everything inside `.self-hosted/` is upstream-managed and `.gitignore`'d. We
own only the wrapper files at this directory's root.

## Files we own

| File | Purpose |
| --- | --- |
| `setup.sh` | Idempotent bootstrap: clone upstream, layer overrides, run `install.sh` |
| `docker-compose.override.yml` | Port mapping (`SENTRY_BIND:9000`) and JSON log rotation on every chatty service |
| `.env.example` | Reference for `.env` (which is gitignored) |
| `.env` | Local overrides — appended onto upstream's `.env` (gitignored) |
| `README.md` | Operator quickstart |

Pinned upstream version is set in `setup.sh`:

```bash
SENTRY_VERSION="${SENTRY_VERSION:-25.8.0}"
```

Override per-invocation with `SENTRY_VERSION=26.5.0 ./setup.sh`.

## Service topology

Upstream `docker-compose.yml` defines ~30 services. They fall into three tiers:

### Edge tier — request ingestion

| Service | Image | Role |
| --- | --- | --- |
| `nginx` | `nginx:1.25.4-alpine` | TLS termination (HTTP-only in this demo), routes UI to `web`, ingestion to `relay` |
| `relay` | `ghcr.io/getsentry/relay:25.8.0` | First touch for every event: deduplication, sampling, normalization, rate limiting |

`relay` writes to Kafka; `web` reads from Postgres + Snuba (Clickhouse).

### App tier — business logic & async work

| Service | Image | Role |
| --- | --- | --- |
| `web` | `ghcr.io/getsentry/sentry:25.8.0` | Django web UI + ingestion API (`/api/<id>/envelope/`) |
| `worker` | same | Celery workers: alerts, notifications, post-processing |
| `cron` | same | Celery beat: nightly cleanups, retention pruning |
| `events-consumer` / `transactions-consumer` / `issues-consumer` / etc. | same | Kafka → Postgres/Clickhouse fan-out |
| `symbolicator` | `ghcr.io/getsentry/symbolicator:25.8.0` | Resolves native stack frames (NDK, iOS dSYMs) |
| `vroom` | `ghcr.io/getsentry/vroom:25.8.0` | Profile ingestion & flamegraph generation |
| `taskbroker` | `ghcr.io/getsentry/taskbroker:25.8.0` | New task queue (replaces Celery for some workloads) |
| `uptime-checker` | `ghcr.io/getsentry/uptime-checker:25.8.0` | Synthetic uptime monitoring |
| `snuba-api` + many `snuba-*-consumer` | `ghcr.io/getsentry/snuba:25.8.0` | Clickhouse query layer |

### Data tier — persistence

| Service | Image | Stores |
| --- | --- | --- |
| `postgres` | `postgres:14.11` | Issues, projects, users, orgs, alert rules, audit log |
| `clickhouse` | self-hosted image | Events, transactions, replays, profiles, sessions, generic metrics |
| `kafka` | `confluentinc/cp-kafka:7.6.1` | Ingestion buffer between `relay` and the consumers |
| `redis` | `redis:6.2.14-alpine` | Rate limit counters, Celery results, deduplication |
| `memcached` | `memcached:1.6.26-alpine` | Cache layer in front of Postgres |
| `smtp` | `registry.gitlab.com/egos-tech/smtp` | Local mail relay for notification testing |

## Compose profiles

Upstream defines `COMPOSE_PROFILES` in `.self-hosted/.env`:

```
COMPOSE_PROFILES=feature-complete
```

Two options:

- **`feature-complete`** (default) — all features: errors, performance, replays,
  profiles, sessions, uptime, metrics.
- **`errors-only`** — strips Clickhouse, Snuba, Kafka, performance consumers,
  replays, profiles. Drastically lighter (~4 GB RAM minimum vs 16 GB). Set in
  `.self-hosted/.env` if you only need crashes/issues. See
  [Sentry docs](https://develop.sentry.dev/self-hosted/experimental/errors-only/).

## Our overrides

### `docker-compose.override.yml`

```yaml
x-log-rotation: &log-rotation
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"

services:
  web:
    ports:
      - "${SENTRY_BIND:-9000}:9000"
    logging: *log-rotation

  relay:
    logging: *log-rotation
  worker:
    logging: *log-rotation
  postgres:
    logging: *log-rotation
  kafka:
    logging: *log-rotation
  clickhouse:
    logging: *log-rotation
  snuba-api:
    logging: *log-rotation
```

Two things:

1. **`SENTRY_BIND`** lets you change the host port without editing
   `docker-compose.yml`. Defaults to `9000`. Set in `.env` to e.g.
   `SENTRY_BIND=127.0.0.1:9000` to bind to loopback only.
2. **Log rotation** — `postgres`, `kafka`, `clickhouse`, `relay`, `worker`,
   `snuba-api` will otherwise grow Docker JSON logs unbounded. 10 MB × 3 files
   per service keeps total log spend bounded at ~180 MB across the seven we
   override.

### `.env`

Layered onto upstream's `.env` (which `install.sh` manages). Our values
override upstream's because dotenv parsing is last-write-wins.

```bash
SENTRY_BIND=9000
# SENTRY_SECRET_KEY=  ← do NOT set; install.sh manages this
SENTRY_EVENT_RETENTION_DAYS=30
# SENTRY_EMAIL_HOST=smtp.gmail.com
# SENTRY_EMAIL_PORT=587
# SENTRY_EMAIL_USER=
# SENTRY_EMAIL_PASSWORD=
# SENTRY_EMAIL_USE_TLS=true
```

Important: **don't redeclare `SENTRY_SECRET_KEY`**. `install.sh` generates it
on first run and stores it in `.self-hosted/.env`. A fresh
`SENTRY_SECRET_KEY` invalidates every existing project's DSN and forces all
sessions to re-issue. See [`operations.md`](./operations.md#secret-rotation).

## Networking

| Surface | URL | What it's for |
| --- | --- | --- |
| Web UI | `http://localhost:9000` | Operator + developer console |
| Health probe | `http://localhost:9000/_health/` | Returns 200 once `web` is ready |
| Ingestion endpoint | `http://localhost:9000/api/<project_id>/envelope/` | Where the Android SDK POSTs envelopes |

### From an Android emulator (AVD)

The emulator can't reach `localhost` on the host directly. Use `10.0.2.2`:

```
sentry.dsn=http://<public_key>@10.0.2.2:9000/<project_id>
```

`10.0.2.2` is the host's loopback as seen by the AVD. Pre-allowed in
`android-app/app/src/main/res/xml/network_security_config.xml`.

### From a physical device on the same Wi-Fi

Use your machine's LAN IP, e.g.:

```
sentry.dsn=http://<public_key>@192.168.1.20:9000/<project_id>
```

`192.168.0.0` and `192.168.1.0` ranges are pre-allowed in the cleartext config.
Add your specific range if it's outside those two.

> Sentry validates DSNs by `public_key` + `project_id`, **not** by host. You
> can rewrite the DSN host in `local.properties` without recreating the
> project — useful when bouncing between emulator and physical device.

## Volumes

Upstream defines named volumes for every stateful service. The big ones:

| Volume | Service | What it stores |
| --- | --- | --- |
| `sentry-postgres` | postgres | Issues, projects, users — durable metadata |
| `sentry-clickhouse` | clickhouse | Events, transactions, profiles — high-volume |
| `sentry-kafka` | kafka | Ingestion buffer (transient but on disk) |
| `sentry-data` | web/worker | Sentry app data (attachments, file uploads) |
| `sentry-zookeeper` | kafka | ZK metadata (Kafka 7 still uses ZK) |
| `sentry-redis` | redis | Counters, queues |
| `sentry-symbolicator` | symbolicator | Debug symbol cache |
| `sentry-vroom-profiles` | vroom | Profile envelopes |

Wipe everything with `docker compose down -v` from inside `.self-hosted/`. See
[`operations.md`](./operations.md#full-reset).

## Resource footprint

Upstream's documented minimum for `feature-complete`:

- 4 CPU cores
- 16 GB RAM + 16 GB swap (Docker Desktop counts toward this)
- 20 GB free disk (without retention pruning, expect ~5–10 GB/month for a
  modestly active project)

`install.sh` enforces this on startup. If your Docker Desktop has less RAM
allocated, it bails with `✗ Memory size detected (... MB) is less than ...`.

### Lighter setups

- **`errors-only` profile** — drop Clickhouse/Kafka/Snuba/replays/profiles. ~4 GB
  RAM minimum.
- **External Postgres/Kafka/Redis** — replace the bundled service with a
  managed one. Requires editing `docker-compose.yml` and the corresponding
  `sentry.conf.py`. Out of scope for this demo.

See [`deployment.md`](./deployment.md) for the full prerequisite matrix.

## Multi-arch & Apple Silicon

All upstream images publish multi-arch manifests including `linux/arm64`. M1+
Macs work without `--platform` hacks. If you ever need to pin a legacy x86
service, set `DOCKER_PLATFORM=linux/amd64` in `.env` — Docker Desktop's
Rosetta 2 emulation will run it (verify Rosetta is enabled in Settings →
General).

## Lifecycle

```bash
# Start
cd sentry-selfhost/.self-hosted
docker compose up -d

# Stop (keep volumes)
docker compose down

# Tail web logs
docker compose logs -f web

# Service status
docker compose ps

# Re-run installer (idempotent, fixes broken state)
./install.sh --skip-user-creation

# Full wipe
docker compose down -v && rm -rf .self-hosted && cd .. && ./setup.sh
```

## Caveats

- **First boot is slow** — Clickhouse, Kafka, Snuba, Relay take 2–3 minutes to
  settle on a clean box. `docker compose ps` should eventually report all
  services healthy. The web UI returns 502s during this window.

- **Port 9000 is opinionated** — if you change `SENTRY_BIND`, every existing
  DSN keeps working because the DSN host is decorative (see Networking
  above), but `nginx` itself listens on port 9000 internally regardless.

- **`SENTRY_VERSION` bumps may run irreversible migrations** — read the
  [release notes](https://github.com/getsentry/self-hosted/releases) before
  bumping the pin. Some versions auto-migrate Postgres / Clickhouse on first
  boot; rollback requires restoring from a backup of those volumes.

- **The `.env` append is one-way** — `setup.sh` appends our `.env` onto
  upstream's `.env`. Running `setup.sh` twice doesn't double-append (we use
  a marker comment), but editing upstream's `.env` directly inside
  `.self-hosted/` will be overwritten next time `setup.sh` runs. Always edit
  our `.env` at the wrapper root.

## Where the upstream lives

If you need to read upstream code:

- Repo: <https://github.com/getsentry/self-hosted>
- Pinned tag: `25.8.0`
- Releases: <https://github.com/getsentry/self-hosted/releases>
- Migration notes per release: each tag's release-notes section

If you need to read Sentry product code (Django app, ingestion, Snuba):

- Sentry: <https://github.com/getsentry/sentry>
- Snuba: <https://github.com/getsentry/snuba>
- Relay: <https://github.com/getsentry/relay>
- Vroom (profiling): <https://github.com/getsentry/vroom>
