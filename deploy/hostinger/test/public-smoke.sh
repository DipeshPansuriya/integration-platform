#!/bin/bash
# Public smoke of the hosted iPASS stack, checked from OUTSIDE the server: curl + bash, plus one short python3 snippet for the
# two raw TCP checks (Postgres TLS fingerprint, Kafka Metadata). Read-only, no logins, no secrets, no Docker, no lock.
#   bash deploy/hostinger/test/public-smoke.sh [<postgres TLS SHA-256 fingerprint, AA:BB:..>]
# Env: APP_HOST AUTH_HOST OPS_HOST (default *.dipeshpansuriya.tech), PG_HOST/KAFKA_HOST (default APP_HOST), PYTHON (interpreter).
# Prints one PASS/FAIL line per check; exit 1 if any failed, 0 if all passed.
set -u
APP_HOST="${APP_HOST:-ipass.dipeshpansuriya.tech}"
AUTH_HOST="${AUTH_HOST:-auth.dipeshpansuriya.tech}"
OPS_HOST="${OPS_HOST:-ops.dipeshpansuriya.tech}"
PG_HOST="${PG_HOST:-$APP_HOST}"; KAFKA_HOST="${KAFKA_HOST:-$APP_HOST}"
FP="${1:-86:4D:2F:C0:BC:41:02:06:5C:8E:39:8F:4F:39:69:1C:41:76:C6:13:E0:E8:AC:0E:A8:CA:BB:FD:D6:D6:E3:24}"
case "$FP" in [0-9A-Fa-f][0-9A-Fa-f]:*) ;; *) echo "usage: $0 [SHA-256 fingerprint as AA:BB:...]" >&2; exit 64;; esac
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1  [$2]"; fail=1; }

# status <label> <url> <expected code> [<expected Location substring>] - no redirect following, 15 s cap
status() {
  local out code loc
  out="$(curl -sS -o /dev/null --max-time 15 -w '%{http_code} %{redirect_url}' "$2" 2>&1)" || { bad "$1" "curl: $out"; return; }
  code="${out%% *}"; loc="${out#* }"
  [ "$code" = "$3" ] || { bad "$1" "got $code, want $3"; return; }
  if [ -n "${4:-}" ]; then case "$loc" in *"$4"*) ;; *) bad "$1" "Location '$loc' lacks '$4'"; return;; esac; fi
  ok "$1 -> $code${4:+ to $4}"
}

status "app root"                  "https://$APP_HOST/" 200
status "actuator/health"           "https://$APP_HOST/actuator/health" 200
status "actuator/env closed"       "https://$APP_HOST/actuator/env" 401
status "actuator/heapdump closed"  "https://$APP_HOST/actuator/heapdump" 401
status "v3/api-docs closed"        "https://$APP_HOST/v3/api-docs" 401
status "keycloak platform account" "https://$AUTH_HOST/realms/platform/account" 200
status "keycloak /admin/ redirect" "https://$AUTH_HOST/admin/" 302 "/admin/master/console/"
status "keycloak master OIDC discovery" "https://$AUTH_HOST/realms/master/.well-known/openid-configuration" 200
# the first hop is Kafbat's own OAuth start; the chain must end on the Keycloak login page (final URL is not printed: it carries a state)
status "ops kafka ui first hop 302" "https://$OPS_HOST/kafka/" 302 "/oauth2/authorization/"
out="$(curl -sS -o /dev/null --max-time 20 -b "" -L --max-redirs 4 -w '%{http_code} %{url_effective}' "https://$OPS_HOST/kafka/" 2>&1)" || out="curl: $out"
case "$out" in "200 https://$AUTH_HOST/realms/"*"/protocol/openid-connect/auth?"*) ok "ops kafka ui ends on the Keycloak login page (200)";; *) bad "ops kafka ui ends on the Keycloak login page" "${out%%\?*}";; esac
status "ops wiremock admin"        "https://$OPS_HOST/__admin/" 200

PY="${PYTHON:-}"
if [ -z "$PY" ]; then for c in python3 python /c/Python314/python; do "$c" -I -c 'import sys; sys.exit(sys.version_info < (3, 8))' >/dev/null 2>&1 && { PY="$c"; break; }; done; fi
if [ -z "$PY" ]; then bad "tcp checks" "no working python3 found (set PYTHON)"; else
  # raw TCP checks: prints "PASS ..." / "FAIL ..." lines; sends only an SSLRequest and a Metadata v0 request
  out="$("$PY" -I - "$PG_HOST" "$KAFKA_HOST" "$FP" 2>&1 <<'PYEOF'
import hashlib, socket, ssl, struct, sys
pg_host, kafka_host, want = sys.argv[1], sys.argv[2], sys.argv[3].replace(":", "").lower()

def pg():
    name = "postgres 5432 open + TLS 1.3 + fingerprint"
    try:
        s = socket.create_connection((pg_host, 5432), timeout=10)
    except OSError as e:
        return "FAIL %s  [connect: %s]" % (name, e)
    try:
        s.sendall(struct.pack(">II", 8, 80877103))             # SSLRequest
        if s.recv(1) != b"S":
            return "FAIL %s  [server did not offer TLS]" % name
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE   # pinned by fingerprint, not by a CA
        ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_3
        t = ctx.wrap_socket(s, server_hostname=None)
        got = hashlib.sha256(t.getpeercert(binary_form=True)).hexdigest()
        if t.version() != "TLSv1.3":
            return "FAIL %s  [negotiated %s]" % (name, t.version())
        if got != want:
            return "FAIL %s  [fingerprint %s, want %s]" % (name, got.upper(), want.upper())
        return "PASS %s (TLSv1.3, sha256 %s...)" % (name, got[:8].upper())
    except (OSError, ssl.SSLError) as e:
        return "FAIL %s  [%s]" % (name, e)
    finally:
        s.close()

def rd(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise OSError("closed early")
        b += c
    return b

def ips_of(h):
    return {a[4][0] for a in socket.getaddrinfo(h, 9092, socket.AF_INET)}

def kafka():
    name = "kafka 9092 open + Metadata v0 advertises public IP"
    try:
        ips = ips_of(kafka_host)
        s = socket.create_connection((kafka_host, 9092), timeout=10)
    except OSError as e:
        return "FAIL %s  [connect: %s]" % (name, e)
    try:
        cid = b"public-smoke"
        body = struct.pack(">hhih", 3, 0, 7, len(cid)) + cid + struct.pack(">i", 0)   # MetadataRequest v0, topics=[] (all)
        s.settimeout(10); s.sendall(struct.pack(">i", len(body)) + body)
        resp = rd(s, struct.unpack(">i", rd(s, 4))[0])
        corr, nb = struct.unpack(">ii", resp[:8]); off = 8
        if corr != 7: return "FAIL %s  [correlation id %d]" % (name, corr)
        brokers = []
        for _ in range(nb):
            node, hl = struct.unpack(">ih", resp[off:off + 6]); off += 6
            host = resp[off:off + hl].decode(); off += hl
            port = struct.unpack(">i", resp[off:off + 4])[0]; off += 4
            brokers.append((node, host, port))
        if not brokers: return "FAIL %s  [no brokers in response]" % name
        good = [b for b in brokers if b[2] == 9092 and (b[1] in ips or ips_of(b[1]) & ips)]
        if good: return "PASS %s (broker %d at %s:%d)" % (name, good[0][0], good[0][1], good[0][2])
        return "FAIL %s  [brokers %s, public IPs %s]" % (name, brokers, sorted(ips))
    except (OSError, struct.error, UnicodeDecodeError) as e:
        return "FAIL %s  [%s]" % (name, e)
    finally:
        s.close()

print(pg()); print(kafka())
PYEOF
)"
  rc=$?
  echo "$out"
  [ $rc -eq 0 ] || fail=1
  ! grep -q '^FAIL' <<<"$out" || fail=1
  [ "$(grep -c '^PASS' <<<"$out")" = 2 ] || fail=1
fi
[ $fail -eq 0 ] && echo "PUBLIC SMOKE: ALL PASSED" || echo "PUBLIC SMOKE: FAILED"
exit $fail
