// Row 572 S0 (spec 4.7, section 5): the one header every screen can use - the screen's ONE <h1> (tabIndex -1 so the shell
// can move focus to it on a route change), the breadcrumb above it, and an optional "Next" line (the one next step, in words).
// Hook-free. Nothing here is a security control.
import type { ReactNode } from 'react';
import { Breadcrumb, type Crumb } from './Breadcrumb';

export function PageHeader({ title, breadcrumb, next }: { title: string; breadcrumb?: Crumb[]; next?: ReactNode }) {
  return (
    <header className="page-header">
      {breadcrumb && breadcrumb.length > 0 && <Breadcrumb items={breadcrumb} />}
      <h1 tabIndex={-1} data-page-title="true">{title}</h1>
      {next ? (
        <p className="page-next" data-page-next="true">
          <strong>Next:</strong> {next}
        </p>
      ) : null}
    </header>
  );
}
