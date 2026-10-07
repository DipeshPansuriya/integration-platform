// E4 (T-097, lane D): the browser-login helper for Playwright - the console's login through the
// REAL Keycloak login form of realm `platform` (no shortcut, no token injected). Used by the
// global setup (the suite's signed-in storage state), the login scenarios and the perf run.

/**
 * Starts at the console's login entry (or `startUrl`), fills the realm's login form with `user`
 * and waits until the browser is back on the console. The console then holds only the backend's
 * HttpOnly session cookie.
 */
export async function browserLogin(page, consoleUrl, user, startUrl = `${consoleUrl}/oauth2/authorization/keycloak`) {
  await page.goto(startUrl);
  await page.waitForSelector('#kc-form-login', { timeout: 30000 });
  await page.fill('#username', user.username);
  await page.fill('#password', user.password);
  await Promise.all([
    page.waitForURL((url) => url.toString().startsWith(consoleUrl), { timeout: 30000 }),
    page.click('#kc-login')
  ]);
}
