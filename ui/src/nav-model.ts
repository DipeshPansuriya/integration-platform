// Row 575 (S1 of the guided flow, plan.md): the console menu as ONE table. Each item names the RolePolicy operation it
// depends on, and OPERATION_ROLES copies that operation's role set from
// platform-tenancy/.../RolePolicy.java (READ_CONFIGURATION l.65, APPROVE_VERSION l.68, READ_MESSAGE_TIMELINE l.80);
// nav-model.test.mjs fails if the copy drifts. UX ONLY - the server check is the only gate: a hidden item is never a
// reason to skip a server check, and no API answer is filtered here. Roles unknown (null / empty) -> everything shown.
import type { Route } from './route-types';

export type Need = 'ALL' | 'READ_CONFIGURATION' | 'READ_MESSAGE_TIMELINE' | 'APPROVE_VERSION';

export const OPERATION_ROLES: Record<Exclude<Need, 'ALL'>, readonly string[]> = {
  READ_CONFIGURATION: ['TENANT_ADMIN', 'CONFIGURATOR', 'APPROVER', 'READ_ONLY'],
  READ_MESSAGE_TIMELINE: ['TENANT_ADMIN', 'SUPPORT', 'READ_ONLY'],
  APPROVE_VERSION: ['TENANT_ADMIN', 'APPROVER'],
};

export type NavItem = { label: string; nav: string; hash: string; page: Route['page']; needs: Need };

/** The six primary items, in the order a person works (spec 4.1). */
export const PRIMARY_NAV: readonly NavItem[] = [
  { label: 'Home', nav: 'home', hash: '#/', page: 'home', needs: 'ALL' },
  { label: 'Flows', nav: 'flows', hash: '#/flows', page: 'flows', needs: 'READ_CONFIGURATION' },
  { label: 'Systems', nav: 'systems', hash: '#/systems', page: 'systems', needs: 'READ_CONFIGURATION' },
  { label: 'Approvals', nav: 'approvals', hash: '#/approvals', page: 'approvals', needs: 'APPROVE_VERSION' },
  { label: 'Messages', nav: 'messages', hash: '#/messages', page: 'messages', needs: 'READ_MESSAGE_TIMELINE' },
  { label: 'Reports', nav: 'reports', hash: '#/reports', page: 'reports', needs: 'READ_MESSAGE_TIMELINE' },
];

/**
 * TEMPORARY "More" group (removed by S10): the legacy screens keep their exact data-nav values and hashes until S3-S6 move
 * them under Messages / Reports / Systems / the flow workspace, so no screen spec loses its click target.
 */
export const LEGACY_NAV: readonly NavItem[] = [
  { label: 'Queue', nav: 'queue', hash: '#/queue', page: 'queue', needs: 'READ_MESSAGE_TIMELINE' },
  { label: 'Failures', nav: 'failures', hash: '#/failures', page: 'failures', needs: 'READ_MESSAGE_TIMELINE' },
  { label: 'Activity', nav: 'activity', hash: '#/activity', page: 'activity', needs: 'READ_MESSAGE_TIMELINE' },
  { label: 'Versions', nav: 'versions', hash: '#/versions', page: 'versions', needs: 'READ_CONFIGURATION' },
  { label: 'Contracts', nav: 'contracts', hash: '#/contracts', page: 'contracts', needs: 'READ_CONFIGURATION' },
  { label: 'Rules', nav: 'rules', hash: '#/rules', page: 'rules', needs: 'READ_CONFIGURATION' },
  { label: 'Mappings', nav: 'mappings', hash: '#/mappings', page: 'mappings', needs: 'READ_CONFIGURATION' },
];

type Roles = readonly string[] | null | undefined;

const unknown = (roles: Roles): roles is null | undefined | [] => !roles || roles.length === 0;

function allowed(roles: Roles, needs: Need): boolean {
  if (needs === 'ALL' || unknown(roles)) return true;
  return (roles as readonly string[]).some((role) => OPERATION_ROLES[needs].includes(role));
}

/** The primary items this role can use (TA 6, CF 3, AP 4, SU 3, RO 5; unknown roles: all six). */
export function navFor(roles: Roles): NavItem[] {
  return PRIMARY_NAV.filter((item) => allowed(roles, item.needs));
}

/** The temporary legacy group, filtered by the same rule. */
export function moreFor(roles: Roles): NavItem[] {
  return LEGACY_NAV.filter((item) => allowed(roles, item.needs));
}

/** Screens that open for a role. `approvals` is a read-only list for anyone who reads configuration (plan S5). */
const PAGE_NEEDS: Partial<Record<Route['page'], Need>> = {
  flows: 'READ_CONFIGURATION', versions: 'READ_CONFIGURATION', systems: 'READ_CONFIGURATION', contracts: 'READ_CONFIGURATION',
  rules: 'READ_CONFIGURATION', mappings: 'READ_CONFIGURATION', approvals: 'READ_CONFIGURATION',
  messages: 'READ_MESSAGE_TIMELINE', failures: 'READ_MESSAGE_TIMELINE', queue: 'READ_MESSAGE_TIMELINE',
  activity: 'READ_MESSAGE_TIMELINE', reports: 'READ_MESSAGE_TIMELINE',
};

/** False only when the roles are known and none of them may use this screen (the shell then shows NoAccess). */
export function canOpen(roles: Roles, page: Route['page']): boolean {
  return allowed(roles, PAGE_NEEDS[page] ?? 'ALL');
}

/** Keycloak and Kafka UI are platform-operator consoles: tenant admin only, and never when the roles are unknown. */
export function showPlatformTools(roles: Roles): boolean {
  return !unknown(roles) && (roles as readonly string[]).includes('TENANT_ADMIN');
}

export type PlatformTool = { label: string; nav: string; href: string };

export function platformTools(hostname: string, protocol: string): PlatformTool[] {
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return [
      { label: 'Keycloak', nav: 'keycloak', href: 'http://localhost:8180/admin/master/console/' },
      { label: 'Kafka UI', nav: 'kafbat', href: 'http://localhost:8090' },
    ];
  }
  const parts = hostname.split('.');
  const domain = parts.length > 1 ? parts.slice(1).join('.') : hostname;
  return [
    { label: 'Keycloak', nav: 'keycloak', href: `${protocol}//auth.${domain}/admin/master/console/` },
    { label: 'Kafka UI', nav: 'kafbat', href: `${protocol}//ops.${domain}/kafka/` },
  ];
}
