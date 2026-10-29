// Row 575 S6 (guided flow, plan S6): the section tabs that fold the old menu entries into the six-item menu. Reports hub = Queue |
// Daily delivery | Activity; Systems = Systems | Contracts. Tabs are links to the unchanged legacy routes; the data-nav values the
// screen specs click (queue, activity, contracts) now live on the tabs, while the Reports and Systems tabs carry none because the
// primary menu items already own data-nav="reports" and "systems" (no duplicate selector on a page). UX only: each page keeps the
// role check it had. No DOM.

import type { Route } from './route-types';

export type TabDef = { key: string; label: string; href: string; page: Route['page']; nav?: string };
export type SectionTab = TabDef & { current: boolean };

export const REPORT_TABS: readonly TabDef[] = [
  { key: 'queue', label: 'Queue', href: '#/queue', page: 'queue', nav: 'queue' },
  { key: 'reports', label: 'Daily delivery', href: '#/reports', page: 'reports' },
  { key: 'activity', label: 'Activity', href: '#/activity', page: 'activity', nav: 'activity' }
];

export const SYSTEMS_TABS: readonly TabDef[] = [
  { key: 'systems', label: 'Systems', href: '#/systems', page: 'systems' },
  { key: 'contracts', label: 'Contracts', href: '#/contracts', page: 'contracts', nav: 'contracts' }
];

const SECTIONS: Record<'reports' | 'systems', { label: string; tabs: readonly TabDef[] }> = {
  reports: { label: 'Reports sections', tabs: REPORT_TABS },
  systems: { label: 'Systems sections', tabs: SYSTEMS_TABS }
};

/** The menu item that owns a page's section (Reports owns queue/activity/reports, Systems owns systems/contracts). */
export function sectionOf(page: Route['page']): 'reports' | 'systems' | null {
  if (REPORT_TABS.some((t) => t.page === page)) return 'reports';
  if (SYSTEMS_TABS.some((t) => t.page === page)) return 'systems';
  return null;
}

export function sectionTabs(page: Route['page']): { label: string; section: 'reports' | 'systems'; tabs: SectionTab[] } | null {
  const section = sectionOf(page);
  if (!section) return null;
  const def = SECTIONS[section];
  return { label: def.label, section, tabs: def.tabs.map((t) => ({ ...t, current: t.page === page })) };
}
