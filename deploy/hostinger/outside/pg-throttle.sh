#!/bin/sh
# Host rules for the outside door, in the DOCKER-USER chain (Docker publishes bypass ufw). IPv4 only (the port is published on IPv4 only).
#   sudo ./outside/pg-throttle.sh on | off | status | check        DRY_RUN=1 prints the commands instead of running them
# Rule 1 (throttle): only NEW TCP connections that ENTER THE HOST on the external interface ($EXT_IF) and were addressed to the
#   published port ($PORT) jump to chain IPASS-PG: per source address at most 3 simultaneous and 12 new per minute (burst 6).
#   Traffic between containers (the platform app to the live postgres, backup, Keycloak) arrives on a br-* bridge, never on
#   $EXT_IF, so it is never counted or dropped. Every address is still allowed.
# Rule 2 (no egress): NEW connections coming IN from the bridge of the published network (br-ipass-pgpub) are dropped, so the
#   proxy cannot open a connection to the internet or to a host service, on top of the network having masquerade switched off.
# `check` runs `iptables -C` for every rule and exits 1 if one is missing. Persistence: OUTSIDE-ACCESS.md section 6.
set -eu
IPT="${IPT:-iptables}"
CHAIN=IPASS-PG
PORT="${PG_PORT:-5432}"
PUB_BR="${PUB_BR:-br-ipass-pgpub}"
EXT_IF="${EXT_IF:-}"
if [ -z "$EXT_IF" ] && [ "${DRY_RUN:-0}" != 1 ]; then EXT_IF="$(ip -4 route show default | awk '{for(i=1;i<NF;i++) if($i=="dev"){print $(i+1); exit}}')"; fi
[ -n "$EXT_IF" ] || EXT_IF="<external-interface>"          # dry run without a host: show the shape
case "$EXT_IF$PUB_BR" in *[!A-Za-z0-9_.@:\<\>-]*) echo "bad interface name" >&2; exit 2;; esac
run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$IPT $*"; else "$IPT" "$@"; fi; }
# the two jump/drop rules (arguments after the chain name) - one definition used by on, off and check
r_throttle() { echo "-i $EXT_IF -p tcp -m conntrack --ctstate NEW --ctorigdstport $PORT -j $CHAIN"; }
r_egress()   { echo "-i $PUB_BR -m conntrack --ctstate NEW -j DROP"; }
has() { # shellcheck disable=SC2086
  "$IPT" -C DOCKER-USER $1 2>/dev/null; }
case "${1:-}" in
  on)
    if [ "${DRY_RUN:-0}" = 1 ] || ! "$IPT" -n -L "$CHAIN" >/dev/null 2>&1; then run -N "$CHAIN"; else run -F "$CHAIN"; fi
    run -A "$CHAIN" -m connlimit --connlimit-above 3 --connlimit-mask 32 -j DROP
    run -A "$CHAIN" -m hashlimit --hashlimit-name ipass-pg --hashlimit-mode srcip --hashlimit-above 12/minute --hashlimit-burst 6 -j DROP
    run -A "$CHAIN" -j RETURN
    for rule in "$(r_throttle)" "$(r_egress)"; do
      # shellcheck disable=SC2086
      if [ "${DRY_RUN:-0}" = 1 ] || ! has "$rule"; then run -I DOCKER-USER 1 $rule; fi
    done
    ;;
  off)
    for rule in "$(r_throttle)" "$(r_egress)"; do
      if [ "${DRY_RUN:-0}" = 1 ]; then
        # shellcheck disable=SC2086
        run -D DOCKER-USER $rule
      else
        # shellcheck disable=SC2086
        while has "$rule"; do run -D DOCKER-USER $rule; done
      fi
    done
    if [ "${DRY_RUN:-0}" = 1 ] || "$IPT" -n -L "$CHAIN" >/dev/null 2>&1; then run -F "$CHAIN"; run -X "$CHAIN"; fi
    ;;
  check)
    bad=0
    for rule in "$(r_throttle)" "$(r_egress)"; do
      # shellcheck disable=SC2086
      if [ "${DRY_RUN:-0}" = 1 ]; then echo "$IPT -C DOCKER-USER $rule"; elif has "$rule"; then echo "OK      DOCKER-USER $rule"; else echo "MISSING DOCKER-USER $rule"; bad=1; fi
    done
    for rule in "-m connlimit --connlimit-above 3 --connlimit-mask 32 -j DROP" "-j RETURN"; do
      # shellcheck disable=SC2086
      if [ "${DRY_RUN:-0}" = 1 ]; then echo "$IPT -C $CHAIN $rule"; elif "$IPT" -C "$CHAIN" $rule 2>/dev/null; then echo "OK      $CHAIN $rule"; else echo "MISSING $CHAIN $rule"; bad=1; fi
    done
    exit $bad
    ;;
  status)
    "$IPT" -n -v -L DOCKER-USER; "$IPT" -n -v -L "$CHAIN" 2>/dev/null || echo "$CHAIN: not present"
    ;;
  *) echo "usage: pg-throttle.sh on | off | status | check" >&2; exit 2;;
esac
