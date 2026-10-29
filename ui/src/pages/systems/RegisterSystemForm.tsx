// T-159 (E5 lane D): register a system of type API, DB, MQ, File or Kafka with its type-specific
// settings (E5-F1-S1). The API validates every save and names every problem; this form shows
// them all and never pre-filters what it sends.

import { useState, type FormEvent } from 'react';
import { clientSettingsErrors, registerSystem, settingsFromForm } from '../../api/systems';
import { ERRORS_ID, SettingsFields, TypeField, ValidationErrors, type FormValues } from './SystemFields';
import { topFieldHasError } from './systems-view';
import { useDrawerSaved } from '../../ui/Drawer';

export type RegisterSystemFormInitial = {
  code?: string;
  name?: string;
  direction?: 'INBOUND' | 'OUTBOUND';
  type?: string;
  values?: FormValues;
};

export function RegisterSystemForm({ onRegistered, initial }: { onRegistered: () => Promise<void>; initial?: RegisterSystemFormInitial }) {
  const [code, setCode] = useState(initial?.code ?? '');
  const [name, setName] = useState(initial?.name ?? '');
  const [direction, setDirection] = useState<'INBOUND' | 'OUTBOUND'>(initial?.direction ?? 'OUTBOUND');
  const [type, setType] = useState(initial?.type ?? '');
  const [values, setValues] = useState<FormValues>(initial?.values ?? {});
  const [errors, setErrors] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const markSaved = useDrawerSaved(); // row 372 M4: only an accepted save lifts the drawer's discard guard

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setSaved(false);
    setErrors([]);
    try {
      const typed = type.trim() !== '';
      // the one shape the client mirrors (KAFKA): same words as the server, no round trip
      const local = clientSettingsErrors(type, values);
      if (local.length > 0) {
        setErrors(local);
        return;
      }
      const result = await registerSystem({
        code: code.trim(),
        name: name.trim(),
        direction,
        type: typed ? type.trim() : null,
        settings: typed ? settingsFromForm(type, values) : null
      });
      if (!result.ok) {
        setErrors(result.errors?.length ? result.errors : [`The API refused the save (HTTP ${result.status}).`]);
        return;
      }
      markSaved();
      setSaved(true);
      setCode('');
      setName('');
      setValues({});
      await onRegistered();
    } finally {
      setBusy(false);
    }
  };

  return (
    <form data-form="register-system" onSubmit={onSubmit}>
      <p className="systems-muted" data-required-legend>
        Required fields are marked *
      </p>
      <label>
        Code *
        <input
          id="sys-field-code"
          data-field="system-code"
          value={code}
          aria-required
          aria-invalid={topFieldHasError(errors, 'code') || undefined}
          aria-describedby={topFieldHasError(errors, 'code') ? ERRORS_ID : undefined}
          onChange={(e) => setCode(e.target.value)}
        />
      </label>
      <label>
        Name *
        <input
          id="sys-field-name"
          data-field="system-name"
          value={name}
          aria-required
          aria-invalid={topFieldHasError(errors, 'name') || undefined}
          aria-describedby={topFieldHasError(errors, 'name') ? ERRORS_ID : undefined}
          onChange={(e) => setName(e.target.value)}
        />
      </label>
      <label>
        Direction *
        <select
          aria-required
          aria-invalid={topFieldHasError(errors, 'direction') || undefined}
          aria-describedby={topFieldHasError(errors, 'direction') ? ERRORS_ID : undefined}
          id="sys-field-direction"
          data-field="system-direction"
          value={direction}
          onChange={(e) => setDirection(e.target.value as 'INBOUND' | 'OUTBOUND')}
        >
          <option value="OUTBOUND">Outbound (target)</option>
          <option value="INBOUND">Inbound (source)</option>
        </select>
      </label>
      <TypeField value={type} onChange={setType} errors={errors} />
      <SettingsFields type={type} values={values} onChange={(field, value) => setValues((v) => ({ ...v, [field]: value }))} errors={errors} />
      <button type="submit" data-action="register-system" disabled={busy}>
        Register system
      </button>
      {saved && <p role="status" className="systems-alert systems-alert-success" data-saved="true">System registered.</p>}
      <ValidationErrors errors={errors} />
    </form>
  );
}
