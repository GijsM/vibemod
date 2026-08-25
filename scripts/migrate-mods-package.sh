#!/usr/bin/env bash
# One-time migration for upgrading an existing VibeMod install to >= 1.0.0.
#
# VibeMod 1.0.0 renamed its Java package from com.gijsm.vibemine to
# com.gijsm.vibemod. Generated mods on disk import the plugin API by its old
# package name and will no longer compile against the new jar. Run this script
# ONCE, with the server stopped, BEFORE restarting the server with the new
# VibeMod.jar: it rewrites every .java source under the plugin's mods/ and
# exports/ folders in place, replacing com.gijsm.vibemine with
# com.gijsm.vibemod. It is safe to re-run (already-migrated files are skipped).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OLD_PKG='com.gijsm.vibemine'
NEW_PKG='com.gijsm.vibemod'

changed=0
scanned=0

for dir in "$ROOT/server/plugins/VibeMod/mods" "$ROOT/server/plugins/VibeMod/exports"; do
  [[ -d "$dir" ]] || continue
  while IFS= read -r -d '' file; do
    scanned=$((scanned + 1))
    if grep -q "$OLD_PKG" "$file"; then
      # perl -pi is in-place on both macOS/BSD and GNU (sed -i differs between them)
      perl -pi -e "s/\\Q$OLD_PKG\\E/$NEW_PKG/g" "$file"
      changed=$((changed + 1))
      echo "migrated: $file"
    fi
  done < <(find "$dir" -type f -name '*.java' -print0)
done

echo "==> Scanned $scanned .java file(s); migrated $changed from $OLD_PKG to $NEW_PKG."
