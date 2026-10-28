#!/bin/sh
# Expiry check for the pinned certificate; exit 1 (and a log line at crit priority, when `logger` exists) if it expires within
# WARN_DAYS (default 90). Run it daily from cron or a systemd timer on the server and wire the failure into your alerting:
#   0 6 * * *  /docker/ipass/outside/cert-expiry-check.sh /docker/ipass/outside/tls/server.crt
# Renewal runbook: OUTSIDE-ACCESS.md section 5.
set -eu
crt="${1:?usage: cert-expiry-check.sh <server.crt> [warn-days]}"; warn="${2:-${WARN_DAYS:-90}}"
case "$warn" in ""|*[!0-9]*) echo "warn-days must be a number" >&2; exit 2;; esac
end="$(openssl x509 -noout -enddate -in "$crt" | sed 's/^notAfter=//')"
if openssl x509 -noout -checkend $((warn * 86400)) -in "$crt" >/dev/null; then
  echo "OK certificate valid beyond $warn days (notAfter=$end)"
else
  msg="ipass outside Postgres certificate expires within $warn days (notAfter=$end): renew now (OUTSIDE-ACCESS.md section 5)"
  echo "ALERT $msg" >&2
  command -v logger >/dev/null 2>&1 && logger -t ipass-outside-cert -p user.crit "$msg" || true
  exit 1
fi
