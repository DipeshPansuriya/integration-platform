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
# the PC-wide docker lock is slotted: .docker-suite.lock-N (a DIRECTORY with an owner file; plain files of that name belong to
# others and are skipped). Never take over a live holder. Names and port are unique per run so two runs cannot collide.
LOCKROOT="${DOCKER_SUITE_LOCKROOT:-/d/Projects/ipass/.docker-suite.lock}"
LOCK=""; for slot in 2 3 4 5 6 7 8 9; do
  if mkdir "$LOCKROOT-$slot" 2>/dev/null; then LOCK="$LOCKROOT-$slot"; break; fi
done
[ -n "$LOCK" ] || { echo "no free docker lock slot under $LOCKROOT-N - not started"; exit 2; }
N=ipass-edge-ops-$$; PORT="${EDGE_TEST_PORT:-$((18100 + RANDOM % 800))}"
echo "maya ipass-edge-ops pid=$$ $(date -u +%FT%TZ) refreshed=$(date -u +%FT%TZ)" > "$LOCK/owner"
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
# 4b. T219 N1/N2: no percent-encoding in an ops path. Double encoding must not slip past the message filter or the login rate
# limit, and an encoded CR/LF must never become raw bytes in the request line sent upstream. Each is 404 and never reaches the stub.
for u in /kafka/%256Cogin /kafka/%256Danifest.json $API/topics/y/%256Dessages /kafka/mess%2561ges /kafka/login%0d%0aX-A:1 /kafka/a%2fb "/kafka/login?x=1%0d%0a"; do
  s="$(st $O GET "$u")"; case "$u" in *"?"*) want=200;; *) want=404;; esac     # a % in the QUERY STRING is not a path and stays fine
  if [ "$s" = "$want" ]; then ok "ops: $u -> $want"; else bad "ops: $u -> $s (want $want)"; fi
done
s="$(st $O GET /kafka/%00)"; { [ "$s" = 404 ] || [ "$s" = 400 ]; } && ok "ops: /kafka/%00 is refused ($s)" || bad "ops: /kafka/%00 -> $s"
# the request-splitting payloads, sent with a raw client INSIDE the docker network (the host-side curl path resets these connections
# itself, which would prove nothing about nginx): the edge must answer 404 and the stub must never see the smuggled second request.
raw() { # raw request line sent with nc from INSIDE the docker network; prints the status line the edge answered
  local rp="${1//\\/\\\\}"; rp="${rp//%/%%}"
  MSYS_NO_PATHCONV=1 docker run --rm --network $N --entrypoint sh nginx:stable-alpine -c "printf 'GET $rp HTTP/1.1\\r\\nHost: ops.t.invalid\\r\\nConnection: close\\r\\n\\r\\n' | nc -w 3 $N-edge 8080 | head -1" 2>&1 | tr -d '\r' | tail -1
}
for u in '/kafka/api\clusters' "/kafka/a%20HTTP/1.1%0d%0a%0d%0aGET%20/x" "/kafka/login%20HTTP/1.1%0d%0aX-A:%201%0d%0a%0d%0aGET%20/zzz" "/kafka/%250d%250aX"; do
  r="$(raw "$u")"; grep -q ' 404 ' <<<"$r" && ok "ops: request-splitting path $u -> 404" || bad "ops: request-splitting path $u -> [$r]"
done
if docker logs $N-up 2>&1 | grep -E '/zzz|GET /x ' >/dev/null; then bad "ops: a smuggled request reached the upstream"; else ok "ops: nothing smuggled ever reached the upstream"; fi
# legitimate Kafbat paths are untouched (own client address each, so the login limit is not what is being tested)
n2=0
for u in /kafka/ /kafka/login /kafka/oauth2/authorization/keycloak /kafka/index.html /kafka/static/js/main.js "/kafka/api/clusters/ipass/topics?page=1&perPage=25" $API/topics/messages $API/topics/ipass.messages.v1; do
  n2=$((n2+1)); s="$(st $O GET "$u" -H "X-Forwarded-For: 10.2.0.$n2")"; [ "$s" = 200 ] && ok "ops: legitimate $u still served" || bad "ops: legitimate $u -> $s"
done
# 5. the upstream receives the request URI UNCHANGED (raw == tested): same path, same query string; and anything that would make
# the raw path differ from nginx's normalised one (dot segments, doubled slashes, ';' parameters, backslash) is refused at the edge
b="$(body $O GET "$API/topics/b/partitions?x=1&y=2&z=a%20b")"; grep -qF 'uri=[/kafka/api/clusters/ipass/topics/b/partitions?x=1&y=2&z=a%20b]' <<<"$b" && ok "ops: upstream gets the request URI unchanged (path and query)" || bad "ops: forwarded uri: $b"
for u in "$API/topics/a/../b/partitions" "$API/topics/a/./b" "/kafka/api//clusters/ipass/topics" "$API/topics/b;jsessionid=1" "$API/topics/b/.." "$API/topics/b/."; do
  s="$(st $O GET "$u")"; [ "$s" = 404 ] && ok "ops: non-normal path $u -> 404" || bad "ops: non-normal path $u -> $s"
done
# nothing the stub ever received carries a percent sign, a dot segment, a doubled slash or a ';' in the PATH part
if docker logs $N-up 2>&1 | grep -E '"[A-Z]+ [^ ?]*(%|;|//|/\.\.?(/| ))' >/dev/null; then bad "ops: the upstream saw a percent sign / dot segment / double slash / ';' in a path"; else ok "ops: the upstream never saw a percent sign, dot segment, double slash or ';' in a path"; fi
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
