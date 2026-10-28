import * as React from 'react';

/** Join class names, skipping falsy values. */
export function cx(...names) {
  return names.filter(Boolean).join(' ');
}

/** Tone class for the shared tone variables (--tone, --tone-bg, --tone-t). */
export function toneClass(tone) {
  return tone ? 'ip-tone-' + tone : null;
}

/** Controlled-or-uncontrolled state helper. */
export function useControlled(value, defaultValue, onChange) {
  const [inner, setInner] = React.useState(defaultValue);
  const controlled = value !== undefined;
  const current = controlled ? value : inner;
  const set = (next) => {
    if (!controlled) setInner(next);
    if (onChange) onChange(next);
  };
  return [current, set];
}
