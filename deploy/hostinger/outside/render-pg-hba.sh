#!/bin/sh
# Renders pg_hba.conf from pg_hba.conf.tmpl for the fixed subnet of ipass-pgfront.
#   IPASS_PGFRONT_SUBNET=172.29.213.0/28 ./outside/render-pg-hba.sh [out-file]     (default: ./outside/pg_hba.conf)
# Refuses anything that is not an IPv4 CIDR with a prefix of 24-28 (a wide subnet would widen the outside door).
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
S="${IPASS_PGFRONT_SUBNET:?set IPASS_PGFRONT_SUBNET (CIDR of the internal ipass-pgfront network)}"
OUT="${1:-$HERE/pg_hba.conf}"
case "$S" in *[!0-9./]*|*/*/*|"") echo "IPASS_PGFRONT_SUBNET is not an IPv4 CIDR" >&2; exit 2;; esac
pfx="${S#*/}"; ip="${S%/*}"
case "$pfx" in 24|25|26|27|28) ;; *) echo "prefix must be 24-28 (got /$pfx)" >&2; exit 2;; esac
printf '%s' "$ip" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$' || { echo "address part is not dotted IPv4" >&2; exit 2; }
sed "s#@PGFRONT_SUBNET@#$S#g" "$HERE/pg_hba.conf.tmpl" > "$OUT.tmp" && mv "$OUT.tmp" "$OUT"
chmod 644 "$OUT"
echo "wrote $OUT for subnet $S"
