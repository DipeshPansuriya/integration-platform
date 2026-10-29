// Row 575 S6: the tab links above a hub page (Reports: Queue | Daily delivery | Activity; Systems: Systems | Contracts).
// Real links, aria-current="page" on the current one; renders nothing on any other page. Hook-free.
import type { Route } from '../route-types';
import { sectionTabs } from '../section-tabs';

export function SectionTabs({ page }: { page: Route['page'] }) {
  const section = sectionTabs(page);
  if (!section) return null;
  return (
    <nav aria-label={section.label} className="op-tabs section-tabs" data-section-tabs={section.section}>
      {section.tabs.map((item) => (
        <a key={item.key} href={item.href} data-nav={item.nav} data-tab={item.key} aria-current={item.current ? 'page' : undefined}>
          {item.label}
        </a>
      ))}
    </nav>
  );
}
