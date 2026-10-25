#!/bin/bash
# Behaviour proof of the T219 edge rules (edge/edge.conf): one stub upstream that ECHOES what it was sent + the real
# edge.conf in an nginx container, under the PC-wide Docker lock (not taken over if a live owner holds it: exit 2).
#   NGINX_TEST_TAG=<tag of a local ipass/ui image> bash deploy/hostinger/test/edge-ops.sh
# Covers: ops host allow-list (message paths 404 in every spelling, topics merely NAMED like "messages" open, non-GET 403,
# console / master realm / platform realm 404), forwarded path == tested path, no Upgrade/Connection forwarded to Keycloak and
# Kafbat (h2c), rate limits incl. the trailing-slash form, auth-host 404s unchanged.
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${NGINX_TEST_TAG:?set NGINX_TEST_TAG to a local ipass/ui image tag}"
LOCK="${DOCKER_SUITE_LOCK:-/d/Projects/ipass/.docker-suite.lock}"
N=ipass-edge-ops; PORT="${EDGE_TEST_PORT:-18092}"
mkdir "$LOCK" 2>/dev/null || { echo "docker lock held by: $(cat "$LOCK/owner" 2>/dev/null) - not started"; exit 2; }
echo "maya ipass-edge-ops $(date -u +%FT%TZ) refreshed=$(date -u +%FT%TZ)" > "$LOCK/owner"
TMP="$(mktemp -d)"
cleanup() { docker rm -f $N-edge $N-up >/dev/null 2>&1; docker network rm $N >/dev/null 2>&1; rm -rf "$LOCK" "$TMP"; }
trap cleanup EXIT
fail=0; ok() { echo "PASS $1"; }; bad() { echo "FAIL $1"; fail=1; }
cat > "$TMP/stub.conf" <<'CONF'
server { listen 8080; location / { default_type text/plain; return 200 "up=[$http_upgrade] conn=[$http_connection] uri=[$request_uri] m=[$request_method]\n"; } }
CONF
p() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
docker network create $N >/dev/null || exit 1
MSYS_NO_PATHCONV=1 docker run -d --name $N-up --network $N --network-alias keycloak --network-alias platform-app --network-alias ui --network-alias kafbat \
  -v "$(p "$TMP")/stub.conf:/etc/nginx/conf.d/default.conf:ro" "ipass/ui:$TAG" >/dev/null || exit 1
MSYS_NO_PATHCONV=1 docker run -d --name $N-edge --network $N -p 127.0.0.1:$PORT:8080 \
  -v "$(p "$HERE")/edge/edge.conf:/etc/nginx/conf.d/default.conf:ro" "ipass/ui:$TAG" >/dev/null || exit 1
for i in $(seq 1 30); do [ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: ops.t.invalid' http://127.0.0.1:$PORT/healthz)" = 200 ] && break; sleep 1; done
# st <host> <method> <path> [extra curl args] -> status ; body <same> -> body
st()   { local h="$1" m="$2" u="$3"; shift 3; curl -s -g --path-as-is -m 10 -o /dev/null -w '%{http_code}' -X "$m" -H "Host: $h" "$@" "http://127.0.0.1:$PORT$u"; }
body() { local h="$1" m="$2" u="$3"; shift 3; curl -s -g --path-as-is -m 10 -X "$m" -H "Host: $h" "$@" "http://127.0.0.1:$PORT$u"; }
O=ops.t.invalid; A=auth.t.invalid; P=ipass.t.invalid
API=/kafka/api/clusters/ipass
# 1. message / produce / smartfilter / serde paths are 404 in every spelling
for u in $API/topics/t/messages $API/topics/t/messages/v2 $API/topics/t/messages/schema $API/topics/t/MESSAGES $API/topics/t/mess%61ges \
         $API/topics/t/%6Dessages //kafka/api/clusters/ipass/topics/t/messages $API/topics/t/./messages $API/topics/t/messages/ \
         "$API/topics/t/messages;a=b" $API/topics/t/messages%2fx $API/topics/t/serdes $API/smartfilters $API/topics/t/smartfilters \
         $API/topics/a/../t/messages "$API/topics/t/messages?limit=1"; do
  s="$(st $O GET "$u")"; [ "$s" = 404 ] && ok "ops: 404 for $u" || bad "ops: $u was not 404 ($s)"
done
# 2. a topic or group merely NAMED like those words still opens (the filter is anchored to the API shape)
for u in $API/topics/messages $API/topics/ipass.messages.v1 $API/topics/produce-events $API/topics/messages/partitions $API/topics/smartfilters-test $API/consumer-groups/messages; do
  s="$(st $O GET "$u")"; [ "$s" = 200 ] && ok "ops: 200 for $u" || bad "ops: $u was not served ($s)"
done
# 3. methods
for m in POST PUT DELETE PATCH; do s="$(st $O $m $API/topics)"; [ "$s" = 403 ] && ok "ops: $m on the API is 403" || bad "ops: $m on the API ($s)"; done
[ "$(st $O GET /kafka/logout)" = 403 ] && ok "ops: GET /kafka/logout is 403" || bad "ops: GET logout"
[ "$(st $O POST /kafka/logout)" = 200 ] && ok "ops: POST /kafka/logout reaches Kafbat" || bad "ops: POST logout"
[ "$(st $O POST /kafka/)" = 403 ] && ok "ops: POST /kafka/ is 403" || bad "ops: POST /kafka/"
# 4. everything that is not Kafbat is 404 on ops
for u in /kafka/actuator/health /kafka/actuator%2fhealth /kafka/api/../actuator/health /actuator/health /v3/api-docs /swagger-ui/index.html /realms/platform/.well-known/openid-configuration \
         /realms/master/.well-known/openid-configuration /realms/master/protocol/openid-connect/token /realms/master/protocol/openid-connect/token/ /admin/ /admin/master/console/ /admin/realms /resources/x /js/x /metrics /health /api/v1/x /KAFKA/; do
  s="$(st $O GET "$u")"; [ "$s" = 404 ] && ok "ops: 404 for $u" || bad "ops: $u was not 404 ($s)"
done
[ "$(st $O GET /)" = 302 ] && ok "ops: / redirects to /kafka/" || bad "ops: /"
if curl -s -o /dev/null -D - -H "Host: $O" "http://127.0.0.1:$PORT/" | grep -i '^location:' | grep -q ':8080'; then bad "ops: the redirect leaks the edge port"; else ok "ops: the redirect carries no :8080"; fi
# 5. the path the upstream receives is the path that was tested
b="$(body $O GET "$API/topics/a/../b/partitions?x=1&y=2")"; grep -qF 'uri=[/kafka/api/clusters/ipass/topics/b/partitions?x=1&y=2]' <<<"$b" && ok "ops: upstream gets the normalised path and the query string" || bad "ops: forwarded uri: $b"
b="$(body $O GET /kafka/api//clusters/ipass/topics)"; grep -qF 'uri=[/kafka/api/clusters/ipass/topics]' <<<"$b" && ok "ops: merged slashes are forwarded merged" || bad "ops: double slash: $b"
# 6. no Upgrade / Connection reaches Keycloak or Kafbat (h2c); the app host keeps websocket
for spec in "$A /realms/platform/.well-known/openid-configuration" "$O /kafka/login" "$O $API/topics"; do
  set -- $spec; b="$(body $1 GET $2 -H 'Upgrade: h2c' -H 'Connection: Upgrade, HTTP2-Settings' -H 'HTTP2-Settings: AAMAAABkAAQAoAAAAAIAAAAA')"
  grep -qF 'up=[] conn=[]' <<<"$b" && ok "h2c: $1$2 forwards no Upgrade or Connection" || bad "h2c: $1$2 -> $b"
done
b="$(body $P GET /api/v1/x -H 'Upgrade: websocket' -H 'Connection: Upgrade')"; grep -qF 'up=[websocket]' <<<"$b" && ok "the app host still forwards a WebSocket upgrade" || bad "app websocket: $b"
# 7. rate limits, both slash forms (12 rapid calls = 6 served, 6 refused)
burst() { local c=0 s i; for i in $(seq 1 "$5"); do s="$(st "$1" "$2" "$3" -H "X-Forwarded-For: $4")"; [ "$s" = 429 ] && c=$((c+1)); done; echo $c; }
n=0
for spec in "$A POST /realms/platform/protocol/openid-connect/token" "$A POST /realms/platform/protocol/openid-connect/token/" \
            "$A POST /realms/platform/login-actions/authenticate" "$A POST /realms/platform/login-actions/authenticate/" \
            "$O GET /kafka/login" "$O GET /kafka/oauth2/authorization/keycloak"; do
  set -- $spec; n=$((n+1)); c="$(burst $1 $2 $3 10.1.0.$n 12)"
  [ "$c" -eq 6 ] && ok "rate limit: $1 $3 = 6 refused of 12" || bad "rate limit: $1 $3 refused $c of 12 (want 6)"
done
[ "$(burst $A GET /realms/platform/.well-known/openid-configuration 10.1.0.99 15)" -eq 0 ] && ok "discovery is not limited" || bad "discovery limited"
# 8. auth host: console and master realm stay 404 (with and without the slash)
for u in /admin /admin/ /admin/realms /realms/master /realms/master/ /realms/master/protocol/openid-connect/token /realms/master/protocol/openid-connect/token/ /metrics /health; do
  s="$(st $A GET $u)"; [ "$s" = 404 ] && ok "auth: 404 for $u" || bad "auth: $u was not 404 ($s)"
done
echo; [ $fail -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"; exit $fail
