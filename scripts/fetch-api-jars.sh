#!/usr/bin/env bash
#
# Downloads one paper-api jar per supported Minecraft version into a gitignored
# cache, so the offline symbol tools (core/src/test/java/symbols/) can measure
# the REAL API vocabulary of every version VibeMod claims to support.
#
# Why this exists: docs/MASTER-PROMPT-reach-and-context.md Objective B3. The
# prompt's factual claims about which enum constants and which ItemMeta methods
# exist on which version were hand-written and never checked. A jar per version
# is what makes them checkable without booting twenty servers.
#
# Usage:
#   scripts/fetch-api-jars.sh              # every supported version
#   scripts/fetch-api-jars.sh 1.21.3 26.2  # just these
#   PAPER_API_DIR=/tmp/jars scripts/fetch-api-jars.sh
#
# Idempotent: an already-downloaded jar is skipped, so re-running is cheap.
#
# ---------------------------------------------------------------------------
# The two artifact layouts (verified against the live repo, not assumed)
#
# Paper's 1.x line publishes SNAPSHOTS:
#   version  1.21.8-R0.1-SNAPSHOT
#   jar      paper-api-1.21.8-R0.1-20250906.215025-55.jar
# so the real filename only exists in that version's own maven-metadata.xml and
# must be resolved before anything can be downloaded.
#
# Paper's 26.x line publishes RELEASES with the build number in the version:
#   version  26.2.build.119-stable
#   jar      paper-api-26.2.build.119-stable.jar
# One Minecraft version (26.2) therefore has hundreds of artifact versions, and
# we take the newest one at the best maturity (stable > beta > alpha).
# ---------------------------------------------------------------------------

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api"
OUT="${PAPER_API_DIR:-$ROOT/paper/api-jars}"

# The floor. Below this Paper refuses the plugin outright on `api-version`
# (MASTER-PROMPT "Ground truth"), so measuring older jars would describe
# servers VibeMod cannot run on.
FLOOR_MINOR=20

mkdir -p "$OUT"

META="$(mktemp)"
trap 'rm -f "$META"' EXIT
echo "==> fetching paper-api maven-metadata.xml"
curl -fsSL "$BASE/maven-metadata.xml" -o "$META"

ALL_VERSIONS="$(grep -o '<version>[^<]*</version>' "$META" | sed 's/<[^>]*>//g')"

# --- Which Minecraft versions exist at or above the floor? --------------------
#
# Deliberately DERIVED from the metadata rather than hard-coded, so a new Paper
# release is picked up by re-running this script. Pre-releases, release
# candidates and the one-off `1.21.5-no-moonrise` fork are excluded: they are
# not versions anyone runs a server on.
mc_versions() {
    # 1.x snapshots: 1.20-R0.1-SNAPSHOT -> 1.20
    echo "$ALL_VERSIONS" \
        | grep -E '^1\.[0-9]+(\.[0-9]+)?-R0\.1-SNAPSHOT$' \
        | sed 's/-R0\.1-SNAPSHOT$//' \
        | awk -F. -v floor="$FLOOR_MINOR" '$2 >= floor'
    # 26.x releases: 26.2.build.119-stable -> 26.2 ; 26.1.1.build.8-alpha -> 26.1.1
    # The `-rc-` line (26.2-rc-2.build.N) is a release candidate, not a release.
    echo "$ALL_VERSIONS" \
        | grep -E '^[0-9]{2}\.[0-9]+(\.[0-9]+)?\.build\.[0-9]+-(alpha|beta|stable)$' \
        | sed -E 's/\.build\.[0-9]+-(alpha|beta|stable)$//' \
        | sort -u
}

# --- Resolve one Minecraft version to a concrete jar URL ----------------------
# Prints "<artifact-version> <jar-url>".
resolve() {
    local mc="$1"

    if [[ "$mc" == 1.* ]]; then
        local av="$mc-R0.1-SNAPSHOT"
        local snap
        snap="$(curl -fsSL "$BASE/$av/maven-metadata.xml" 2>/dev/null || true)"
        if [[ -z "$snap" ]]; then
            return 1
        fi
        # The <snapshotVersion> with NO <classifier> and extension `jar` is the
        # real jar; the sources and javadoc entries carry the same <value>, so
        # grabbing the first <value> would be right only by luck. Walk the
        # records properly. (awk, not `grep -P`: macOS ships BSD grep.)
        local value
        value="$(printf '%s\n' "$snap" | awk '
            /<snapshotVersion>/ { inrec=1; cls=""; ext=""; val=""; next }
            inrec && /<classifier>/ { cls=1 }
            inrec && /<extension>/  { ext=$0; sub(/.*<extension>/,"",ext); sub(/<\/extension>.*/,"",ext) }
            inrec && /<value>/      { val=$0; sub(/.*<value>/,"",val);     sub(/<\/value>.*/,"",val) }
            /<\/snapshotVersion>/ {
                if (inrec && cls=="" && ext=="jar" && val!="") { print val; exit }
                inrec=0
            }')"
        if [[ -z "$value" ]]; then
            # Fallback: compose from <timestamp> and <buildNumber>.
            local ts bn
            ts="$(printf '%s' "$snap" | grep -o '<timestamp>[^<]*' | sed 's/<[^>]*>//' | head -1)"
            bn="$(printf '%s' "$snap" | grep -o '<buildNumber>[^<]*' | sed 's/<[^>]*>//' | head -1)"
            [[ -n "$ts" && -n "$bn" ]] || return 1
            value="${mc}-R0.1-${ts}-${bn}"
        fi
        echo "$av $BASE/$av/paper-api-$value.jar"
        return 0
    fi

    # 26.x: newest build, best maturity (stable > beta > alpha).
    local best
    best="$(echo "$ALL_VERSIONS" \
        | grep -E "^${mc//./\\.}\.build\.[0-9]+-(alpha|beta|stable)$" \
        | awk -F'.build.' '{
              split($2, p, "-");
              rank = (p[2]=="stable") ? 3 : (p[2]=="beta") ? 2 : 1;
              printf "%d %09d %s\n", rank, p[1], $0
          }' \
        | sort -k1,1nr -k2,2nr \
        | head -1 | awk '{print $3}')"
    [[ -n "$best" ]] || return 1
    echo "$best $BASE/$best/paper-api-$best.jar"
}

# bash 3.2 (what macOS ships) has no `mapfile`.
TARGETS=()
if [[ $# -gt 0 ]]; then
    TARGETS=("$@")
else
    while IFS= read -r line; do
        [[ -n "$line" ]] && TARGETS+=("$line")
    done < <(mc_versions)
fi

echo "==> ${#TARGETS[@]} version(s) to ensure in $OUT"
FAILED=()
for mc in "${TARGETS[@]}"; do
    jar="$OUT/paper-api-$mc.jar"
    if [[ -s "$jar" ]]; then
        printf '    %-10s skip (have %s)\n' "$mc" "$(du -h "$jar" | cut -f1)"
        continue
    fi
    if ! read -r av url < <(resolve "$mc"); then
        printf '    %-10s FAILED to resolve\n' "$mc"
        FAILED+=("$mc")
        continue
    fi
    printf '    %-10s <- %s\n' "$mc" "$av"
    if ! curl -fsSL "$url" -o "$jar.part"; then
        printf '    %-10s FAILED to download %s\n' "$mc" "$url"
        rm -f "$jar.part"
        FAILED+=("$mc")
        continue
    fi
    mv "$jar.part" "$jar"
done

echo "==> $(ls -1 "$OUT"/paper-api-*.jar 2>/dev/null | wc -l | tr -d ' ') jar(s) cached"
if [[ ${#FAILED[@]} -gt 0 ]]; then
    echo "==> failed: ${FAILED[*]}" >&2
    exit 1
fi
