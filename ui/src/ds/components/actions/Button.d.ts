import type * as React from 'react';

/** Shared tone names. accent follows data-accent; the rest are semantic. */
export type Tone = 'accent' | 'ok' | 'warn' | 'err' | 'info' | 'muted';
export type IconName =
  | 'dashboard' | 'queue' | 'failures' | 'warning' | 'activity' | 'reports' | 'flows' | 'systems' | 'contracts'
  | 'rules' | 'mappings' | 'mapping-json' | 'mapping-xml' | 'document' | 'rows' | 'menu' | 'trend' | 'check'
  | 'plus' | 'close' | 'refresh' | 'search' | 'upload' | 'sign-out' | 'sign-in' | 'grid-4' | 'chevron-down'
  | 'arrow' | 'moon' | 'sun' | 'palette';
export type Accent = 'indigo' | 'emerald' | 'rose' | 'amber' | 'cyan' | 'violet';
export type Mode = 'light' | 'dark';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  /** Variant. accent follows data-accent; secondary, danger, ghost, link. */
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost' | 'link';
  /** md is the default; sm/lg sizes used by toolbars and page headers. */
  size?: 'sm' | 'md' | 'lg';
  /** Name from IconName, optional leading icon. */
  icon?: IconName;
  /** Full-width fill. */
  block?: boolean;
}

export declare function Button(props: ButtonProps): React.ReactElement;
