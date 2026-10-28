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

export interface LoginFeature { icon: IconName; label: string }
export interface LoginScreenProps { /** Use \n for the line break. */ headline?: string; copy?: string; features?: LoginFeature[]; title?: string; subtitle?: string; ssoLabel?: string; onSSO?: () => void; altLabel?: string; altIcon?: IconName; onAlt?: () => void }
export declare function LoginScreen(props: LoginScreenProps): React.ReactElement;
