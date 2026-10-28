# Row 571 - sign-in: before and after

Text only (no screenshots in the repo). "Before" = the two screenshots Dipesh sent on 28-Oct.

## Before

1. A visitor with no session landed on the console's own page: a centred card, brand line "Integration Platform",
   h1 "Sign in", the line "Sign in to your tenant's integration workbench", ONE blue button "Continue with SSO" and
   the note "You will be redirected to your organisation's sign-in page, then returned here."
2. Only that button went on to the Keycloak form: a plain card, h1 "Sign in", labels "Username or email" and "Password",
   a separate "Show" button beside the password box, a blue "Sign in" button.
3. After the sign-in the visitor landed on the console home, not on the screen they asked for.

## After

1. No SSO page. `#/login` redirects ONCE to `/oauth2/authorization/keycloak` (Authorization Code + PKCE, state and nonce
   unchanged, owned by the backend) and records the attempt time in `sessionStorage` (no token, no secret).
2. Loop guard (`ui/src/pages/login-flow.ts`, `decideLoginView`): if the visitor comes back to `#/login` with no session
   within 10 minutes of an attempt (Back from the form, a cancelled or failed login), the page shows h1 "Sign-in did not
   complete" with a "Try again" button and does NOT redirect. Only the button starts the next attempt.
3. Right after Sign out the landing shows h1 "You have been signed out" and a "Sign in" button (no automatic redirect).
   Without usable `sessionStorage` the guard cannot work, so a "Sign in" button decides instead of an automatic jump.
4. Return-to: the `#/...` screen asked for is remembered across the round trip and restored once signed in (hash routes
   only, a fixed character set, never a URL, so it cannot become an open redirect; one use; forgotten on sign-out).
5. Keycloak login form (theme `platform`, CSS only - no template override, so it survives Keycloak releases):
   - card with a 4px accent rule on top and a 4px hairline at the foot, both the project's own neutral tokens (light and dark);
   - eyebrow "INTEGRATION PLATFORM" above the h1, same brand line as the console;
   - password box and its Show/Hide toggle are ONE control (one boundary at the >= 3:1 control colour, one focus ring
     drawn on the group, the toggle is a real button named "Show password"/"Hide password" by Keycloak's own aria-label);
   - 44px fields and button, alerts that say "Error", "Warning", "Done" or "Note" in words (not a glyph).
   - Colour tokens (`--platform-*`) are UNCHANGED, so the realm form and the console stay one world (the T90 parity
     tests still pass); only the two new rule tokens were added, in the console too (`--color-rule-top/-bottom`).
6. The console's own sign-in state (Try again, signed-out, session-check error) carries the same card signature.

## Not changed

Backend security configuration, the realm JSON, the OIDC client, cookies, CSRF, logout (RP-initiated), token handling.
A failed code exchange for an API client still ends on the backend's JSON answer (401/503); a browser is sent to #/login (follow-up).

## Brand note

kale-design is NOT used on this project (overview, Dipesh 2026-10-22, supersedes ledger row 268). The card rules use the
project's own tokens (accent on top, hairline at the foot); no Deep Sea, Noon or Evening Sky colour is present, and a
test pins that. Follow-ups to this PR (row 571): bfcache restore always shows Try again; the storage probe writes;
return-to keeps a query on a real route and rejects `#//`, case and encoded tricks; a failed code exchange from a
browser lands on `#/login?error=login_failed` (API clients keep the JSON answer).
