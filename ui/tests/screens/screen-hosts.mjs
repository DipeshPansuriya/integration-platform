// The destination allow-list the screens boot gives the app. Since T97 (DestinationSettingsCheck) saving a
// system checks its host against it, and config-systems.yml ships it empty - so every seeded fixture system
// would get HTTP 400. Fixture hosts are fake .test names that are never resolved or called; syntax is
// AllowList.java's (exact name or *.domain, which does not cover partner.example.test - hence its own entry).
// Unit: tests/screens/unit/screen-hosts.test.mjs keeps every spec's fixture host covered.

export const SCREEN_ALLOWED_HOSTS = Object.freeze(['*.partner.test', 'partner.example.test']);

/** The app setting bootBackend's appEnv adds (mergeAppEnv): the list, comma-joined. */
export function screenAppEnv() {
  return { PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS: SCREEN_ALLOWED_HOSTS.join(',') };
}
