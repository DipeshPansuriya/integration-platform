// B29 C5: the app theme (html[data-theme]) as the mode jdm-editor's JdmConfigProvider takes.
export type JdmMode = 'light' | 'dark';
export function jdmThemeMode(value: string | undefined | null): JdmMode {
  return value === 'dark' ? 'dark' : 'light';
}
