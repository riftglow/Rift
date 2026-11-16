#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

# Bundled rule-sets (local .srs, app-managed) — 2026-10-31 migration.
# The old geoip.db / geosite.db downloads are gone: routing no longer uses
# the db + conversion shim. The three files below are the rule-sets actually
# used by the default rules (cn bypass + ads block); any other user code is
# downloaded on demand from the routing-assets page, never bundled.
#
# Pull route (verified live 2026-10-31, must stay working):
#   https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-<code>.srs
#   https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-<code>.srs
# curl -f fails the script on any 404, and the size check below fails it on
# any empty download, so a dead link or an empty file can never be bundled.
download_srs() {
  curl -fLSs "$1" -o "$2"
  if [ ! -s "$2" ]; then
    echo "empty download: $2 from $1" >&2
    exit 1
  fi
  echo "bundled $2 ($(wc -c < "$2") bytes)"
}

download_srs https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs geoip-cn.srs
download_srs https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs geosite-cn.srs
download_srs https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs geosite-category-ads-all.srs

# Per-file bundled version: "<date> <short-sha>", e.g. "2026-10-31 a1b2c3d".
# Date = build date (also the upgrade-order key used at extraction time);
# SHA = rule-set branch HEAD commit at build time, truncated to its first
# 7 characters (best effort: the API can rate-limit, and a missing SHA
# must not block the build when the .srs files themselves downloaded
# fine — it falls back to "unknown").
# The short SHAs are recorded in srs.commits.txt for traceability
# (the full SHAs live only in the rule-set branches themselves).
branch_head_sha() {
  curl -fsSL "https://api.github.com/repos/$1/commits?sha=rule-set&per_page=1" 2>/dev/null \
    | grep '"sha":' | head -1 | sed -E 's/.*"([^"]+)".*/\1/' | cut -c1-7 || true
}

SNAPSHOT_DATE=$(date -u +%F)
GEOIP_SHA=$(branch_head_sha "SagerNet/sing-geoip")
GEOSITE_SHA=$(branch_head_sha "SagerNet/sing-geosite")
[ -n "$GEOIP_SHA" ] || GEOIP_SHA="unknown"
[ -n "$GEOSITE_SHA" ] || GEOSITE_SHA="unknown"

echo -n "$SNAPSHOT_DATE $GEOIP_SHA" > geoip-cn.version.txt
echo -n "$SNAPSHOT_DATE $GEOSITE_SHA" > geosite-cn.version.txt
echo -n "$SNAPSHOT_DATE $GEOSITE_SHA" > geosite-category-ads-all.version.txt
{
  echo "snapshot: $SNAPSHOT_DATE"
  echo "sing-geoip rule-set: $GEOIP_SHA"
  echo "sing-geosite rule-set: $GEOSITE_SHA"
} > srs.commits.txt
echo "bundled versions: geoip-cn=$SNAPSHOT_DATE $GEOIP_SHA, geosite-cn/geosite-category-ads-all=$SNAPSHOT_DATE $GEOSITE_SHA"
