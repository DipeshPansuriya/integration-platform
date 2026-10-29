// Row 575 (S1 of the guided flow, plan.md): the console menu as ONE table. Each item names the RolePolicy operation it
// depends on, and OPERATION_ROLES copies that operation's role set from
// platform-tenancy/.../RolePolicy.java (READ_CONFIGURATION l.65, APPROVE_VERSION l.68, READ_MESSAGE_TIMELINE l.80);
// nav-model.test.mjs fails if the copy drifts. UX ONLY - the server check is the only gate: a hidden item is never a
// reason to skip a server check, and no API answer is filtered here.
// Roles NOT LOADED (null / undefined: /me pending or failed) -> everything shown. Roles LOADED but empty (no grants) -> Home only.
import type { Route } from './route-types';
import { REPORT_TABS, SYSTEMS_TABS } from './section-tabs';

export type Need = 'ALL' | 'READ_CONFIGURATION' | 'READ_MESSAGE_TIMELINE' | 'APPROVE_VERSION';

export const OPERATION_ROLES: Record<Exclude<Need, 'ALL'>, readonly string[]> = {
  READ_CONFIGURATION: ['TENANT_ADMIN', 'CONFIGURATOR', 'APPROVER', 'READ_ONLY'],
  READ_MESSAGE_TIMELINE: ['TENANT_ADMIN', 'SUPPORT', 'READ_ONLY'],
  APPROVE_VERSION: ['TENANT_ADMIN', 'APPROVER'],
};

/** `initial` is the collapsed-rail mark (data-initial); unique across the menu (tested). */
export type NavItem = { label: string; nav: string; hash: string; page: Route['page']; needs: Need; initial: string };

/** The six primary items, in the order a person works (spec 4.1). */
export const PRIMARY_NAV: readonly NavItem[] = [
  { label: 'Home', nav: 'home', hash: '#/', page: 'home', needs: 'ALL', initial: 'H' },
  { label: 'Flows', nav: 'flows', hash: '#/flows', page: 'flows', needs: 'READ_CONFIGURATION', initial: 'Fl' },
  { label: 'Systems', nav: 'systems', hash: '#/systems', page: 'systems', needs: 'READ_CONFIGURATION', initial: 'S' },
  { label: 'Approvals', nav: 'approvals', hash: '#/approvals', page: 'approvals', needs: 'APPROVE_VERSION', initial: 'Ap' },
  { label: 'Messages', nav: 'messages', hash: '#/messages', page: 'messages', needs: 'READ_MESSAGE_TIMELINE', initial: 'Me' },
  { label: 'Reports', nav: 'reports', hash: '#/reports', page: 'reports', needs: 'READ_MESSAGE_TIMELINE', initial: 'Re' },
];

/**
 * TEMPORARY "More" group (removed by S10): the legacy screens keep their exact data-nav values and hashes until S3-S6 move
 * them under Messages / Reports / Systems / the flow workspace, so no screen spec loses its click target.
 */
export const LEGACY_NAV: readonly NavItem[] = [
  { label: 'Failures', nav: 'failures', hash: '#/failures', page: 'failures', needs: 'READ_MESSAGE_TIMELINE', initial: 'Fa' },
  { label: 'Versions', nav: 'versions', hash: '#/versions', page: 'versions', needs: 'READ_CONFIGURATION', initial: 'V' },
  { label: 'Rules', nav: 'rules', hash: '#/rules', page: 'rules', needs: 'READ_CONFIGURATION', initial: 'Ru' },
  { label: 'Mappings', nav: 'mappings', hash: '#/mappings', page: 'mappings', needs: 'READ_CONFIGURATION', initial: 'Ma' },
];

type Roles = readonly string[] | null | undefined;

/** True only while the roles are not loaded. A loaded empty list is a real state (no grants), never "unknown". */
const unknown = (roles: Roles): roles is null | undefined => !roles;

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

/** Screens every signed-in role may open (Home, and the sign-in card which has no shell). */
export const ALLOW_ALL_PAGES: readonly Route['page'][] = ['home', 'login'];

/**
 * Screens deliberately refused to every KNOWN tenant role until a role need is agreed (Mira + Karan, row 575): Destinations (PR #88)
 * is operator-only (PR #90: every tenant call answers 403), so it is in neither the menu nor PAGE_NEEDS and is refused for every tenant caller, roles known or not.
 */
export const REFUSED_PAGES: readonly Route['page'][] = ['destinations'];

/**
 * The role need of every other screen. A page missing here is REFUSED (closed by default; a test fails for an unmapped page).
 * Approvals (Mira, 28-Oct, row 575): open only to Approver and Tenant admin - the menu item and canOpen use the same need,
 * so a configurator or read-only user who types #/approvals gets NoAccess.
 */
export const PAGE_NEEDS: Partial<Record<Route['page'], Exclude<Need, 'ALL'>>> = {
  flows: 'READ_CONFIGURATION', versions: 'READ_CONFIGURATION', systems: 'READ_CONFIGURATION', contracts: 'READ_CONFIGURATION',
  rules: 'READ_CONFIGURATION', mappings: 'READ_CONFIGURATION', approvals: 'APPROVE_VERSION',
  messages: 'READ_MESSAGE_TIMELINE', failures: 'READ_MESSAGE_TIMELINE', queue: 'READ_MESSAGE_TIMELINE',
  activity: 'READ_MESSAGE_TIMELINE', reports: 'READ_MESSAGE_TIMELINE',
};

/** False when the roles are known and none may use this screen (the shell then shows NoAccess). Unmapped pages are refused. */
export function canOpen(roles: Roles, page: Route['page']): boolean {
  if (ALLOW_ALL_PAGES.includes(page)) return true;
  if (REFUSED_PAGES.includes(page)) return false; // before the unknown-roles rule: Destinations is operator-only whatever /me said
  if (unknown(roles)) return true;
  const need = PAGE_NEEDS[page];
  return need !== undefined && allowed(roles, need);
}

/** The screen's name, for the heading above a NoAccess state. */
export function titleOf(page: Route['page']): string {
  // Queue, Activity and Contracts are tabs now (S6), not menu items; their tab labels name them.
  const named = [...REPORT_TABS, ...SYSTEMS_TABS].find((t) => t.page === page && t.nav);
  return [...PRIMARY_NAV, ...LEGACY_NAV].find((item) => item.page === page)?.label ?? named?.label ?? 'This screen';
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
