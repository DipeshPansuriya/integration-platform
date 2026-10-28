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

export interface DropzoneProps { title?: string; hint?: string; accept?: string; multiple?: boolean; onFiles?: (files: File[]) => void }
export declare function Dropzone(props: DropzoneProps): React.ReactElement;
