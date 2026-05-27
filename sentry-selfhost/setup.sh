#!/usr/bin/env bash
# Bootstraps Sentry self-hosted into ./.self-hosted using the official installer.
# Re-runnable; idempotent.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Pin to a known-good upstream release. Bump as needed.
SENTRY_VERSION="${SENTRY_VERSION:-25.4.0}"
TARGET_DIR=".self-hosted"

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "✗ '$1' is required but not installed."; exit 1; }
}

echo "▶ Checking prerequisites..."
require git
require docker
docker compose version >/dev/null 2>&1 || { echo "✗ 'docker compose' (v2) is required."; exit 1; }

if [[ ! -d "$TARGET_DIR" ]]; then
  echo "▶ Cloning getsentry/self-hosted@${SENTRY_VERSION} → $TARGET_DIR"
  git clone --depth 1 --branch "${SENTRY_VERSION}" https://github.com/getsentry/self-hosted.git "$TARGET_DIR"
else
  echo "▶ $TARGET_DIR already exists, skipping clone (delete it to re-clone)"
fi

if [[ ! -f ".env" ]]; then
  echo "▶ Creating .env from .env.example"
  cp .env.example .env
fi

echo "▶ Layering overrides into $TARGET_DIR"
cp docker-compose.override.yml "$TARGET_DIR/docker-compose.override.yml"
cp .env "$TARGET_DIR/.env.custom"
# Append our custom env to upstream's .env (install.sh creates/updates it)
touch "$TARGET_DIR/.env"
{
  echo ""
  echo "# --- sentry-selfhost demo overrides ---"
  cat .env
} >> "$TARGET_DIR/.env"

echo "▶ Running upstream install.sh (skip user creation; do it manually after)"
(cd "$TARGET_DIR" && ./install.sh --skip-user-creation)

cat <<'EOF'

✅ Install complete.

Create your first superuser:
    cd .self-hosted && docker compose run --rm web createuser

Start Sentry:
    cd .self-hosted && docker compose up -d

Then open http://localhost:9000
EOF
