# Deployment guide

End-to-end install of the SentryDemo stack on a fresh host, plus the Android
client. Aimed at infra / DevOps engineers standing this up the first time.

> **TL;DR**: `cd sentry-selfhost && ./setup.sh && docker compose --project-directory .self-hosted up -d`,
> create a superuser, copy the DSN, drop it into `android-app/local.properties`,
> `./gradlew :app:installDebug`.

## Audience

This guide assumes:

- You have shell access to the host that will run Sentry.
- You're comfortable with Docker and `docker compose` v2.
- The host meets the resource floor in [Prerequisites](#prerequisites).
- You can install Android Studio + JDK 17 + the Android SDK on a developer
  machine (for the client). Most teams already have this.

## Prerequisites

### Sentry backend host

| Resource | Minimum (`feature-complete`) | Minimum (`errors-only`) | Recommended |
| --- | --- | --- | --- |
| CPU cores | 4 | 2 | 8 |
| RAM | 16 GB | 4 GB | 32 GB |
| Swap | 16 GB | 4 GB | 16 GB |
| Disk | 20 GB free | 10 GB | 100 GB SSD |
| OS | Linux/macOS with Docker | same | Ubuntu 22.04 LTS or newer |
| Docker Engine | 19.03.6+ | same | 25.x |
| Docker Compose | 2.32.2+ | same | latest 2.x |
| Git | any modern version | same | 2.40+ |

The first-boot installer **enforces** these minimums; it bails out with a
helpful error message if RAM is short.

Verify on the target host:

```bash
docker --version          # → Docker version 25.x.x …
docker compose version    # → Docker Compose version v2.32.x or higher
git --version             # → git version 2.x.x
free -h                   # → at least 16 GB total + 16 GB swap
df -h /                   # → at least 20 GB free
nproc                     # → at least 4
```

If you run Docker Desktop, raise its memory allocation to 16 GB+ in
**Settings → Resources** before starting.

### Android developer machine

| Tool | Version |
| --- | --- |
| JDK | 17 (project compiles with `sourceCompatibility = VERSION_17`) |
| Android SDK | platform 35 + build-tools 35.0.0 |
| Android Studio | Giraffe (2023.3.x) or newer — Iguana/Koala recommended for Compose 1.7 |
| Emulator (optional) | Pixel API 35 system image, or any API 26+ device |

## Step 1 — Clone the repo

```bash
git clone https://github.com/Yugi91/SentryDemo.git
cd SentryDemo
```

Layout:

```
SentryDemo/
├── android-app/        # Mobile client
├── sentry-selfhost/    # Backend wrapper
├── docs/               # This documentation
└── README.md
```

## Step 2 — Bring up Sentry self-hosted

### 2.1 Customize `.env`

```bash
cd sentry-selfhost
cp .env.example .env
$EDITOR .env
```

Knobs you'll most likely touch:

| Key | Default | Notes |
| --- | --- | --- |
| `SENTRY_BIND` | `9000` | Host port. Use `127.0.0.1:9000` to bind loopback only on a public host. |
| `SENTRY_EVENT_RETENTION_DAYS` | `30` | Events older than this are pruned by the `cron` service. |
| `SENTRY_EMAIL_*` | unset | SMTP for notifications. Leave unset for the demo; the bundled `smtp` service catches everything. |
| `SENTRY_SECRET_KEY` | unset (auto-generated) | **Do not set manually** — `install.sh` generates and stabilizes it. |

The full reference for every supported key is in
[`configuration.md`](./configuration.md).

### 2.2 Run the installer

```bash
./setup.sh
```

What happens:

1. Clones `getsentry/self-hosted@25.8.0` into `./.self-hosted/`.
2. Copies `docker-compose.override.yml` and `.env` into the clone.
3. Appends our `.env` onto upstream's `.env`.
4. Runs `.self-hosted/install.sh --skip-user-creation`, which:
   - Pulls all images (~5 GB total).
   - Generates `SENTRY_SECRET_KEY` and writes it to `.self-hosted/.env`.
   - Builds `sentry-self-hosted-local` (image with custom plugins).
   - Initializes Postgres schema and Clickhouse tables.
   - Runs Kafka topic creation.
   - Warms relay credentials.

Expect this to take **10–20 minutes** on a clean box (mostly pulling images
and initializing Clickhouse). Subsequent runs are 1–2 minutes.

### 2.3 Create the first superuser

```bash
cd .self-hosted
docker compose run --rm web createuser
```

Prompts for email, password, and superuser flag. Pick a real email — the
default org name comes from the domain.

### 2.4 Start the stack

```bash
docker compose up -d
```

Wait 2–3 minutes for everything to settle:

```bash
docker compose ps
# every service should report "running (healthy)" or "running"
```

Smoke-test the health endpoint:

```bash
curl http://localhost:9000/_health/
# → "ok"
```

### 2.5 Create a project in the UI

1. Open <http://localhost:9000> and log in with the superuser.
2. Finish the first-run wizard (org name, etc.).
3. **Settings → Projects → Create Project**:
   - Platform: **Android**
   - Project name: anything (e.g. `sentry-demo-android`)
4. Copy the DSN from the next screen. It will look like:

   ```
   http://abc123def456@localhost:9000/2
   ```

## Step 3 — Configure the Android app

```bash
cd ../../android-app
cp local.properties.example local.properties
$EDITOR local.properties
```

Fill in three values:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
sentry.dsn=http://abc123def456@10.0.2.2:9000/2
sentry.environment=debug
sentry.userId=demo-user-001
```

**The host part matters:**

| Where you run the app | DSN host |
| --- | --- |
| Android emulator (AVD) on the same machine as Sentry | `10.0.2.2` |
| Physical device on the same Wi-Fi as the Sentry host | LAN IP, e.g. `192.168.1.20` |
| App on a developer laptop pointing at a remote Sentry | Public hostname or IP |

The Sentry server only validates `<public_key>` and `<project_id>` — the host
is purely for HTTP routing.

> **Production:** terminate Sentry behind HTTPS and remove the cleartext
> exceptions in `app/src/main/res/xml/network_security_config.xml`. The
> reverse-proxy / load-balancer setup is out of scope for this demo.

## Step 4 — Build & install

### From the command line

```bash
./gradlew :app:installDebug
# launches the build, installs onto the running emulator or connected device
```

First build downloads dependencies (~5 minutes on a clean machine, ~30 s on
warm Gradle).

### From Android Studio

1. Open `android-app/` in Android Studio Giraffe+.
2. Studio prompts to sync Gradle — accept.
3. Pick a run target (emulator or physical device).
4. Hit Run (Shift+F10).

## Step 5 — Smoke test

After install, the app shows five buttons:

| Button | What you should see in Sentry |
| --- | --- |
| **Delay action (2 s)** | Performance → Transactions → `delay_action` with a `processing` span |
| **Crash action** | Issues → `RuntimeException: Demo crash triggered from UI` *(visible after relaunching the app)* |
| **ANR action (block 8 s)** | Issues → `ApplicationNotResponding` *(after relaunching)* |
| **Photo workflow (ok path)** | Performance → Transactions → `photo_workflow` with 3 child spans |
| **Photo workflow (force fail)** | Same transaction with `sync_image` marked failed + `Issues → PhotoSyncException` |

If events don't appear within 30 seconds of a button tap (Crash/ANR excepted):

1. Check the DSN — match `local.properties.sentry.dsn` against
   Sentry → Settings → Projects → `<project>` → Client Keys.
2. Check the network — `adb shell ping -c 3 10.0.2.2` (emulator) or your
   device's IP.
3. Check the SDK log — `adb logcat -s "Sentry"` should show
   `[Sentry] [INFO] Initialization finished.`
4. Check the backend — `docker compose logs -f relay` should show incoming
   envelope POSTs.

## Post-install hardening (production)

The demo defaults are deliberately permissive. For a production deployment,
review these:

### 1. TLS everywhere

- Put Sentry behind a TLS-terminating reverse proxy (Caddy, nginx, Traefik).
- Set `system.url-prefix` in `.self-hosted/sentry/config.yml` to the public
  HTTPS URL.
- Remove the cleartext exceptions from `network_security_config.xml`.

### 2. Bind to loopback only on public hosts

In `.env`:

```bash
SENTRY_BIND=127.0.0.1:9000
```

So Sentry only listens on the local interface; only your reverse proxy can
reach it.

### 3. Real SMTP

In `.env`:

```bash
SENTRY_EMAIL_HOST=smtp.sendgrid.net
SENTRY_EMAIL_PORT=587
SENTRY_EMAIL_USER=apikey
SENTRY_EMAIL_PASSWORD=<your sendgrid key>
SENTRY_EMAIL_USE_TLS=true
```

### 4. Backup strategy

The four volumes you must back up:

- `sentry-postgres` — issues, projects, users, configuration
- `sentry-clickhouse` — events, transactions, profiles (the bulk of disk)
- `sentry-data` — file uploads (attachments, debug symbols)
- `.self-hosted/.env` — contains `SENTRY_SECRET_KEY`. **Losing this rotates
  every DSN.**

See [`operations.md`](./operations.md#backups) for the recipe.

### 5. Retention tuning

`SENTRY_EVENT_RETENTION_DAYS=30` is the default. Bump up for compliance
requirements, bump down for tight storage. The `cron` service prunes nightly.

### 6. Authentication

Out of the box, anyone with a superuser account can do anything. Real-world
setups should integrate SSO (SAML or OAuth) via Sentry's settings — see the
[upstream auth docs](https://develop.sentry.dev/self-hosted/sso/).

### 7. Auth tokens for CI

For uploading ProGuard mappings from your Android CI:

1. Sentry → Settings → Account → API → Auth Tokens → **Create New Token**.
2. Scope: `project:releases`, optionally `org:read`.
3. Store as `SENTRY_AUTH_TOKEN` in your CI secrets.
4. Flip the Sentry Gradle plugin flags in `android-app/app/build.gradle.kts`:

   ```kotlin
   sentry {
       autoUploadProguardMapping.set(true)
       includeProguardMapping.set(true)
       // org.set("...")  ← required if not using SENTRY_ORG env
       // projectName.set("...")  ← required if not using SENTRY_PROJECT env
   }
   ```

## Upgrading Sentry self-hosted

```bash
cd sentry-selfhost
SENTRY_VERSION=26.5.0 ./setup.sh   # picks up the new tag, re-runs install.sh
docker compose down                 # stop the old stack
docker compose up -d                # start the new stack
```

`install.sh` runs migrations on the next boot. **Read the release notes** for
the target tag before bumping a major — some bumps run irreversible Postgres
or Clickhouse migrations that can't be rolled back without a volume restore.

## Upgrading the Android Sentry SDK

In `android-app/gradle/libs.versions.toml`:

```toml
sentry = "8.x.y"
sentryGradle = "5.x.y"
```

Then re-sync Gradle. Release notes:
<https://github.com/getsentry/sentry-java/releases>.

## Disaster recovery — full reset

```bash
cd sentry-selfhost/.self-hosted
docker compose down -v             # stops and removes volumes
cd ..
rm -rf .self-hosted
./setup.sh                          # fresh install
```

After this, **every existing DSN is invalid** because `SENTRY_SECRET_KEY` is
regenerated. You must recreate projects and update all clients with the new
DSNs.

See [`operations.md`](./operations.md#full-reset) for partial-reset options
(e.g., wipe events but keep projects).

## Common deployment failure modes

| Symptom | Likely cause | Resolution |
| --- | --- | --- |
| `install.sh` aborts: "Memory size detected (8.0 GB) is less than 16.0 GB" | Docker Desktop allocated < 16 GB | Settings → Resources → Memory → 16 GB+; restart Docker |
| `install.sh` aborts: "Docker is required" | Docker daemon not running | `systemctl start docker` or open Docker Desktop |
| Port 9000 already in use | Another service is bound | `lsof -i :9000`; either stop that service or set `SENTRY_BIND=9001` in `.env` |
| `web` keeps restarting | Postgres or Clickhouse not healthy yet | `docker compose ps`; wait for `(healthy)`; check `docker compose logs postgres` |
| Events POST returns 429 | Rate limited by Relay | Set per-project rate limit in Sentry UI (Project → Settings → Client Keys → Rate Limit) |
| Events POST returns 200 but nothing in UI | Sampling dropped them, or wrong project_id | Check `docker compose logs -f relay` for "dropped"; verify project id in DSN |
| Disk filling fast | Default retention not being applied | `docker compose exec cron sentry cleanup --days 30`; check `cron` is running |
| Android: cleartext blocked | Host IP not in `network_security_config.xml` | Add the IP/host and rebuild |
| Android: `InvalidDsnException` | Malformed DSN | Must be `<scheme>://<key>@<host>:<port>/<id>` |

For day-2 operations (backups, restarts, capacity tuning), continue to
[`operations.md`](./operations.md).
