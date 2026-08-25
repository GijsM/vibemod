#!/usr/bin/env bash
#
# PAPER-ONLY DEV HELPER. This script, start.sh, stop.sh, build.sh, deploy.sh,
# rcon.sh and logtail.sh exist to run ONE local Paper server under server/ for
# hand-testing. They are not the acceptance gates and they know nothing about
# Fabric or NeoForge - for those, and for a real headless gate on any platform,
# use scripts/smoke-{paper,fabric,neoforge}.sh (see README, "Development").
#
# One-time (idempotent) local dev setup for VibeMod:
#   - downloads the latest Paper $PAPER_VERSION server jar (verified by sha256)
#   - accepts the Mojang EULA
#   - generates/reuses an RCON password and writes server/server.properties
#   - installs the rcon-client npm dependency used by scripts/rcon.sh
#
# Usage: scripts/setup.sh            # Paper 1.21.8, the dialog baseline
#        PAPER_VERSION=1.20.6 scripts/setup.sh   # the supported floor
#        PAPER_VERSION=26.2   scripts/setup.sh   # the newest line
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
SCRIPTS_DIR="$ROOT/scripts"

# 1.21.8 by default: it is the dialog baseline VibeMod was built on, and the
# version gradle.properties calls paperRunVersionModern.
PAPER_VERSION="${PAPER_VERSION:-1.21.8}"
FILL_API_URL="https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/latest"

mkdir -p "$SERVER_DIR"

echo "==> VibeMod dev environment setup"
echo "    root:  $ROOT"
echo "    paper: $PAPER_VERSION"

# --- 1. Paper server jar ---------------------------------------------------
# The build number is whatever Fill reports as latest, not a hand-maintained
# pin: the old script named the file after a pinned build it never actually
# asked for, so the name on disk and the bytes in it could disagree.
echo "==> Fetching build metadata from Fill v3 API..."
BUILD_JSON="$(curl -fsSL -H 'User-Agent: vibemod-setup/1.0 (local dev)' "$FILL_API_URL")"

PAPER_BUILD="$(node -e '
  const data = JSON.parse(require("fs").readFileSync(0, "utf8"));
  if (!data.id) { process.exit(1); }
  process.stdout.write(String(data.id));
' <<<"$BUILD_JSON")"

PAPER_JAR_NAME="paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"
PAPER_JAR_PATH="$SERVER_DIR/$PAPER_JAR_NAME"

if [[ -f "$PAPER_JAR_PATH" ]]; then
  echo "==> Paper jar already present: $PAPER_JAR_PATH (skipping download)"
else
  DOWNLOAD_URL="$(node -e '
    const data = JSON.parse(require("fs").readFileSync(0, "utf8"));
    const dl = data.downloads && data.downloads["server:default"];
    if (!dl || !dl.url) { process.exit(1); }
    process.stdout.write(dl.url);
  ' <<<"$BUILD_JSON")"

  EXPECTED_SHA256="$(node -e '
    const data = JSON.parse(require("fs").readFileSync(0, "utf8"));
    const dl = data.downloads && data.downloads["server:default"];
    if (!dl || !dl.checksums || !dl.checksums.sha256) { process.exit(1); }
    process.stdout.write(dl.checksums.sha256);
  ' <<<"$BUILD_JSON")"

  if [[ -z "$DOWNLOAD_URL" || -z "$EXPECTED_SHA256" ]]; then
    echo "!! Could not parse download URL / sha256 from Fill API response:" >&2
    echo "$BUILD_JSON" >&2
    exit 1
  fi

  echo "==> Downloading $PAPER_JAR_NAME from $DOWNLOAD_URL"
  TMP_JAR="$(mktemp "$SERVER_DIR/.${PAPER_JAR_NAME}.XXXXXX")"
  curl -fSL -H 'User-Agent: vibemod-setup/1.0 (local dev)' -o "$TMP_JAR" "$DOWNLOAD_URL"

  echo "==> Verifying sha256..."
  ACTUAL_SHA256="$(shasum -a 256 "$TMP_JAR" | awk '{print $1}')"
  if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "!! sha256 mismatch for $PAPER_JAR_NAME" >&2
    echo "   expected: $EXPECTED_SHA256" >&2
    echo "   actual:   $ACTUAL_SHA256" >&2
    rm -f "$TMP_JAR"
    exit 1
  fi
  echo "==> sha256 verified: $ACTUAL_SHA256"

  mv "$TMP_JAR" "$PAPER_JAR_PATH"
  echo "==> Saved $PAPER_JAR_PATH"
fi

# --- 2. EULA ----------------------------------------------------------------
echo "eula=true" > "$SERVER_DIR/eula.txt"
echo "==> Wrote $SERVER_DIR/eula.txt"

# --- 3. RCON password ---------------------------------------------------
RCON_PASSWORD_FILE="$SCRIPTS_DIR/.rcon-password"
if [[ -f "$RCON_PASSWORD_FILE" ]]; then
  RCON_PASSWORD="$(cat "$RCON_PASSWORD_FILE")"
  echo "==> Reusing existing RCON password from $RCON_PASSWORD_FILE"
else
  RCON_PASSWORD="$(openssl rand -hex 12)"
  printf '%s' "$RCON_PASSWORD" > "$RCON_PASSWORD_FILE"
  chmod 600 "$RCON_PASSWORD_FILE"
  echo "==> Generated new RCON password -> $RCON_PASSWORD_FILE"
fi

# --- 4. server.properties -----------------------------------------------
# difficulty=normal + spawn-monsters=true (not peaceful) so summoned mobs
# don't instantly despawn -- peaceful mode deletes hostile mobs like creepers
# on the next tick, which would make most vibe-coded mods look broken.
cat > "$SERVER_DIR/server.properties" <<EOF
online-mode=false
level-type=minecraft\:normal
spawn-protection=0
view-distance=4
simulation-distance=4
difficulty=normal
gamemode=survival
enable-rcon=true
rcon.port=25575
rcon.password=${RCON_PASSWORD}
enable-command-block=true
max-players=5
allow-nether=false
sync-chunk-writes=false
motd=vibemod
server-ip=127.0.0.1
spawn-monsters=true
EOF
echo "==> Wrote $SERVER_DIR/server.properties"

# --- 5. rcon-client npm dependency --------------------------------------
if [[ -d "$SCRIPTS_DIR/node_modules/rcon-client" ]]; then
  echo "==> rcon-client already installed (skipping npm install)"
else
  echo "==> Installing rcon-client in $SCRIPTS_DIR"
  (
    cd "$SCRIPTS_DIR"
    npm init -y >/dev/null 2>&1 || true
    npm install rcon-client@^4 --no-fund --no-audit
  )
fi

echo "==> Setup complete."
