// T-159 (E5 lane D): edit a registered system's name, type and type-specific settings
// (PUT /api/v1/admin/systems/{id}; code and direction are not edited there). A refused save
// lists every error the API names and leaves the stored system as it was.

import { useState, type FormEvent } from 'react';
import { formFromSettings, settingsFromForm, updateSystem, type SystemView } from '../../api/systems';
import { SettingsFields, TypeField, ValidationErrors, type FormValues } from './SystemFields';

export function EditSystemForm({ system, onSaved }: { system: SystemView; onSaved: () => Promise<void> }) {
  const [name, setName] = useState(system.name);
  const [type, setType] = useState(system.type ?? '');
  const [values, setValues] = useState<FormValues>(formFromSettings(system.settings));
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
      const result = await updateSystem(system.id, {
        name: name.trim(),
        type: typed ? type.trim() : null,
        settings: typed ? settingsFromForm(type, values) : null
      });
      if (!result.ok) {
        if (result.status === 404) setErrors(['This system does not exist for your tenant.']);
        else setErrors(result.errors?.length ? result.errors : [`The API refused the save (HTTP ${result.status}).`]);
        return;
      }
      setSaved(true);
      await onSaved();
    } finally {
      setBusy(false);
    }
  };

  return (
    <form data-form="edit-system" data-system-id={system.id} onSubmit={onSubmit}>
      <label>
        Name
        <input id="sys-field-name" data-field="system-name" value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <TypeField value={type} onChange={setType} />
      <SettingsFields type={type} values={values} onChange={(field, value) => setValues((v) => ({ ...v, [field]: value }))} />
      <button type="submit" data-action="save-system" disabled={busy}>
        Save system
      </button>
      {saved && <p role="status" className="systems-alert systems-alert-success" data-saved="true">System saved.</p>}
      <ValidationErrors errors={errors} />
    </form>
  );
}
