// T130 (row 323, F21 BS-B): the ONE place the base suite reads the dev-only outbound exception from.
//
// Dipesh "Option 1" (2026-10-16 20:38 IST): BS-07, BS-10 and BS-12 reach the one local WireMock only
// through the dev-only exception row 278 builds - a named dev/showcase profile, one exact host, no
// wildcard, the app's REAL host resolver (plan section 2; karan reviews it as BS-17 / T137 before it
// lands). Its profile name, its host and the name of the app variable that carries the host are row
// 278's - TBD(row278) until row 278's build lands; they are read from the four names in EXCEPTION_ENV and
// nowhere else, and a value still reading TBD is refused (PLACEHOLDER). The fourth, the port the target
// listens on behind the exception host, is one integer 1-65535 (Kavita, row 323 follow-up 18-Oct).
//
// Unset -> not configured: every BS scenario FAILS with NOT_CONFIGURED (never a skip). There is no
// default host and no fallback: the unnamed loopback stays what the guard refuses
// (DestinationGuard.java:60-66 and :81-86 on main), and nothing here touches the guard or the
// resolver. The profile reaches the app through Spring Boot's standard SPRING_PROFILES_ACTIVE.

export const EXCEPTION_ENV = Object.freeze({
  profile: 'IPASS_BS_EXCEPTION_PROFILE',
  host: 'IPASS_BS_EXCEPTION_HOST',
  hostVar: 'IPASS_BS_EXCEPTION_HOST_VAR',
  port: 'IPASS_BS_EXCEPTION_PORT'
});

export const NOT_CONFIGURED = 'dev-only exception not configured - waits for row 278';
/**
 * Step 5: the placeholder the suite's docs show for the four names until row 278's build lands.
 * Any value still reading "tbd" (any case) is refused - fail closed, never a guessed name that looks final.
 */
export const PLACEHOLDER = 'TBD(row278)';
const STILL_TBD = 'dev-only exception still TBD(row278)';
const MISCONFIGURED = 'dev-only exception misconfigured';

/** The JVM option variables a hosts-file or name-service override could ride in. */
const JVM_OPTION_VARS = Object.freeze(['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']);
const RESOLVER_OVERRIDES = /jdk\.net\.hosts\.file|sun\.net\.spi\.nameservice/i;
/** Names the exception's app variable may never be: the profile switch, the JVM's own settings. */
const RESERVED_VARS = new Set(['SPRING_PROFILES_ACTIVE', 'JAVA_HOME', 'PATH', ...JVM_OPTION_VARS]);
const LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const NOT_A_HOST = 'is not a host name (one exact name: no wildcard, port, scheme, path, user info, space or IP literal)';

function profileProblem(profile) {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(profile)) return `profile "${profile}" is not ONE profile name`;
  if (profile.toLowerCase() === 'default') return 'profile "default" is not a named dev/showcase profile';
  return null;
}

function hostProblem(host) {
  if (host.length > 253 || /[^a-z0-9.-]/.test(host)) return `host "${host}" ${NOT_A_HOST}`;
  const labels = host.split('.');
  if (labels.some((label) => !LABEL.test(label))) return `host "${host}" ${NOT_A_HOST}`;
  // RFC 3696 section 2: a top-level label is never all digits - that shape is an address, however written
  if (/^\d+$/.test(labels[labels.length - 1]) || /^0x/i.test(host)) {
    return `host "${host}" is an IP literal, not a host name - the unnamed loopback must stay refused`;
  }
  return null;
}

function portProblem(port) {
  if (!/^[0-9]{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
    return `${EXCEPTION_ENV.port} "${port}" is not ONE TCP port (an integer 1-65535)`;
  }
  return null;
}

function hostVarProblem(name) {
  if (!/^[A-Z][A-Z0-9_]*$/.test(name)) return `app variable "${name}" is not an environment name`;
  if (RESERVED_VARS.has(name)) return `app variable "${name}" is the profile switch or a JVM setting, never the exception host`;
  return null;
}

/**
 * The exception as configured in `env`: `{ configured: true, profile, host, hostVar, port, appEnv }`, where
 * `appEnv` is what the app process gets on top of the harness's own settings - or
 * `{ configured: false, missing, reason }`. Values are trimmed; the host is lower-cased as the guard
 * normalises it (AllowList.normalise, DestinationGuard.java:58).
 */
export function exceptionSettings(env = process.env) {
  const read = (name) => (typeof env[name] === 'string' ? env[name].trim() : '');
  const values = {
    profile: read(EXCEPTION_ENV.profile),
    host: read(EXCEPTION_ENV.host).toLowerCase(),
    hostVar: read(EXCEPTION_ENV.hostVar),
    port: read(EXCEPTION_ENV.port)
  };
  const missing = Object.entries(EXCEPTION_ENV).filter(([key]) => values[key] === '').map(([, name]) => name);
  if (missing.length > 0) {
    return {
      configured: false,
      missing,
      reason: `${NOT_CONFIGURED}: set ${missing.join(', ')} (the named profile, the one exact host, the app`
        + " variable name row 278's build defines and the target port)"
    };
  }
  const tbd = Object.entries(EXCEPTION_ENV).filter(([key]) => /tbd/i.test(values[key])).map(([, name]) => name);
  if (tbd.length > 0) {
    return { configured: false, missing: [], reason: `${STILL_TBD}: ${tbd.join(', ')} - set them to the names row 278's build defines` };
  }
  const problems = [profileProblem(values.profile), hostProblem(values.host), hostVarProblem(values.hostVar),
    portProblem(values.port)].filter(Boolean);
  if (problems.length > 0) return { configured: false, missing: [], reason: `${MISCONFIGURED}: ${problems.join('; ')}` };
  return Object.freeze({
    configured: true,
    ...values,
    port: Number(values.port),
    appEnv: Object.freeze({ SPRING_PROFILES_ACTIVE: values.profile, [values.hostVar]: values.host })
  });
}

/** The settings when configured; otherwise the scenario fails here with the reason (never a skip). */
export function requireException(settings) {
  if (!settings?.configured) throw new Error(settings?.reason ?? NOT_CONFIGURED);
  return settings;
}

/** A target base URL on the exception host - the only host any BS target is configured with. */
export function targetUrl(settings, port, path) {
  requireException(settings);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`targetUrl: port ${port} is not a TCP port`);
  if (typeof path !== 'string' || !path.startsWith('/')) throw new Error(`targetUrl: path ${path} must start with /`);
  return `http://${settings.host}:${port}${path}`;
}

/**
 * The app must resolve the exception host with its REAL resolver (T130 criterion 2): a hosts-file or
 * name-service override in any JVM option variable the app inherits is a test resolver in disguise.
 */
export function assertRealResolver(env = process.env) {
  for (const name of JVM_OPTION_VARS) {
    const value = env[name];
    const hit = typeof value === 'string' ? value.match(RESOLVER_OVERRIDES) : null;
    if (hit) {
      throw new Error(`the app must run on its real host resolver: ${name} carries ${hit[0]} (a test resolver) - unset it for the base suite`);
    }
  }
}
