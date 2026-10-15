// T-159 (E5 lane D): the pieces the register and edit forms share - the type field (the four
// registered types offered, any text accepted so the API stays the one authority on what a type
// is), the type's own settings fields, and the list of EVERY validation error the API names.

import { SYSTEM_TYPES, TYPE_LABELS, settingsFieldsOf } from '../../api/systems';
import { errorAnchor } from './systems-view';

export type FormValues = Record<string, string | boolean>;

export function TypeField({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <label>
      Type
      <input id="sys-field-type" list="system-types" data-field="system-type" value={value} onChange={(e) => onChange(e.target.value)} />
      <datalist id="system-types">
        {SYSTEM_TYPES.map((type) => (
          <option key={type} value={type}>
            {TYPE_LABELS[type]}
          </option>
        ))}
      </datalist>
    </label>
  );
}

export function SettingsFields({
  type,
  values,
  onChange
}: {
  type: string;
  values: FormValues;
  onChange: (name: string, value: string | boolean) => void;
}) {
  const fields = settingsFieldsOf(type);
  if (fields.length === 0) return null;
  return (
    <fieldset data-settings-for={type.trim()}>
      <legend>{type.trim()} settings</legend>
      {fields.map((field) => (
        <label key={field.name}>
          {field.label}
          {field.required ? ' *' : ''}
          {field.kind === 'boolean' ? (
            <input
              type="checkbox"
              id={`sys-setting-${field.name}`}
              data-setting={field.name}
              checked={values[field.name] === true}
              onChange={(e) => onChange(field.name, e.target.checked)}
            />
          ) : (
            <input
              id={`sys-setting-${field.name}`}
              data-setting={field.name}
              inputMode={field.kind === 'number' ? 'numeric' : undefined}
              value={typeof values[field.name] === 'string' ? (values[field.name] as string) : ''}
              onChange={(e) => onChange(field.name, e.target.value)}
            />
          )}
        </label>
      ))}
    </fieldset>
  );
}

/** Every error of a refused save, each on its own line - never only the first (E5-F1-S1). Row 310
 * lane C (T101): one bordered danger alert, each error an anchor to the field it names (when it names one). */
export function ValidationErrors({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null;
  return (
    <div role="alert" className="systems-alert systems-alert-danger" data-validation-errors={errors.length}>
      <p>The save was refused:</p>
      <ul>
        {errors.map((error, index) => {
          const anchor = errorAnchor(error);
          return (
            <li key={`${index}-${error}`} data-validation-error={index}>
              {anchor ? <a href={`#${anchor}`} onClick={(event) => focusField(event, anchor)}>{error}</a> : error}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** The anchor moves focus to its field without changing the address (the hash is the screen's route). */
function focusField(event: { preventDefault: () => void }, id: string) {
  event.preventDefault();
  document.getElementById(id)?.focus();
}
