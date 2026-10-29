// Row 571 (PLATFORM lane): console theme runtime mirrored here, typed. The DS
// ThemePicker.d.ts may not be edited (coordinator scope), so useConsoleTheme is re-declared
// locally with the same behaviour: localStorage keys ip-t / ip-m, data-accent + data-theme on <html>.
// Additive browser-only: guards against non-dom environments.

import { useCallback, useEffect, useState } from 'react';

export const ACCENTS = ['indigo', 'emerald', 'rose', 'amber', 'cyan', 'violet'] as const;
export type Accent = (typeof ACCENTS)[number];
export type ThemeMode = 'light' | 'dark';

const ACCENT_KEY = 'ip-t';
const MODE_KEY = 'ip-m';

function systemMode(): ThemeMode {
  try {
    if (typeof window === 'undefined' || !window.matchMedia) return 'light';
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function readAccent(fallback: Accent): Accent {
  try {
    if (typeof window === 'undefined') return fallback;
    const v = window.localStorage.getItem(ACCENT_KEY);
    return v && (ACCENTS as readonly string[]).includes(v) ? (v as Accent) : fallback;
  } catch {
    return fallback;
  }
}

function readMode(fallback?: ThemeMode): ThemeMode {
  try {
    if (typeof window !== 'undefined') {
      const v = window.localStorage.getItem(MODE_KEY);
      if (v === 'light' || v === 'dark') return v;
    }
  } catch {
    /* storage blocked */
  }
  return fallback ?? systemMode();
}

export interface ConsoleTheme {
  accent: Accent;
  mode: ThemeMode;
  setAccent: (a: Accent) => void;
  setMode: (m: ThemeMode) => void;
  toggleMode: () => void;
}


import * as React from 'react';

export function useConsoleTheme(options: {
  accent?: Accent;
  mode?: ThemeMode;
  target?: React.MutableRefObject<HTMLElement | null>;
  persist?: boolean;
} = {}): ConsoleTheme {
  const { accent: accent0 = 'indigo', mode: mode0, target, persist = true } = options;
  const [accent, setAccentState] = useState<Accent>(() => readAccent(accent0));
  const [mode, setModeState] = useState<ThemeMode>(() => readMode(mode0));

  useEffect(() => {
    if (typeof document === 'undefined') return;
    const el =
      (target && target.current) ||
      (document.documentElement as HTMLElement | null);
    if (!el) return;
    el.setAttribute('data-accent', accent);
    el.setAttribute('data-theme', mode);
  }, [accent, mode, target]);

  useEffect(() => {
    if (!persist || typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(ACCENT_KEY, accent);
      window.localStorage.setItem(MODE_KEY, mode);
    } catch {
      /* storage full / blocked */
    }
  }, [accent, mode, persist]);

  const setAccent = useCallback((next: Accent) => setAccentState(next), []);
  const setMode = useCallback((next: ThemeMode) => setModeState(next), []);
  const toggleMode = useCallback(
    () => setModeState((m) => (m === 'dark' ? 'light' : 'dark')),
    []
  );
  return { accent, mode, setAccent, setMode, toggleMode };
}
