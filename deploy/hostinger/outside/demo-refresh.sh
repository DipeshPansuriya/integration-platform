#!/bin/sh
# Rebuilds the demo copy in pg-demo from the live database: ONE tenant's rows, ONLY the allow-listed columns of demo-columns.txt.
#   ./outside/demo-refresh.sh <tenant_code>        e.g. showcase-a   (run from /docker/ipass; repeatable; outside users see the new snapshot at commit)
# Route chosen (and why): a filtered COPY of an explicit column allow-list, not seed.sql and not pg_dump. seed.sql only creates
# tenants/users (the interesting data - systems, endpoints, interfaces, routes, messages, deliveries - is made through the app),
# and a dump + restore would have to carry the credential/secret tables and then delete them, i.e. the secret would exist in the
# dump. Here a secret column or table is never selected: it cannot reach the copy, and showcase-b / other tenants are excluded by
# the WHERE on the source side, so their rows never leave the live database.
# The live database is only READ (default_transaction_read_only, SELECT/COPY TO STDOUT); nothing is written there.
# The copy is a SNAPSHOT: it does not follow live changes until the next refresh; public.demo_snapshot says when it was taken.
# All tables are replaced in ONE transaction: readers see the old copy until commit, then the new one.
# Test hooks: SRC_EXEC (default: docker compose exec -T postgres), DEMO_EXEC (default: docker compose --env-file ./.env -f outside/compose.outside.yaml exec -T pg-demo), COLUMNS_FILE.
set -eu
umask 077
HERE="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1
SRC_EXEC="${SRC_EXEC:-docker compose exec -T postgres}"
DEMO_EXEC="${DEMO_EXEC:-docker compose --env-file ${ENV_FILE:-./.env} -f $HERE/compose.outside.yaml exec -T pg-demo}"
COLUMNS_FILE="${COLUMNS_FILE:-$HERE/demo-columns.txt}"
tenant="${1:?usage: demo-refresh.sh <tenant_code>}"
case "$tenant" in *[!A-Za-z0-9._-]*|"") echo "tenant code has a character it should not" >&2; exit 2;; esac
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# source psql: read-only session, reads POSTGRES_USER / POSTGRES_DB inside the live container (container socket, no password)
src() { # psql args...
  # shellcheck disable=SC2086
  $SRC_EXEC sh -c 'PGOPTIONS="-c default_transaction_read_only=on" exec psql -X -q -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"
}
tid="$(echo "SELECT id FROM public.tenant WHERE code = :'code'" | src -v "code=$tenant" -f - | tr -d '\r')"   # -f, not -c: only -f expands :'code'
[ "$(printf '%s\n' "$tid" | grep -c .)" = 1 ] || { echo "tenant '$tenant' not found (or not unique) in the live database: nothing was changed" >&2; exit 1; }
case "$tid" in *[!0-9a-f-]*) echo "unexpected tenant id" >&2; exit 1;; esac

: > "$TMP/load.sql"
printf '\\set ON_ERROR_STOP on\nBEGIN;\nSET LOCAL statement_timeout = 0;\nSET LOCAL lock_timeout = 0;\nSET LOCAL log_min_error_statement = panic;\n' >> "$TMP/load.sql"
names=""
while IFS='|' read -r tbl fcol cols; do
  case "$tbl" in ""|\#*) continue;; esac
  case "$tbl$fcol$cols" in *[!a-z0-9_,]*) echo "bad name in $COLUMNS_FILE: $tbl" >&2; exit 2;; esac
  out="$(src -v "tbl=$tbl" -v "cols=$cols" -f - < "$HERE/demo-columns.sql" | tr -d '\r')"
  [ -n "$out" ] || { echo "a column of $tbl in $COLUMNS_FILE does not exist in the live schema (schema drift): nothing was changed" >&2; exit 1; }
  ddl="${out%%|*}"; sel="${out#*|}"
  {
    echo "DROP TABLE IF EXISTS public.$tbl CASCADE;"; echo "$ddl"
    echo "COPY public.$tbl ($cols) FROM STDIN;"
    src -c "COPY (SELECT $sel FROM public.$tbl WHERE $fcol = '$tid') TO STDOUT" < /dev/null | tr -d '\r'
    printf '%s\n' '\.'
  } >> "$TMP/load.sql"
  names="${names:+$names,}'$tbl'"
done < "$COLUMNS_FILE"
[ -n "$names" ] || { echo "no tables listed in $COLUMNS_FILE" >&2; exit 2; }
cat >> "$TMP/load.sql" <<SQL
SELECT format('DROP TABLE public.%I CASCADE', tablename) FROM pg_tables WHERE schemaname = 'public' AND tablename <> ALL (ARRAY[$names, 'demo_snapshot']) \\gexec
DROP TABLE IF EXISTS public.demo_snapshot;
CREATE TABLE public.demo_snapshot (taken_at timestamptz NOT NULL, tenant_code text NOT NULL);
INSERT INTO public.demo_snapshot VALUES (now(), '$tenant');
SELECT 'GRANT SELECT ON ALL TABLES IN SCHEMA public TO ipass_outside' WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipass_outside') \\gexec
DO \$\$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
              AND column_name ~* '(secret|token|password|passwd|credential|hash|salt|kek|apikey|api_key|settings|payload|detail)') THEN
    RAISE EXCEPTION 'the copy would hold a secret-looking column: nothing was changed';
  END IF;
END \$\$;
COMMIT;
SQL
# shellcheck disable=SC2086
$DEMO_EXEC sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U ipass_demo_admin -d ipass_demo -f -' < "$TMP/load.sql"
# shellcheck disable=SC2086
$DEMO_EXEC sh -c 'psql -X -q -U ipass_demo_admin -d ipass_demo -c "ANALYZE"' >/dev/null
echo "demo copy refreshed from tenant $tenant at $(date -u +%FT%TZ): a snapshot, not live data"
