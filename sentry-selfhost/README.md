# sentry-selfhost

Wrapper around the official [`getsentry/self-hosted`](https://github.com/getsentry/self-hosted) docker-compose installer. We pin a known-good release tag, clone it into `./.self-hosted/`, layer our overrides on top, and run `install.sh`.

## Requirements

- Docker Engine **19.03.6+**
- Docker Compose **2.32.2+**
- **4** CPU cores, **16 GB RAM + 16 GB swap**, **20 GB** free disk

Verify:

```bash
docker --version
docker compose version
```

## Setup

```bash
./setup.sh
```

The script:
1. Clones `getsentry/self-hosted` at the pinned tag into `./.self-hosted/` (if missing).
2. Copies `docker-compose.override.yml` and `.env` into that directory.
3. Runs `./install.sh --skip-user-creation` (re-runnable; idempotent).
4. Prompts you to create the first superuser interactively via `docker compose run --rm web createuser`.

## Start / Stop

```bash
# Start
cd .self-hosted && docker compose up -d

# Tail logs
cd .self-hosted && docker compose logs -f web

# Stop
cd .self-hosted && docker compose down

# Wipe everything (volumes too)
cd .self-hosted && docker compose down -v
```

## Access

- Web UI: <http://localhost:9000>
- Health: <http://localhost:9000/_health/>

On first login, create an **organization** and a **project** with platform **Android**. Sentry will show a DSN like `http://<key>@localhost:9000/<project_id>` — copy this into `android-app/local.properties`.

## Networking for emulator / device

The Android emulator can't reach `localhost` on the host directly. Use:

- **Emulator (AVD):** `http://10.0.2.2:9000/...` — set DSN host to `10.0.2.2`.
- **Physical device on same Wi-Fi:** use your machine's LAN IP, e.g. `http://192.168.x.x:9000/...`. The provided `network_security_config.xml` already allows cleartext to `10.0.2.2` and `192.168.0.0/16`.

> Sentry stores the DSN host it generates. To rewrite host without recreating the project, just edit the DSN string in `android-app/local.properties` — Sentry validates by `project_id`/`public_key`, the host is purely for routing.

## Files

| File | Purpose |
| --- | --- |
| `setup.sh` | Clone upstream + run installer |
| `docker-compose.override.yml` | Local customizations (port mapping, log driver) |
| `.env.example` | Sample env, copy to `.env` and edit |
| `.self-hosted/` | Cloned upstream — git-ignored |

## Caveats

- **`.env` merge** — `setup.sh` appends our `.env` onto the upstream `.env` that `install.sh` manages. Keys we define here override upstream defaults (last-write-wins in dotenv parsing), so only set keys you actually want to customize. Don't redeclare keys `install.sh` auto-generates (`SENTRY_SECRET_KEY` in particular — see below).

- **`SENTRY_SECRET_KEY` stability** — `install.sh` generates this on first run and stores it in `.self-hosted/.env`. **Don't delete `.self-hosted/.env`** between re-runs: a fresh `SENTRY_SECRET_KEY` invalidates every existing project's DSN and forces session re-issue. To wipe Sentry cleanly, run `docker compose down -v && rm -rf .self-hosted` together (don't keep the volumes around with a new secret).

- **ARM64 / Apple Silicon** — all upstream images publish multi-arch (incl. `linux/arm64`) so M-series Macs work without `--platform` hacks. Verify Docker Desktop has Rosetta 2 emulation enabled if you ever need to pin a legacy x86 service.

- **Bumping `SENTRY_VERSION`** — track [releases](https://github.com/getsentry/self-hosted/releases). Read the migration notes for the target tag — some major bumps run irreversible db migrations on first boot.

## Troubleshooting

- **`install.sh` fails on memory check** — bump Docker Desktop memory to 16 GB+ in Settings → Resources.
- **Port 9000 already in use** — `lsof -i :9000` to find the offender, or change `SENTRY_BIND` in `.env` and re-run `install.sh`.
- **Slow first boot** — `clickhouse`, `kafka`, `snuba`, `relay` need ~2-3 minutes to settle. `docker compose ps` should eventually show all healthy.
- **Logs eating disk** — covered by `docker-compose.override.yml` (10 MB × 3 rotation on every chatty service). If you add new services, mirror the same `logging: *log-rotation` anchor.
- **Reset everything** — `cd .self-hosted && docker compose down -v && cd .. && rm -rf .self-hosted && ./setup.sh`.
