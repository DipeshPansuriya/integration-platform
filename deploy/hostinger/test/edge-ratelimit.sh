#!/bin/bash
# Behaviour proof of the edge login rate limit (edge/edge.conf): one stub upstream + the real edge.conf in an nginx
# container, under the PC-wide Docker lock (it is NOT taken over if a live owner holds it: exit 2, nothing started).
#   NGINX_TEST_TAG=<tag of a local ipass/ui image> bash deploy/hostinger/test/edge-ratelimit.sh
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${NGINX_TEST_TAG:?set NGINX_TEST_TAG to a local ipass/ui image tag}"
LOCK="${DOCKER_SUITE_LOCK:-/d/Projects/ipass/.docker-suite.lock}"
N=ipass-edge-rl; PORT="${EDGE_TEST_PORT:-18091}"
mkdir "$LOCK" 2>/dev/null || { echo "docker lock held by: $(cat "$LOCK/owner" 2>/dev/null) - not started"; exit 2; }
echo "arjun ipass-edge-ratelimit $(date -u +%FT%TZ) refreshed=$(date -u +%FT%TZ)" > "$LOCK/owner"
cleanup() { docker rm -f $N-edge $N-up >/dev/null 2>&1; docker network rm $N >/dev/null 2>&1; rm -rf "$LOCK"; }
trap cleanup EXIT
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1"; fail=1; }
docker network create $N >/dev/null || exit 1
# the stub answers every name the edge proxies to (keycloak, platform-app, ui) with the ui image's nginx
docker run -d --name $N-up --network $N --network-alias keycloak --network-alias platform-app --network-alias ui "ipass/ui:$TAG" >/dev/null || exit 1
MSYS_NO_PATHCONV=1 docker run -d --name $N-edge --network $N -p 127.0.0.1:$PORT:8080 \
  -v "$(cygpath -m "$HERE" 2>/dev/null || echo "$HERE")/edge/edge.conf:/etc/nginx/conf.d/default.conf:ro" "ipass/ui:$TAG" >/dev/null || exit 1
for i in $(seq 1 30); do [ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: ipass.t.invalid' http://127.0.0.1:$PORT/healthz)" = 200 ] && break; sleep 1; done
# req <host> <method> <path> <xff|-> -> status
req() { if [ "$4" = - ]; then curl -s -o /dev/null -w '%{http_code}' -X "$2" -H "Host: $1" "http://127.0.0.1:$PORT$3"; else curl -s -o /dev/null -w '%{http_code}' -X "$2" -H "Host: $1" -H "X-Forwarded-For: $4" "http://127.0.0.1:$PORT$3"; fi; }
burst() { local c429=0 other=0 s; for i in $(seq 1 "$5"); do s="$(req "$1" "$2" "$3" "$4")"; [ "$s" = 429 ] && c429=$((c429+1)) || other=$((other+1)); done; echo "$c429 $other"; }
read -r r429 rother <<<"$(burst auth.t.invalid POST /realms/platform/protocol/openid-connect/token 10.0.0.1 12)"
[ "$rother" -eq 6 ] && [ "$r429" -eq 6 ] && ok "token: 12 rapid calls = 6 served (1 + burst 5), 6 refused with 429" || bad "token burst: 429=$r429 other=$rother"
[ "$(req auth.t.invalid POST /realms/platform/protocol/openid-connect/token '9.9.9.9, 10.0.0.1')" = 429 ] && ok "a spoofed first X-Forwarded-For entry does not reset the budget (last entry is the key)" || bad "spoofed first entry"
[ "$(req auth.t.invalid POST /realms/platform/protocol/openid-connect/token 10.0.0.2)" != 429 ] && ok "another client address still has its own budget" || bad "second client"
[ "$(req auth.t.invalid POST /realms/platform/login-actions/authenticate 10.0.0.2)" != 429 ] && ok "the authenticate path is served for a client under budget" || bad "authenticate"
read -r a429 _ <<<"$(burst auth.t.invalid POST /realms/platform/login-actions/authenticate 10.0.0.4 12)"
[ "$a429" -ge 1 ] && ok "login-actions/authenticate: excess calls get 429" || bad "authenticate burst"
# row 489 chunk C: the master realm is proxied like any realm and its token path is rate limited the same way (edge.conf: realm-agnostic)
read -r m429 mother <<<"$(burst auth.t.invalid POST /realms/master/protocol/openid-connect/token 10.0.0.9 12)"
[ "$mother" -eq 6 ] && [ "$m429" -eq 6 ] && ok "master realm token is proxied (not 404) and limited like any realm: 6 served, 6 refused with 429" || bad "master realm token burst: 429=$m429 other=$mother"
read -r d429 _ <<<"$(burst auth.t.invalid GET /realms/platform/.well-known/openid-configuration 10.0.0.5 15)"
[ "$d429" -eq 0 ] && ok "discovery and other Keycloak paths are not limited" || bad "discovery limited ($d429)"
read -r l429 _ <<<"$(burst ipass.t.invalid GET /login/oauth2/code/keycloak 10.0.0.6 12)"
[ "$l429" -ge 1 ] && ok "app /login: excess calls get 429" || bad "app login burst"
read -r p429 _ <<<"$(burst ipass.t.invalid GET /api/v1/tenants 10.0.0.7 15)"
[ "$p429" -eq 0 ] && ok "app /api is not limited" || bad "/api limited ($p429)"
[ "$(req ipass.t.invalid GET /healthz -)" = 200 ] && ok "no X-Forwarded-For: the connection address is the key and the edge answers" || bad "no xff"
echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
