import { useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import {
  Button, Field, Input, Select, Switch, Dropzone, PageHeader, Panel,
  Drawer, DataTable, StatusBadge, KeyValue, CodePreview, TagList, Callout
} from '../../ds/components/index.js';
import './contracts.css';

export type ContractFormat = 'XML' | 'JSON' | 'EDIFACT' | 'CSV';

export interface ContractRowVM {
  name: string;
  version: string;
  format: ContractFormat;
  flowsUsing: number;
  updated: string;
}

export interface ImportErrorVM {
  path: string;
  message: string;
}

export interface ImportDraft {
  name: string;
  version: string;
  format: ContractFormat;
  autoGenerateRules: boolean;
  createSampleMapping: boolean;
}

export interface ContractDetailVM {
  name: string;
  version: string;
  format: ContractFormat;
  updated?: string;
  usedBy: string[];
  schemaCode: string;
  schemaLanguage: 'xml' | 'json' | 'plain';
  validationRules: { field: string; rule: string }[];
}

export interface ContractsScreenProps {
  rows?: ContractRowVM[];
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  onRowSelect?: (row: ContractRowVM) => void;
  importOpen?: boolean;
  onImportOpen?: () => void;
  onImportClose?: () => void;
  importFileName?: string | null;
  importUploading?: boolean;
  importErrors?: ImportErrorVM[] | null;
  onSchemaUpload?: (files: File[]) => void;
  onImportSubmit?: (draft: ImportDraft) => void;
  detailOpen?: boolean;
  detail?: ContractDetailVM | null;
  detailLoading?: boolean;
  detailError?: string | null;
  onDetailClose?: () => void;
  onDownloadSchema?: () => void;
  onEditContract?: () => void;
  /** Optional data-action on the header Import Contract button. */
  importAction?: string;
  /** Optional data-action on the empty-state Import Contract button. */
  emptyImportAction?: string;
}

/** Drawer.Section is a runtime-only export (Drawer.d.ts declares only the Drawer function). */
interface DrawerSectionProps { title?: string; children?: ReactNode }
const DrawerSection = (Drawer as unknown as { Section?: (props: DrawerSectionProps) => ReactElement }).Section
  ?? ((props: DrawerSectionProps) => <div {...props} />);

const DEFAULT_DRAFT: ImportDraft = {
  name: '', version: '1.0', format: 'XML', autoGenerateRules: true, createSampleMapping: false
};

export default function ContractsScreen(props: ContractsScreenProps) {
  const {
    rows, loading, error, onRetry, onRowSelect,
    importOpen, onImportOpen, onImportClose,
    importFileName, importUploading, importErrors, onSchemaUpload, onImportSubmit,
    detailOpen, detail, detailLoading, detailError, onDetailClose,
    onDownloadSchema, onEditContract, importAction, emptyImportAction
  } = props;

  const [draft, setDraft] = useState<ImportDraft>({ ...DEFAULT_DRAFT });

  const patch = (part: Partial<ImportDraft>) => setDraft({ ...draft, ...part });

  const columns = [
    { key: 'name', header: 'Contract', mono: true },
    { key: 'version', header: 'Version', mono: true },
    { key: 'format', header: 'Format', render: (r: ContractRowVM) => <StatusBadge status={r.format} /> },
    { key: 'flowsUsing', header: 'Flows Using', numeric: true },
    { key: 'updated', header: 'Updated' }
  ];

  const submitImport = () => { onImportSubmit?.(draft); };
  const downloadLabel = detail?.format === 'XML' ? 'Download XSD' : `Download ${detail?.format}`;

  return (
    <div className="contracts-screen">
      <PageHeader
        title="Contracts"
        subtitle="Message schema definitions"
        actions={
          <Button variant="primary" icon="plus" data-action={importAction} onClick={() => onImportOpen?.()}>Import Contract</Button>
        }
      />

      {error ? (
        <div data-region="error">
          <Callout tone="err" title="Could not load contracts">
            {error}
          </Callout>
          <div className="lane-c-colrow">
            <Button variant="secondary" icon="refresh" onClick={() => onRetry?.()}>Retry</Button>
          </div>
        </div>
      ) : loading ? (
        <div data-region="loading" className="lane-c-region">
          <div className="lane-c-empty">Loading contracts&hellip;</div>
        </div>
      ) : !rows || rows.length === 0 ? (
        <div data-region="empty" className="lane-c-region">
          <div className="lane-c-empty">
            <p className="lane-c-empty-title">No contracts yet</p>
            <p className="lane-c-note">Import a contract schema to get started.</p>
            <div className="lane-c-colrow">
              <Button variant="secondary" data-action={emptyImportAction} onClick={() => onImportOpen?.()}>Import Contract</Button>
            </div>
          </div>
        </div>
      ) : (
        <div data-region="table" className="lane-c-region">
          <Panel title="Contracts">
            <DataTable columns={columns} rows={rows} rowKey="name" onRowClick={(r: ContractRowVM) => onRowSelect?.(r)} />
          </Panel>
        </div>
      )}

      {detailOpen && (
        <Drawer
          title={detail?.name ?? ''}
          open
          onClose={() => onDetailClose?.()}
          footer={
            <>
              <Button variant="secondary" onClick={() => onDetailClose?.()}>Close</Button>
              <Button variant="secondary" onClick={() => onDownloadSchema?.()}>{downloadLabel}</Button>
              <Button variant="primary" onClick={() => onEditContract?.()}>Edit Contract</Button>
            </>
          }
        >
          {detailError ? (
            <Callout tone="err" title="Could not load contract">{detailError}</Callout>
          ) : detailLoading ? (
            <div data-region="detail-loading" className="lane-c-region">
              <div className="lane-c-empty">Loading contract&hellip;</div>
            </div>
          ) : detail ? (
            <>
              <DrawerSection title="Contract Info">
                <div className="lane-c-kv-col">
                  <KeyValue label="Name" value={detail.name} mono />
                  <KeyValue label="Version" value={detail.version} mono />
                  <KeyValue label="Format" value={detail.format} />
                  <KeyValue label="Updated" value={detail.updated} />
                </div>
              </DrawerSection>
              <DrawerSection title="Used By">
                <TagList tags={detail.usedBy} mono />
              </DrawerSection>
              <DrawerSection title="Schema Preview">
                <CodePreview code={detail.schemaCode} language={detail.schemaLanguage} />
              </DrawerSection>
              {detail.validationRules.length > 0 && (
                <DrawerSection title="Validation Rules">
                  {detail.validationRules.map((v) => (
                    <KeyValue key={`${v.field}-${v.rule}`} label={v.field} value={v.rule} mono />
                  ))}
                </DrawerSection>
              )}
            </>
          ) : null}
        </Drawer>
      )}

      {importOpen && (
        <Drawer
          title="Import Contract"
          open
          onClose={() => onImportClose?.()}
          footer={
            <>
              <Button variant="secondary" onClick={() => onImportClose?.()}>Cancel</Button>
              <Button variant="primary" disabled={importUploading} onClick={submitImport}>Import</Button>
            </>
          }
        >
          <DrawerSection title="Upload Schema">
            <Dropzone
              title="Upload schema"
              hint="XSD or JSON Schema file"
              accept=".xsd,.json"
              onFiles={(files) => onSchemaUpload?.(files)}
            />
            {importFileName && <p className="lane-c-note lane-c-file">{importFileName}</p>}
            {importUploading && <p className="lane-c-note">Uploading&hellip;</p>}
            {importErrors && importErrors.length > 0 && (
              <Callout tone="err" title="Validation errors">
                <ul className="lane-c-errors">
                  {importErrors.map((e) => (
                    <li key={`${e.path}-${e.message}`}>{e.path}: {e.message}</li>
                  ))}
                </ul>
              </Callout>
            )}
          </DrawerSection>
          <DrawerSection title="Contract Details">
            <div className="lane-c-colrow">
              <Field label="Contract name">
                <Input
                  code
                  placeholder="Contract name"
                  value={draft.name}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => patch({ name: e.target.value })}
                />
              </Field>
            </div>
            <div className="lane-c-colrow">
              <Field label="Version">
                <Input
                  code
                  placeholder="Version"
                  value={draft.version}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => patch({ version: e.target.value })}
                />
              </Field>
            </div>
            <div className="lane-c-colrow">
              <Field label="Format">
                <Select
                  value={draft.format}
                  onChange={(e: React.ChangeEvent<HTMLSelectElement>) => patch({ format: e.target.value as ContractFormat })}
                  options={[
                    { value: 'XML', label: 'XML / XSD' },
                    { value: 'JSON', label: 'JSON Schema' },
                    { value: 'EDIFACT', label: 'EDIFACT' },
                    { value: 'CSV', label: 'CSV / Flat File' }
                  ]}
                />
              </Field>
            </div>
          </DrawerSection>
          <DrawerSection title="Options">
            <Switch
              label="Auto-generate mapping rules"
              checked={draft.autoGenerateRules}
              onChange={(on: boolean) => patch({ autoGenerateRules: on })}
            />
            <Switch
              label="Create a sample mapping"
              checked={draft.createSampleMapping}
              onChange={(on: boolean) => patch({ createSampleMapping: on })}
            />
          </DrawerSection>
        </Drawer>
      )}
    </div>
  );
}