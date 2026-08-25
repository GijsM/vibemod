#!/usr/bin/env bash
#
# PAPER-ONLY DEV HELPER (see the header of scripts/setup.sh).
#
# Usage: logtail.sh [n]  -- tails the last n (default 40) lines of the server log.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tail -n "${1:-40}" "$ROOT/server/logs/latest.log"
