// T214: the guards of the hosted seed driver. Every request the driver (and the realm login it triggers, hop by hop) makes goes
// through guardedFetch: https only, only the app's own domain (the app host and its siblings such as auth.<domain>), an explicit
// timeout, and redirects are never followed by fetch (the login code follows them itself, one checked hop at a time).

/** `fetch` restricted to https on `baseUrl`'s domain, with a timeout and manual redirects. */
export function guardedFetch(baseUrl, realFetch = globalThis.fetch, timeoutMs = 30000) {
  const host = new URL(baseUrl).hostname;
  const parent = host.split('.').slice(1).join('.');
  const ownDomain = (name) => name === host || (parent.includes('.') && name.endsWith(`.${parent}`));
  return async (input, init = {}) => {
    const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
    if (url.protocol !== 'https:') throw new Error(`refused: ${url.origin} is not https`);
    if (!ownDomain(url.hostname)) throw new Error(`refused: ${url.hostname} is not the app's domain (${parent || host})`);
    return realFetch(url, { ...init, redirect: 'manual', signal: init.signal ?? AbortSignal.timeout(timeoutMs) });
  };
}

/** A failure as one short line: no stack, no multi-line body, every given secret value replaced. */
export function describeFailure(error, secrets = []) {
  let text = String(error?.message ?? error).split('\n')[0];
  for (const secret of secrets) if (secret && secret.length > 3) text = text.split(secret).join('***');
  return `seed-nested-appointment-hosted: ${text.length > 280 ? `${text.slice(0, 280)}...` : text}`;
}
