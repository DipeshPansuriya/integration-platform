#!/bin/sh
# Row 489 chunk D: the kafka-front bridge may not open NEW connections to anywhere (belt and braces next to enable_ip_masquerade=false).
# on | off | check. Not persistent across a reboot or a Docker restart: re-run `on` (same as pg-throttle.sh).
set -u
R="-i br-ipass-kpub -m conntrack --ctstate NEW -j DROP"
case "${1:-}" in
  on)    iptables -C DOCKER-USER $R 2>/dev/null || iptables -I DOCKER-USER 1 $R; echo "kafka-public-fw: on" ;;
  off)   while iptables -C DOCKER-USER $R 2>/dev/null; do iptables -D DOCKER-USER $R; done; echo "kafka-public-fw: off" ;;
  check) iptables -C DOCKER-USER $R && echo "kafka-public-fw: OK" ;;
  *) echo "usage: $0 on|off|check" >&2; exit 2 ;;
esac
