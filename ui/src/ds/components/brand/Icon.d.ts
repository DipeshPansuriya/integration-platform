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

export interface IconProps { name: IconName; /** px, or [width, height]. */ size?: number | [number, number]; strokeWidth?: number; /** Gives the icon an accessible name; omit for decorative icons. */ title?: string; className?: string; style?: React.CSSProperties }
export declare function Icon(props: IconProps): React.ReactElement | null;
