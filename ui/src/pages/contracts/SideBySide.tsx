// T-160 (E5 lane D, E5-F2-S1): the compared versions side by side - for example Target A v1.0
// next to Target B v2.0 - each pane read from the API with its stored schema's fields.

import { useEffect, useState } from 'react';
import { readContractVersion, schemaFields, type ContractVersion } from '../../api/contracts';

export type Compared = { id: string; key: string };

function Pane({ compared }: { compared: Compared }) {
  const [version, setVersion] = useState<ContractVersion | null>(null);
  const [refused, setRefused] = useState<number | null>(null);

  useEffect(() => {
    let current = true;
    void readContractVersion(compared.id).then((result) => {
      if (!current) return;
      if (result.ok) setVersion(result.data);
      else setRefused(result.status);
    });
    return () => {
      current = false;
    };
  }, [compared.id]);

  return (
    <section data-compare-pane={compared.key} style={{ flex: '1 1 18rem', minWidth: 0 }}>
      <h4>{compared.key.split('/').join(' - ')}</h4>
      {refused !== null && <p role="alert">The API refused reading this version (HTTP {refused}).</p>}
      {version && (
        <>
          <dl>
            <dt>Status</dt>
            <dd data-compare-status>{version.status}</dd>
            {version.changeNote && (
              <>
                <dt>Change note</dt>
                <dd data-change-note>{version.changeNote}</dd>
              </>
            )}
          </dl>
          <table>
            <thead>
              <tr>
                <th>Field</th>
                <th>Type</th>
                <th>Required</th>
              </tr>
            </thead>
            <tbody>
              {schemaFields(version.jsonSchema).map((field) => (
                <tr key={field.name} data-schema-field={field.name}>
                  <td>{field.name}</td>
                  <td>{field.type}</td>
                  <td>{field.required ? 'yes' : 'no'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}

export function SideBySide({ compared }: { compared: Compared[] }) {
  if (compared.length === 0) return null;
  return (
    <section data-side-by-side style={{ display: 'flex', flexWrap: 'wrap', gap: '1rem' }}>
      {compared.map((entry) => (
        <Pane key={entry.id} compared={entry} />
      ))}
    </section>
  );
}
