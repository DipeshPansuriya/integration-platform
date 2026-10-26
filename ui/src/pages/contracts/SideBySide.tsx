// T-160 (E5 lane D, E5-F2-S1): the compared versions side by side - for example Target A v1.0
// next to Target B v2.0 - each pane read from the API with its stored schema's fields.
// Row 310 lane C (T101, 10-screens §6): up to two panes in a two-column grid, a sticky header per
// pane, captioned tables, and a worded "diff" hint on fields that differ from the other pane
// (phase 1 stays visual: changed type/required or a field only one side has).

import { useEffect, useState } from 'react';
import { readContractVersion, schemaFields, type ContractVersion } from '../../api/contracts';
import { refusalWords } from '../../ui/refusal-words';
import { fieldDiff, statusBadge } from './contracts-view';

export type Compared = { id: string; key: string };

type Loaded = { version: ContractVersion | null; refused: number | null };
type Field = ReturnType<typeof schemaFields>[number];

const DIFF_WORD = { changed: 'changed', 'only-here': 'only here' } as const;

function Pane({ compared, loaded, other }: { compared: Compared; loaded: Loaded | undefined; other: Field[] | undefined }) {
  const version = loaded?.version ?? null;
  const fields = version ? schemaFields(version.jsonSchema) : [];
  const diff = fieldDiff(fields, other);
  const title = compared.key.split('/').join(' - ');
  return (
    <section className="contracts-pane" data-compare-pane={compared.key}>
      <h4 className="contracts-pane-head">{title}</h4>
      <div className="contracts-pane-body">
        {!loaded && (
          <p className="contracts-muted-cell" role="status" data-compare-loading>
            Loading this version...
          </p>
        )}
        {loaded?.refused != null && (
          <p role="alert" className="contracts-pane-error" data-refusal={`compare-refused-${loaded.refused}`}>
            {refusalWords(loaded.refused, 'this version')}
          </p>
        )}
        {version && (
          <>
            <dl className="contracts-meta">
              <dt>Status</dt>
              <dd data-compare-status>{statusBadge(version.status).word}</dd>
              {version.changeNote && (
                <>
                  <dt>Change note</dt>
                  <dd data-change-note>{version.changeNote}</dd>
                </>
              )}
            </dl>
            <table className="table">
              <caption className="contracts-sr-only">Fields of {title}</caption>
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Type</th>
                  <th>Required</th>
                </tr>
              </thead>
              <tbody>
                {fields.map((field) => {
                  const mark = diff[field.name];
                  return (
                    <tr
                      key={field.name}
                      className={mark ? 'contracts-diff-row' : undefined}
                      data-schema-field={field.name}
                      data-field-diff={mark}
                    >
                      <td className="contracts-mono">
                        {field.name}
                        {mark && <span className="badge badge-info contracts-diff-word">{DIFF_WORD[mark]}</span>}
                      </td>
                      <td>{field.type}</td>
                      <td>{field.required ? 'yes' : 'no'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </>
        )}
      </div>
    </section>
  );
}

export function SideBySide({ compared }: { compared: Compared[] }) {
  const [loaded, setLoaded] = useState<Record<string, Loaded>>({});
  const ids = compared.map((entry) => entry.id).join('|');

  useEffect(() => {
    let current = true;
    // an unchecked version leaves the cache, so checking it again reads it fresh (as the remount did)
    setLoaded((was) => Object.fromEntries(Object.entries(was).filter(([id]) => compared.some((entry) => entry.id === id))));
    for (const entry of compared) {
      if (loaded[entry.id]) continue;
      void readContractVersion(entry.id).then((result) => {
        if (!current) return;
        setLoaded((was) => ({
          ...was,
          [entry.id]: result.ok ? { version: result.data, refused: null } : { version: null, refused: result.status }
        }));
      });
    }
    return () => {
      current = false;
    };
    // the compared ids are the only input; loaded is a cache, not a trigger
  }, [ids]);

  if (compared.length === 0) return null;
  const fieldsOf = (id: string) => {
    const version = loaded[id]?.version;
    return version ? schemaFields(version.jsonSchema) : undefined;
  };
  return (
    <section className="contracts-compare" data-side-by-side aria-label="Compared versions">
      {compared.map((entry) => {
        const other = compared.find((candidate) => candidate.id !== entry.id);
        return <Pane key={entry.id} compared={entry} loaded={loaded[entry.id]} other={other ? fieldsOf(other.id) : undefined} />;
      })}
    </section>
  );
}
