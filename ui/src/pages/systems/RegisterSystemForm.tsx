// T-159 (E5 lane D): register a system of type API, DB, MQ or File with its type-specific
// settings (E5-F1-S1). The API validates every save and names every problem; this form shows
// them all and never pre-filters what it sends.

import { useState, type FormEvent } from 'react';
import { registerSystem, settingsFromForm } from '../../api/systems';
import { SettingsFields, TypeField, ValidationErrors, type FormValues } from './SystemFields';

export function RegisterSystemForm({ onRegistered }: { onRegistered: () => Promise<void> }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [direction, setDirection] = useState<'INBOUND' | 'OUTBOUND'>('OUTBOUND');
  const [type, setType] = useState('');
  const [values, setValues] = useState<FormValues>({});
  const [errors, setErrors] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setSaved(false);
    setErrors([]);
    try {
      const typed = type.trim() !== '';
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
      <h3>Register a system</h3>
      <label>
        Code
        <input data-field="system-code" value={code} onChange={(e) => setCode(e.target.value)} />
      </label>
      <label>
        Name
        <input data-field="system-name" value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        Direction
        <select
          data-field="system-direction"
          value={direction}
          onChange={(e) => setDirection(e.target.value as 'INBOUND' | 'OUTBOUND')}
        >
          <option value="OUTBOUND">Outbound (target)</option>
          <option value="INBOUND">Inbound (source)</option>
        </select>
      </label>
      <TypeField value={type} onChange={setType} />
      <SettingsFields type={type} values={values} onChange={(field, value) => setValues((v) => ({ ...v, [field]: value }))} />
      <button type="submit" data-action="register-system" disabled={busy}>
        Register system
      </button>
      {saved && <p data-saved="true">System registered.</p>}
      <ValidationErrors errors={errors} />
    </form>
  );
}
