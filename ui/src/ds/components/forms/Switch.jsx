import * as React from 'react';
import { useControlled } from '../_shared/util.js';

/** On/off toggle with its label, as role="switch". Controlled (checked) or uncontrolled (defaultChecked). */
export function Switch({ checked, defaultChecked = false, onChange, label, id, disabled }) {
  const [on, set] = useControlled(checked, defaultChecked, onChange);
  return (
    <button type="button" role="switch" aria-checked={!!on} className="ip-switch" id={id} disabled={disabled} onClick={() => set(!on)}>
      <span className="ip-switch-track" aria-hidden="true" />
      <span className="ip-switch-label">{label}</span>
    </button>
  );
}
