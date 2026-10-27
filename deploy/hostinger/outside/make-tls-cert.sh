#!/bin/sh
# Self-signed server certificate for the outside Postgres door (ECDSA P-256, default 1095 days = 3 years, max 1100),
# generated ON THE SERVER.   ./outside/make-tls-cert.sh <dir> <ip-or-name[,more,...]>   e.g. ./outside/make-tls-cert.sh ./outside/tls 203.0.113.7
# The first SAN entry becomes the CN. Never overwrites. Certificate facts: CA:FALSE (it can never act as a CA, so pinning it in
# a trust store is harmless), keyUsage digitalSignature, extendedKeyUsage serverAuth.
# The PUBLIC server.crt is what clients pin (sslrootcert=server.crt, sslmode=verify-full); it is not a secret. The script prints its
# SHA-256 fingerprint and expiry: send the fingerprint to each user by a DIFFERENT channel than the file (OUTSIDE-ACCESS.md 5).
# Files: <dir> mode 0750 root, server.key mode 0600 root, server.crt 0644. The pg-demo container copies the key into its own
# tmpfs with the owner postgres needs, so no host-side chown to the container uid is required (and none is attempted).
set -eu
umask 077
export MSYS_NO_PATHCONV=1
dir="${1:?usage: make-tls-cert.sh <dir> <ip-or-name[,...]>}"; sans="${2:?list at least one IP or host name}"
days="${DAYS:-1095}"
case "$days" in ""|*[!0-9]*) echo "DAYS must be a number" >&2; exit 2;; esac
[ "$days" -ge 365 ] && [ "$days" -le 1100 ] || { echo "DAYS must be between 365 and 1100 (a pinned certificate: 2 to 3 years)" >&2; exit 2; }
[ ! -e "$dir/server.key" ] || { echo "$dir/server.key exists - refusing to overwrite (renewal steps: OUTSIDE-ACCESS.md section 5)" >&2; exit 3; }
san=""; first=""
oldifs="$IFS"; IFS=,
for s in $sans; do
  case "$s" in ""|*[!A-Za-z0-9.:-]*) echo "bad SAN entry" >&2; exit 2;; esac
  case "$s" in *[!0-9.]*) k=DNS;; *) k=IP;; esac
  san="${san:+$san,}$k:$s"; first="${first:-$s}"
done
IFS="$oldifs"
mkdir -p "$dir"; chmod 750 "$dir"
# relative file names inside $dir: a native Windows openssl cannot read POSIX temp paths, and -subj must not be path-converted
( cd "$dir" && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days "$days" \
  -subj "/CN=$first" -addext "subjectAltName=$san" -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=critical,digitalSignature" -addext "extendedKeyUsage=serverAuth" -keyout server.key -out server.crt 2>/dev/null )
chmod 600 "$dir/server.key"; chmod 644 "$dir/server.crt"
echo "wrote $dir/server.crt and server.key (SAN: $san)"
( cd "$dir" && openssl x509 -noout -fingerprint -sha256 -enddate -in server.crt )
