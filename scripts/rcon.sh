#!/usr/bin/env bash
# Usage: rcon.sh "<command>"
# Sends one command to the local dev server over RCON and prints the response
# (with Minecraft color codes stripped).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS_DIR="$ROOT/scripts"
PASSWORD_FILE="$SCRIPTS_DIR/.rcon-password"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 \"<command>\"" >&2
  exit 1
fi

if [[ ! -f "$PASSWORD_FILE" ]]; then
  echo "!! $PASSWORD_FILE not found. Run scripts/setup.sh first." >&2
  exit 1
fi

RCON_PASSWORD="$(cat "$PASSWORD_FILE")"
RCON_COMMAND="$1"

cd "$SCRIPTS_DIR"
RCON_PASSWORD="$RCON_PASSWORD" RCON_COMMAND="$RCON_COMMAND" node -e '
const { Rcon } = require("rcon-client");

const password = process.env.RCON_PASSWORD;
const command = process.env.RCON_COMMAND;

(async () => {
  let rcon;
  try {
    rcon = await Rcon.connect({ host: "127.0.0.1", port: 25575, password });
  } catch (err) {
    console.error("!! RCON connection failed: " + (err && err.message ? err.message : err));
    process.exit(1);
  }

  try {
    const response = await rcon.send(command);
    console.log(response.replace(/§./g, ""));
  } catch (err) {
    console.error("!! RCON command failed: " + (err && err.message ? err.message : err));
    process.exitCode = 1;
  } finally {
    await rcon.end();
  }
})();
'
