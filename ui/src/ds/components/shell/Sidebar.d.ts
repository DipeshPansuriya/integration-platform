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

export interface NavEntry { id: string; label: string; icon: IconName; count?: number; countTone?: 'neutral' | 'danger'; href?: string }
export interface NavGroup { title: string; items: NavEntry[] }
export interface SidebarProps { product?: string; version?: string; /** Defaults to Operations + Configuration. */ groups?: NavGroup[]; active?: string; onNavigate?: (id: string) => void; footer?: React.ReactNode; className?: string }
export declare function Sidebar(props: SidebarProps): React.ReactElement;
