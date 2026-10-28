import * as React from 'react';
import { cx } from '../_shared/util.js';
import { LogoMark } from '../brand/LogoMark.jsx';
import { NavItem } from './NavItem.jsx';

export const DEFAULT_NAV = [
  {
    title: 'Operations',
    items: [
      { id: 'dashboard', label: 'Dashboard', icon: 'dashboard' },
      { id: 'queue', label: 'Queue', icon: 'queue', count: 24 },
      { id: 'failures', label: 'Failures', icon: 'failures', count: 3, countTone: 'danger' },
      { id: 'activity', label: 'Activity', icon: 'activity' },
      { id: 'reports', label: 'Reports', icon: 'reports' },
    ],
  },
  {
    title: 'Configuration',
    items: [
      { id: 'flows', label: 'Flows', icon: 'flows' },
      { id: 'systems', label: 'Systems', icon: 'systems' },
      { id: 'contracts', label: 'Contracts', icon: 'contracts' },
      { id: 'rules', label: 'Rules', icon: 'rules' },
      { id: 'mappings', label: 'Mappings', icon: 'mappings' },
    ],
  },
];

/** The 248px sidebar: logo block, grouped navigation, footer slot pinned to the bottom. */
export function Sidebar({ product = 'iPaaS Console', version, groups = DEFAULT_NAV, active, onNavigate, footer, className }) {
  return (
    <aside className={cx('ip-sidebar', className)}>
      <div className="ip-sb-logo">
        <LogoMark size={32} label={product} />
        <div>
          <div className="ip-sb-title">{product}</div>
          {version && <div className="ip-sb-ver">{version}</div>}
        </div>
      </div>
      <nav aria-label="Main">
        {groups.map((g) => (
          <div className="ip-nav-group" key={g.title}>
            <h2 className="ip-nav-title">{g.title}</h2>
            <ul className="ip-nav-list">
              {g.items.map((it) => (
                <li key={it.id}>
                  <NavItem
                    icon={it.icon}
                    label={it.label}
                    count={it.count}
                    countTone={it.countTone}
                    href={it.href || '#' + it.id}
                    active={it.id === active}
                    onClick={onNavigate ? (e) => { e.preventDefault(); onNavigate(it.id); } : undefined}
                  />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
      {footer && <div className="ip-sb-foot">{footer}</div>}
    </aside>
  );
}
Sidebar.defaultGroups = DEFAULT_NAV;
