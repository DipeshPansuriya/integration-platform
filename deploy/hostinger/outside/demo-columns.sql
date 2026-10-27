-- Used by demo-refresh.sh on the SOURCE (live) database, read-only. Input psql variables: tbl, cols (comma list).
-- Prints exactly two lines: (1) the CREATE TABLE for the demo copy, (2) the SELECT list for COPY.
-- Types: built-in types are kept; any other type (an enum, a domain) becomes text, so the demo needs none of the live types.
-- Prints nothing if a listed column does not exist in the live table (schema drift): the caller then stops.
WITH c AS (
  SELECT u.c, u.ord, CASE WHEN ty.typtype = 'b' AND ty.typnamespace = 'pg_catalog'::regnamespace
                          THEN format_type(a.atttypid, a.atttypmod) ELSE 'text' END AS demo_type,
         (ty.typtype = 'b' AND ty.typnamespace = 'pg_catalog'::regnamespace) AS plain
    FROM unnest(string_to_array(:'cols', ',')) WITH ORDINALITY u(c, ord)
    JOIN pg_attribute a ON a.attrelid = format('public.%I', :'tbl')::regclass AND a.attname = u.c AND NOT a.attisdropped
    JOIN pg_type ty ON ty.oid = a.atttypid
)
SELECT format('CREATE TABLE public.%I (%s);', :'tbl', string_agg(format('%I %s', c, demo_type), ', ' ORDER BY ord)),
       string_agg(CASE WHEN plain THEN format('%I', c) ELSE format('%I::text', c) END, ', ' ORDER BY ord)
  FROM c
HAVING count(*) = cardinality(string_to_array(:'cols', ','));
