# Operations runbook

Day-2 procedures for running Sentry self-hosted: lifecycle, health, backups,
restore, upgrades, capacity tuning, and incident response.

> First-time install is in [`deployment.md`](./deployment.md). This document
> is for an existing, working stack.

## Lifecycle

All commands assume CWD = `sentry-selfhost/.self-hosted/`.

### Start

```bash
docker compose up -d
```

Wait 2–3 minutes for everything to settle. Then:

```bash
docker compose ps
```

All services should show `running (healthy)` or `running`. A few minutes of
`(health: starting)` on `web`, `relay`, `snuba-api` is normal during boot.

### Stop (keep data)

```bash
docker compose down
```

Volumes survive. Restart with `docker compose up -d` and you're back where you
were.

### Stop (wipe data)

```bash
docker compose down -v
```

`-v` removes named volumes. After this, **every project's DSN is invalidated**
because the new `SENTRY_SECRET_KEY` will differ. See [full reset](#full-reset).

### Tail logs

```bash
docker compose logs -f web              # one service
docker compose logs -f --tail=100       # all services, last 100 lines each
docker compose logs -f web worker       # multiple services
```

### Restart one service

```bash
docker compose restart web
docker compose restart worker cron     # multiple
```

Useful after editing `sentry/config.yml` (touches `web` + `worker`).

### Run an ad-hoc command

```bash
docker compose run --rm web sentry --help
docker compose run --rm web sentry shell                  # Django shell
docker compose run --rm web sentry createuser             # add a superuser
docker compose run --rm web sentry permissions list       # list permission groups
docker compose run --rm web sentry cleanup --days 30      # manual retention pruning
```

### Open a shell in a running container

```bash
docker compose exec web bash
docker compose exec postgres psql -U postgres -d postgres
docker compose exec clickhouse clickhouse-client
docker compose exec redis redis-cli
```

## Health checks

### Top-level health

```bash
curl http://localhost:9000/_health/
# → "ok"
```

If this returns 502: `web` not ready. Check `docker compose ps`.

### Per-service health

```bash
docker compose ps
```

Watch for:

- `running (healthy)` — good
- `running (health: starting)` — wait
- `running (unhealthy)` — bad; check logs
- `exited (1)` — crashed; check logs

### Spot-check ingestion

```bash
# Tail relay (request ingestion) while you trigger an event from the app
docker compose logs -f relay
```

You should see `"POST /api/<project_id>/envelope/..."` lines whenever the
Android app sends an event.

### Spot-check Kafka

```bash
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
docker compose exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list
```

### Spot-check Clickhouse

```bash
docker compose exec clickhouse clickhouse-client -q "SELECT count() FROM default.errors_local"
docker compose exec clickhouse clickhouse-client -q "SELECT count() FROM default.transactions_local"
```

### Spot-check Postgres

```bash
docker compose exec postgres psql -U postgres -d postgres \
  -c "SELECT id, name FROM sentry_project ORDER BY id;"
```

## Backups

### What to back up

| Path | What it is | Priority |
| --- | --- | --- |
| `sentry-postgres` volume | Issues, projects, users, alert rules | **critical** |
| `sentry-clickhouse` volume | Events, transactions, profiles | **critical** |
| `sentry-data` volume | File uploads (attachments, debug symbols) | high |
| `sentry-selfhost/.self-hosted/.env` | `SENTRY_SECRET_KEY` + other secrets | **critical** |
| `sentry-selfhost/.self-hosted/sentry/config.yml` | Sentry app config | medium |
| `sentry-selfhost/.self-hosted/relay/credentials.json` | Relay's keypair | high |

**Losing `.env` or `credentials.json` invalidates all DSNs.**

### Backup recipe (cold)

Stop the stack first to ensure consistency:

```bash
cd sentry-selfhost/.self-hosted
docker compose down

# Dump volumes to tarballs
docker run --rm \
  -v sentry-self-hosted_sentry-postgres:/source:ro \
  -v "$(pwd)/backups":/backup \
  alpine tar czf /backup/postgres-$(date +%F).tar.gz -C /source .

docker run --rm \
  -v sentry-self-hosted_sentry-clickhouse:/source:ro \
  -v "$(pwd)/backups":/backup \
  alpine tar czf /backup/clickhouse-$(date +%F).tar.gz -C /source .

docker run --rm \
  -v sentry-self-hosted_sentry-data:/source:ro \
  -v "$(pwd)/backups":/backup \
  alpine tar czf /backup/data-$(date +%F).tar.gz -C /source .

# Bundle config + secrets
tar czf backups/config-$(date +%F).tar.gz .env sentry/config.yml relay/credentials.json

docker compose up -d
```

Volume names are prefixed with the compose project name (`sentry-self-hosted_…`).
Verify on your host: `docker volume ls | grep sentry`.

### Backup recipe (hot — Postgres)

For minimal-downtime backups, use Postgres' own dump:

```bash
docker compose exec postgres pg_dump -U postgres -Fc -d postgres \
  > backups/postgres-$(date +%F).pgdump
```

Clickhouse hot backups are more involved (`BACKUP` SQL statement to a disk
volume, then export). For demo purposes the cold-tarball approach is fine.

### Restore

```bash
cd sentry-selfhost/.self-hosted
docker compose down -v   # destroys current volumes

# Recreate empty volumes by running install.sh (interrupted)
cd .. && ./setup.sh
# Press Ctrl-C after volumes are created but before install seeds them, or
# accept the empty install and overwrite below.

cd .self-hosted
docker compose down

# Restore tarballs into the volumes
docker run --rm \
  -v sentry-self-hosted_sentry-postgres:/target \
  -v "$(pwd)/backups":/backup \
  alpine sh -c "rm -rf /target/* && tar xzf /backup/postgres-2026-05-27.tar.gz -C /target"
# Repeat for clickhouse and data

# Restore config + secrets
tar xzf backups/config-2026-05-27.tar.gz

docker compose up -d
```

Verify by logging in with the original superuser and checking that existing
events show up.

## Upgrades

### Patch / minor upgrades

```bash
cd sentry-selfhost
SENTRY_VERSION=25.9.0 ./setup.sh
cd .self-hosted
docker compose down
docker compose up -d
```

`install.sh` runs migrations on the next boot. Patch upgrades within a major
are typically safe.

### Major upgrades

**Read the release notes first.** Some major versions run irreversible
migrations on first boot — rollback requires restoring from backup. The
release notes call this out explicitly when it applies.

Recommended procedure:

1. Back up everything (see above).
2. Bump `SENTRY_VERSION` in `setup.sh`.
3. Re-run `setup.sh`.
4. `docker compose down && docker compose up -d`.
5. Monitor `docker compose logs -f web worker` for migration progress.
6. Smoke-test by tapping a button in the Android app and watching the event
   land in the UI.
7. If anything goes wrong, restore from backup and pin to the previous version.

## Capacity tuning

### Disk

The two biggest consumers:

- **Clickhouse** — events, transactions, profiles. Grows roughly linearly
  with `events/day × retention_days`. For a small project (1k events/day,
  30-day retention): ~500 MB. For 100k events/day: ~50 GB.
- **Postgres** — issues table grows with unique issue count, not event volume.
  Usually <5 GB even at scale.

Lower disk pressure by reducing `SENTRY_EVENT_RETENTION_DAYS` in `.env`,
restarting the stack, and waiting for the next nightly `cron` run (or
forcing immediate cleanup with
`docker compose run --rm web sentry cleanup --days 7`).

### CPU

- **Boot is CPU-heavy** — Clickhouse compaction and Kafka topic creation take
  ~5 cores for the first 60 seconds. After settling, idle baseline is ~0.5
  cores total.
- **Per-event cost** — Relay does normalization in Rust (cheap). Snuba
  consumers do the bulk of work (Kafka → Clickhouse). For >10k events/sec,
  you'll need to scale Snuba consumers — out of scope for this demo.

### RAM

The defaults assume **16 GB total** allocated to Docker. Tight, but works.
Reduce by switching `COMPOSE_PROFILES=errors-only` in `.env` — drops Snuba,
Clickhouse, Kafka, replays, profiles. Floor becomes ~4 GB.

### Log rotation

Our override caps Docker JSON logs at **10 MB × 3 files** per chatty service
(see [`sentry-selfhost.md`](./sentry-selfhost.md#docker-compose-overrideyml)).
If you add a new service, copy the `logging: *log-rotation` anchor onto it.

## Secret rotation

### Rotating `SENTRY_SECRET_KEY`

**Don't, unless you understand the impact.** Rotation invalidates every
project's DSN and forces all users to log in again.

If you must:

```bash
cd sentry-selfhost/.self-hosted
# Generate a new key
NEW_KEY=$(python3 -c "import secrets; print(secrets.token_urlsafe(50))")

# Replace in .env (NOT in our wrapper's .env — in the upstream .env that
# install.sh manages)
sed -i.bak "s|^SENTRY_SECRET_KEY=.*|SENTRY_SECRET_KEY=$NEW_KEY|" .env

# Restart
docker compose restart web worker cron
```

Then recreate every project's DSN in the UI and update all SDK clients.

### Rotating Sentry user passwords

```bash
docker compose run --rm web sentry --help | grep -i password
# Use the `sentry users password` command, or do it from the web UI
```

## Common incidents

### Disk full

Symptoms: `web` returns 500s, Clickhouse logs full disk errors.

```bash
# Confirm
df -h /var/lib/docker

# Reduce retention temporarily
docker compose run --rm web sentry cleanup --days 7

# Or expand the Docker data root, then bump retention back in .env
```

### Port 9000 already in use

```bash
lsof -i :9000           # find the culprit
# Either kill it, or change SENTRY_BIND in .env and restart:
docker compose down
docker compose up -d
```

### `web` stuck restarting

```bash
docker compose logs --tail=200 web
```

Most common: Postgres or Clickhouse not healthy yet. Wait 2 more minutes.

If `web` is genuinely stuck:

```bash
docker compose run --rm web sentry upgrade   # re-runs migrations
docker compose restart web
```

### Events arriving but not showing in UI

1. Verify Relay accepted them:
   ```bash
   docker compose logs --tail=50 relay | grep -E "envelope|dropped"
   ```
2. Verify they reached Kafka:
   ```bash
   docker compose exec kafka kafka-consumer-groups \
     --bootstrap-server localhost:9092 --describe \
     --group snuba-consumers
   # Lag should be small (<10)
   ```
3. Verify they reached Clickhouse:
   ```bash
   docker compose exec clickhouse clickhouse-client -q \
     "SELECT count() FROM default.errors_local WHERE timestamp > now() - INTERVAL 1 HOUR"
   ```
4. If everything looks healthy but the UI says zero, check the project filter
   (top-left dropdown in the UI) and the time range.

### One snuba consumer keeps crashing

```bash
docker compose logs --tail=200 snuba-errors-consumer  # or whichever
```

Common cause: corrupt offset. Reset (loses events still in the topic):

```bash
docker compose stop snuba-errors-consumer
docker compose exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group snuba-errors-consumers \
  --reset-offsets --to-latest --topic events --execute
docker compose start snuba-errors-consumer
```

## Full reset

When you want to nuke everything and start over:

```bash
cd sentry-selfhost/.self-hosted
docker compose down -v
cd ..
rm -rf .self-hosted
./setup.sh
```

After this:

- Every DSN is invalid (new `SENTRY_SECRET_KEY`).
- Every Sentry user is gone.
- All historical events are gone.
- You'll need to recreate the superuser, the org, every project, and update
  all SDK clients with the new DSNs.

### Partial reset — wipe events but keep projects

```bash
docker compose stop
docker compose rm -f -v snuba-api kafka clickhouse zookeeper
docker compose up -d
# After boot, force cleanup of any straggler rows:
docker compose run --rm web sentry cleanup --days 0
```

Postgres (issues, projects, users) is preserved.

## Monitoring the monitor

Recommended external checks for a production Sentry:

| Check | Tool | Frequency |
| --- | --- | --- |
| `GET /_health/` returns 200 | Uptime monitor (UptimeRobot, etc.) | every 1 min |
| Disk usage of the Docker data root | Prometheus node_exporter | every 30 s |
| Postgres replication lag (if HA) | Postgres exporter | every 1 min |
| Clickhouse query latency p95 | Clickhouse exporter | every 1 min |
| Kafka consumer lag (snuba groups) | Kafka exporter | every 30 s |

A meta-Sentry (separate instance, points at this one via DSN) catches the case
where Sentry itself is the source of an outage.

## Ad-hoc admin tasks

### Add a new superuser

```bash
docker compose run --rm web sentry createuser --superuser
```

### Reset a user's password

```bash
docker compose run --rm web sentry users update --email user@example.com --password newpass
```

### Delete an org's data

```bash
docker compose run --rm web sentry exec
>>> from sentry.models import Organization
>>> Organization.objects.get(slug='example').delete()
```

### Force retention cleanup now

```bash
docker compose run --rm web sentry cleanup --days 30
```

### Re-issue Relay credentials

```bash
docker compose stop relay
rm -f relay/credentials.json
docker compose up -d relay
```

Note: this disconnects external relays. For self-contained installs (the
demo), it's harmless on restart.

## Where to look next

- Upstream operator docs: <https://develop.sentry.dev/self-hosted/>
- Upstream issues: <https://github.com/getsentry/self-hosted/issues>
- Sentry SDK release notes (Android): <https://github.com/getsentry/sentry-java/releases>
- Architecture reference: [`architecture.md`](./architecture.md)
- Service topology reference: [`sentry-selfhost.md`](./sentry-selfhost.md)
