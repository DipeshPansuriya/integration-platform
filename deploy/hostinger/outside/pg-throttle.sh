#!/bin/sh
# Host-level throttle for NEW connections to published port 5432, in the DOCKER-USER chain (Docker publishes bypass ufw).
# Allows every source address; per source it caps simultaneous connections (4) and new connections (12/min, burst 6).
#   sudo ./outside/pg-throttle.sh on | off | status        DRY_RUN=1 prints the commands instead of running them
# IPv4 only (the port is published on IPv4 only). Rules live in the kernel until reboot: see OUTSIDE-ACCESS.md for the
# systemd unit that re-applies them after a reboot or a docker restart.
set -eu
IPT="${IPT:-iptables}"
CHAIN=IPASS-PG
run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$IPT $*"; else "$IPT" "$@"; fi; }
case "${1:-}" in
  on)
    # chain (create or flush), then the jump from DOCKER-USER (once)
    if [ "${DRY_RUN:-0}" = 1 ] || ! "$IPT" -n -L "$CHAIN" >/dev/null 2>&1; then run -N "$CHAIN"; else run -F "$CHAIN"; fi
    run -A "$CHAIN" -m connlimit --connlimit-above 4 --connlimit-mask 32 -j DROP
    run -A "$CHAIN" -m hashlimit --hashlimit-name ipass-pg --hashlimit-mode srcip --hashlimit-above 12/minute --hashlimit-burst 6 -j DROP
    run -A "$CHAIN" -j RETURN
    if [ "${DRY_RUN:-0}" = 1 ] || ! "$IPT" -C DOCKER-USER -p tcp -m conntrack --ctorigdstport 5432 --ctstate NEW -j "$CHAIN" 2>/dev/null; then
      run -I DOCKER-USER 1 -p tcp -m conntrack --ctorigdstport 5432 --ctstate NEW -j "$CHAIN"
    fi
    ;;
  off)
    while [ "${DRY_RUN:-0}" != 1 ] && "$IPT" -C DOCKER-USER -p tcp -m conntrack --ctorigdstport 5432 --ctstate NEW -j "$CHAIN" 2>/dev/null; do
      run -D DOCKER-USER -p tcp -m conntrack --ctorigdstport 5432 --ctstate NEW -j "$CHAIN"
    done
    [ "${DRY_RUN:-0}" != 1 ] || run -D DOCKER-USER -p tcp -m conntrack --ctorigdstport 5432 --ctstate NEW -j "$CHAIN"
    if [ "${DRY_RUN:-0}" = 1 ] || "$IPT" -n -L "$CHAIN" >/dev/null 2>&1; then run -F "$CHAIN"; run -X "$CHAIN"; fi
    ;;
  status)
    "$IPT" -n -v -L DOCKER-USER; "$IPT" -n -v -L "$CHAIN" 2>/dev/null || echo "$CHAIN: not present"
    ;;
  *) echo "usage: pg-throttle.sh on | off | status" >&2; exit 2;;
esac
