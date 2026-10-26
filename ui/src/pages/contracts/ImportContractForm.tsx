// T-160 (E5 lane D, E5-F2-S1): import a contract version from an OpenAPI document or a JSON
// Schema. The document goes to the API as typed; a document that does not parse is refused with
// the parser's own errors, all listed, and nothing is stored.
// Row 310 lane C (T101): rendered inside the shared Drawer (its title names the form); the parser
// errors stay one danger alert listing every line verbatim, and the drawer stays open with values kept.

import { useEffect, useState, type FormEvent } from 'react';
import { CONTRACT_ROLES, importContractVersion, type ContractFormat, type ContractRole } from '../../api/contracts';
import { listSystems, type SystemView } from '../../api/systems';
import { useDrawerSaved } from '../../ui/Drawer';
import { refusalWords } from '../../ui/refusal-words';
import { roleWord } from './contracts-view';

export function ImportContractForm({ onImported }: { onImported: () => Promise<void> }) {
  const [systems, setSystems] = useState<SystemView[]>([]);
  const [system, setSystem] = useState('');
  const [role, setRole] = useState<ContractRole>('TARGET_REQUEST');
  const [versionLabel, setVersionLabel] = useState('');
  const [format, setFormat] = useState<ContractFormat>('OPENAPI');
  const [schemaName, setSchemaName] = useState('');
  const [document, setDocument] = useState('');
  const [imported, setImported] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<{ error: string; parserErrors: string[] } | null>(null);
  const [busy, setBusy] = useState(false);
  const [systemsRefused, setSystemsRefused] = useState<number | null>(null);
  const markSaved = useDrawerSaved(); // row 372 M4: only an accepted import lifts the drawer's discard guard

  useEffect(() => {
    void listSystems().then((result) => {
      if (result.ok) setSystems(result.data);
      else setSystemsRefused(result.status);
    });
  }, []);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setImported(null);
    setRefusal(null);
    try {
      const result = await importContractVersion({
        system,
        role,
        versionLabel: versionLabel.trim(),
        format,
        document,
        ...(schemaName.trim() ? { schemaName: schemaName.trim() } : {})
      });
      if (!result.ok) {
        const error = result.error ?? `The API refused the import (HTTP ${result.status}).`;
        setRefusal({ error, parserErrors: (result.errors ?? []).filter((line) => line !== result.error) });
        return;
      }
      markSaved();
      setImported(`${result.data.system ?? system} ${result.data.versionLabel ?? versionLabel} (${result.data.status ?? 'DRAFT'})`);
      await onImported();
    } finally {
      setBusy(false);
    }
  };

  return (
    <form data-form="import-contract" onSubmit={onSubmit}>
      <label>
        System
        <select data-field="contract-system" value={system} onChange={(e) => setSystem(e.target.value)}>
          <option value="">Choose a system</option>
          {systems.map((row) => (
            <option key={row.id} value={row.code}>
              {row.code} - {row.name}
            </option>
          ))}
        </select>
      </label>
      {systemsRefused !== null && (
        <p role="alert" className="contracts-alert contracts-alert-danger" data-refusal="systems-list-failed">
          {refusalWords(systemsRefused, 'the systems list')} Without it no system can be chosen.
        </p>
      )}
      <label>
        Role
        <select data-field="contract-role" value={role} onChange={(e) => setRole(e.target.value as ContractRole)}>
          {CONTRACT_ROLES.map((value) => (
            <option key={value} value={value}>
              {roleWord(value)}
            </option>
          ))}
        </select>
      </label>
      <label>
        Version (major.minor)
        <input data-field="version-label" placeholder="2.0" value={versionLabel} onChange={(e) => setVersionLabel(e.target.value)} />
      </label>
      <label>
        Format
        <select data-field="contract-format" value={format} onChange={(e) => setFormat(e.target.value as ContractFormat)}>
          <option value="OPENAPI">OpenAPI 3.x (JSON or YAML)</option>
          <option value="JSON_SCHEMA">JSON Schema (draft 2020-12)</option>
        </select>
      </label>
      {format === 'OPENAPI' && (
        <label>
          Component schema (when the document has more than one)
          <input data-field="schema-name" value={schemaName} onChange={(e) => setSchemaName(e.target.value)} />
        </label>
      )}
      <label>
        Document
        <textarea data-field="contract-document" rows={10} value={document} onChange={(e) => setDocument(e.target.value)} />
      </label>
      <button type="submit" data-action="import-contract" disabled={busy}>
        Import version
      </button>
      {imported && <p role="status" className="contracts-alert contracts-alert-success" data-imported="true">Imported {imported}.</p>}
      {refusal && (
        <div role="alert" className="contracts-alert contracts-alert-danger" data-refusal="import-refused">
          <p>Refused: {refusal.error} Nothing was stored.</p>
          {refusal.parserErrors.length > 0 && (
            <ul>
              {refusal.parserErrors.map((line, index) => (
                <li key={`${index}-${line}`} data-parser-error={index}>
                  {line}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </form>
  );
}
