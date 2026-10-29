import * as React from 'react';
import { PageHeader } from '../ds/components/shell/PageHeader.jsx';

export interface ScreenProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
}

// Hook-free render wrapper: every console screen is one <section data-screen> with a standard PageHeader.
export function Screen({ title, subtitle, actions, children, className }: ScreenProps): React.ReactElement {
  return (
    <section className={className} data-screen>
      <PageHeader title={title} subtitle={subtitle} actions={actions} />
      {children}
    </section>
  );
}
