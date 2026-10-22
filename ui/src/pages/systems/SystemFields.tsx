// T-159 (E5 lane D): the pieces the register and edit forms share - the type field (the
// registered types offered, any text accepted so the API stays the one authority on what a type
// is), the type's own settings fields, and the list of EVERY validation error the API names.

import { Fragment } from 'react';
import { SYSTEM_TYPES, TYPE_LABELS, settingsFieldsOf, settingsNoteOf } from '../../api/systems';
import { errorAnchor, fieldHasError, topFieldHasError } from './systems-view';

export type FormValues = Record<string, string | boolean>;

/** The id the invalid fields point their aria-describedby at: the list of errors below the form. */
export const ERRORS_ID = 'sys-validation-errors';

export function TypeField({ value, onChange, errors = [] }: { value: string; onChange: (value: string) => void; errors?: string[] }) {
  const invalid = topFieldHasError(errors, 'type');
  return (
    <label>
      Type *
      <input
        id="sys-field-type"
        list="system-types"
        data-field="system-type"
        value={value}
        aria-required
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? ERRORS_ID : undefined}
        onChange={(e) => onChange(e.target.value)}
      />
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
  onChange,
  errors = []
}: {
  type: string;
  values: FormValues;
  onChange: (name: string, value: string | boolean) => void;
  errors?: string[];
}) {
  const fields = settingsFieldsOf(type);
  if (fields.length === 0) return null;
  const note = settingsNoteOf(type);
  return (
    <fieldset data-settings-for={type.trim()}>
      <legend>{type.trim()} settings</legend>
      {fields.map((field) => {
        const invalid = fieldHasError(errors, field.name);
        const hintId = `sys-setting-${field.name}-hint`;
        const describedBy = [field.hint ? hintId : '', invalid ? ERRORS_ID : ''].filter(Boolean).join(' ') || undefined;
        return (
          <Fragment key={field.name}>
            <label>
              {field.label}
              {field.required ? ' *' : ''}
              {field.kind === 'boolean' ? (
                <input
                  type="checkbox"
                  id={`sys-setting-${field.name}`}
                  data-setting={field.name}
                  checked={values[field.name] === true}
                  aria-invalid={invalid || undefined}
                  aria-describedby={describedBy}
                  onChange={(e) => onChange(field.name, e.target.checked)}
                />
              ) : (
                <input
                  id={`sys-setting-${field.name}`}
                  data-setting={field.name}
                  inputMode={field.kind === 'number' ? 'numeric' : undefined}
                  value={typeof values[field.name] === 'string' ? (values[field.name] as string) : ''}
                  aria-required={field.required || undefined}
                  aria-invalid={invalid || undefined}
                  aria-describedby={describedBy}
                  onChange={(e) => onChange(field.name, e.target.value)}
                />
              )}
            </label>
            {field.hint && (
              <p id={hintId} className="systems-muted">
                {field.hint}
              </p>
            )}
          </Fragment>
        );
      })}
      {note && (
        <p className="systems-muted" data-settings-note>
          {note}
        </p>
      )}
    </fieldset>
  );
}

/** Every error of a refused save, each on its own line - never only the first (E5-F1-S1). Row 310
 * lane C (T101): one bordered danger alert, each error an anchor to the field it names (when it names one). */
export function ValidationErrors({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null;
  return (
    <div id={ERRORS_ID} role="alert" className="systems-alert systems-alert-danger" data-validation-errors={errors.length}>
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
