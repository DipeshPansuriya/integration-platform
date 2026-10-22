// T-159 (E5 lane D): edit a registered system's name, type and type-specific settings
// (PUT /api/v1/admin/systems/{id}; code and direction are not edited there). A refused save
// lists every error the API names and leaves the stored system as it was.

import { useState, type FormEvent } from 'react';
import { clientSettingsErrors, extraSettingsOf, formFromSettings, settingsForEdit, updateSystem, type SystemView } from '../../api/systems';
import { ERRORS_ID, SettingsFields, TypeField, ValidationErrors, type FormValues } from './SystemFields';
import { topFieldHasError } from './systems-view';
import { useDrawerSaved } from '../../ui/Drawer';

export function EditSystemForm({ system, onSaved }: { system: SystemView; onSaved: () => Promise<void> }) {
  const [name, setName] = useState(system.name);
  const [type, setType] = useState(system.type ?? '');
  const [values, setValues] = useState<FormValues>(formFromSettings(system.settings));
  const [errors, setErrors] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const markSaved = useDrawerSaved(); // row 372 M4: only an accepted save lifts the drawer's discard guard

  const extras = extraSettingsOf(type, system.settings, system.type);

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
      const result = await updateSystem(system.id, {
        name: name.trim(),
        type: typed ? type.trim() : null,
        // merged over the stored settings: keys this form does not show are sent back, never erased
        settings: typed ? settingsForEdit(type, values, system.settings, system.type) : null
      });
      if (!result.ok) {
        if (result.status === 404) setErrors(['This system does not exist for your tenant.']);
        else setErrors(result.errors?.length ? result.errors : [`The API refused the save (HTTP ${result.status}).`]);
        return;
      }
      markSaved();
      setSaved(true);
      await onSaved();
    } finally {
      setBusy(false);
    }
  };

  return (
    <form data-form="edit-system" data-system-id={system.id} onSubmit={onSubmit}>
      <p className="systems-muted" data-required-legend>
        Required fields are marked *
      </p>
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
      <TypeField value={type} onChange={setType} errors={errors} />
      <SettingsFields type={type} values={values} onChange={(field, value) => setValues((v) => ({ ...v, [field]: value }))} errors={errors} />
      {extras.length > 0 && (
        <section data-extra-settings aria-label="Other stored settings">
          <h3>Other stored settings (kept as they are)</h3>
          <dl>
            {extras.map((extra) => (
              <div key={extra.name} data-extra-setting={extra.name}>
                <dt>{extra.name}</dt>
                <dd>{extra.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
      <button type="submit" data-action="save-system" disabled={busy}>
        Save system
      </button>
      {saved && <p role="status" className="systems-alert systems-alert-success" data-saved="true">System saved.</p>}
      <ValidationErrors errors={errors} />
    </form>
  );
}
