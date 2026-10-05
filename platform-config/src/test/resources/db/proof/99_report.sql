-- Lane B proof verdict (T-014, T-018, T-020) - runs as ipaas_migrator. Prints every probe
-- and fails the psql session (exit 3) when any probe missed its contract, so run-proof.sh
-- turns the SQL layer into a pass/fail result the same way a test runner would.

SELECT * FROM public.proof_probe ORDER BY step;

DO $$
DECLARE
    mismatched record;
BEGIN
    FOR mismatched IN
        SELECT step, want_sqlstate, got_sqlstate, note FROM public.proof_probe
        WHERE (want_sqlstate = 'ERR' AND got_sqlstate = 'NONE')
           OR (want_sqlstate NOT IN ('NONE', 'ERR') AND got_sqlstate <> want_sqlstate)
           OR note LIKE 'refusal did not fire%'
           OR got_sqlstate = 'FAIL'
    LOOP
        RAISE NOTICE 'PROBE FAIL %: want %, got % - %', mismatched.step, mismatched.want_sqlstate,
            mismatched.got_sqlstate, mismatched.note;
    END LOOP;
    IF EXISTS (SELECT 1 FROM public.proof_probe
               WHERE (want_sqlstate = 'ERR' AND got_sqlstate = 'NONE')
                  OR (want_sqlstate NOT IN ('NONE', 'ERR') AND got_sqlstate <> want_sqlstate)
                  OR note LIKE 'refusal did not fire%'
                  OR got_sqlstate = 'FAIL') THEN
        RAISE EXCEPTION 'THE LANE B PROOF RAN RED: at least one probe missed its contract';
    END IF;
    RAISE NOTICE 'LANE B PROOF GREEN: every probe met its contract';
END $$;