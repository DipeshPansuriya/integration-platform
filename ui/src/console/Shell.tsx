import * as React from 'react';
import { useCallback, useState } from 'react';
import { readCookie } from '../api/client';
import { useDashboardCounts } from '../api/hooks';
import { AppShell } from '../ds/components/shell/AppShell.jsx';
import { EnvBadge } from '../ds/components/shell/EnvBadge.jsx';
import { LogoMark } from '../ds/components/brand/LogoMark.jsx';
import { NavItem } from '../ds/components/shell/NavItem.jsx';
import { Topbar } from '../ds/components/shell/Topbar.jsx';
import { UserChip } from '../ds/components/shell/UserChip.jsx';
import { useConsoleTheme } from './theme';
import type { Route } from '../route-types';

export interface ShellProps {
  roles?: readonly string[] | null;
  tenant?: string;
  env?: string;
  version?: string;
  page?: Route['page'];
  children?: React.ReactNode;
}

type NavIcon =
  | 'dashboard' | 'flows' | 'systems' | 'contracts' | 'rules'
  | 'mappings' | 'queue' | 'failures' | 'activity' | 'reports'
  | 'document' | 'check' | 'menu';

const NAV_ICONS: Record<string, NavIcon> = {
  home: 'dashboard',
  flows: 'flows',
  systems: 'systems',
  approvals: 'check',
  versions: 'document',
  messages: 'activity',
  reports: 'reports',
  queue: 'queue',
  failures: 'failures',
  activity: 'activity',
  contracts: 'contracts',
  rules: 'rules',
  mappings: 'mappings',
};

interface NavModelItem {
  nav: string;
  title: string;
  hash?: string;
  page?: Route['page'];
  initial?: string;
}

const PRIMARY: NavModelItem[] = [
  { nav: 'home', title: 'Home', page: 'home' },
  { nav: 'flows', title: 'Flows', page: 'flows' },
  { nav: 'systems', title: 'Systems', page: 'systems' },
  { nav: 'approvals', title: 'Approvals', page: 'approvals' },
  { nav: 'messages', title: 'Messages', page: 'messages' },
];

const MORE: NavModelItem[] = [
  { nav: 'reports', title: 'Reports', page: 'reports' },
  { nav: 'queue', title: 'Queue', page: 'queue' },
  { nav: 'versions', title: 'Versions', page: 'versions' },
];

export function Shell({ roles, tenant, env, version = 'v0.1', page, children }: ShellProps): React.ReactElement {
  const [navOpen, setNavOpen] = useState(false);
  const theme = useConsoleTheme();
  const dash = useDashboardCounts();

  const approvalsCount = dash?.counts?.pendingApproval ?? 0;
  const toggleNav = useCallback(() => setNavOpen((v) => !v), []);
  const closeNav = useCallback(() => setNavOpen(false), []);

  async function doLogout() {
    try {
      const token = readCookie('XSRF-TOKEN');
      const headers = new Headers();
      if (token) headers.set('X-XSRF-TOKEN', token);
      await fetch('/logout', { method: 'POST', credentials: 'include', headers });
    } catch { /* ignore */ }
    window.location.hash = '#/login';
  }

  function renderGroup(item: NavModelItem) {
    const active = !!item.page && item.page === page;
    return (
      <NavItem
        key={item.nav}
        label={item.title}
        href={item.hash ?? ('#/' + item.nav)}
        active={active}
        icon={NAV_ICONS[item.nav] ?? 'document'}
        data-nav={item.nav}
        data-initial={item.initial}
        aria-current={active ? 'page' : undefined}
      >
        {item.title}
        {item.nav === 'approvals' && approvalsCount ? (
          <span data-nav-count>{approvalsCount}</span>
        ) : null}
      </NavItem>
    );
  }

  const showGroups = !roles || roles.length === 0 ? PRIMARY : [...PRIMARY];
  const navNode = (
    <>
      <LogoMark label="iPaaS Console" />
      <nav aria-label="Main" data-nav-group="primary">
        {PRIMARY.map((it) => renderGroup(it))}
      </nav>
      <nav aria-label="More" data-nav-group="more">
        <span data-nav-group-heading>More</span>
        {MORE.map((it) => renderGroup(it))}
      </nav>
      <div data-nav-footer>
        <button type="button" data-action="toggle-theme" onClick={theme.toggleMode}>
          {theme.mode === 'dark' ? 'Light' : 'Dark'} mode
        </button>
        <span>{version}</span>
      </div>
    </>
  );
  void showGroups;

  return (
    <AppShell navOpen={navOpen} onNavClose={closeNav} sidebar={navNode} topbar={
      <Topbar product="iPaaS Console" onMenu={toggleNav}>
        <button type="button" data-action="toggle-nav" onClick={toggleNav} aria-label="Open navigation">
          Menu
        </button>
        <span className="chip" data-session-tenant>
          {tenant ?? '—'}
        </span>
        <EnvBadge env={env ?? 'dev'} />
        <button type="button" data-action="toggle-theme" onClick={theme.toggleMode}>
          {theme.mode === 'dark' ? 'Light' : 'Dark'} mode
        </button>
        <span data-action="account">
          <UserChip name={tenant ?? 'Guest'} />
        </span>
        <button type="button" data-action="logout" onClick={doLogout}>
          Logout
        </button>
      </Topbar>
    }>
      <div className="ip-main" data-main>
        {children}
      </div>
    </AppShell>
  );
}
