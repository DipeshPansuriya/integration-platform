import { useState } from 'react';
import type { ChangeEvent, ReactElement, ReactNode } from 'react';
import {
  Button, Switch, Field, Input, PageHeader, Panel, Select,
  Drawer, DataTable, KeyValue, DecisionTable, MappingCanvas, Callout
} from '../../ds/components/index.js';
import './mappings.css';

export interface MappingRowVM {
  name: string;
  sourceFormat: string;
  targetFormat: string;
  fields: number;
  usedBy: string | null;
  updated: string;
}

export interface MappingDetailVM {
  name: string;
  sourceFormat: string;
  targetFormat: string;
  updated?: string;
  fields: { source: string; target: string; transform?: string }[];
  canvas?: { source?: string; target?: string; transform?: string; height?: number } | null;
}

export interface MappingDraft {
  name: string;
  sourceFormat: string;
  targetFormat: string;
  autoMap: boolean;
  sampleData: boolean;
  contract?: string | null;
}

export interface MappingsScreenProps {
  rows?: MappingRowVM[];
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  onRowSelect?: (row: MappingRowVM) => void;
  detailOpen?: boolean;
  detail?: MappingDetailVM | null;
  detailError?: string | null;
  onDetailClose?: () => void;
  onTestMapping?: () => void;
  onEditMapping?: () => void;
  newOpen?: boolean;
  onNewOpen?: () => void;
  onNewClose?: () => void;
  saving?: boolean;
  onMappingCreate?: (draft: MappingDraft) => void;
  contracts?: { name: string; format?: string }[] | null;
}

/** Drawer.Section is a runtime-only export (Drawer.d.ts declares only the Drawer function). */
interface DrawerSectionProps { title?: string; children?: ReactNode }
const DrawerSection = (Drawer as unknown as { Section?: (props: DrawerSectionProps) => ReactElement }).Section
  ?? ((props: DrawerSectionProps) => <div {...props} />);

const DEFAULT_DRAFT: MappingDraft = {
  name: '', sourceFormat: 'XML', targetFormat: 'JSON', autoMap: true, sampleData: false, contract: null
};

const FORMAT_OPTIONS = ['XML', 'JSON', 'CSV', 'EDIFACT'];

export default function MappingsScreen(props: MappingsScreenProps) {
  const {
    rows, loading, error, onRetry, onRowSelect,
    detailOpen, detail, detailError, onDetailClose, onTestMapping, onEditMapping,
    newOpen, onNewOpen, onNewClose, saving, onMappingCreate,
    contracts
  } = props;

  const [draft, setDraft] = useState<MappingDraft>({ ...DEFAULT_DRAFT });
  const openNew = () => { setDraft({ ...DEFAULT_DRAFT }); onNewOpen?.(); };
  const patch = (part: Partial<MappingDraft>) => setDraft((prev) => ({ ...prev, ...part }));

  const columns = [
    { key: 'name', header: 'Mapping', mono: true },
    { key: 'sourceFormat', header: 'Source' },
    { key: 'targetFormat', header: 'Target' },
    { key: 'fields', header: 'Fields', numeric: true },
    { key: 'usedBy', header: 'Used By' },
    { key: 'updated', header: 'Updated' }
  ];

  return (
    <div className="mappings-screen">
      <PageHeader
        title="Mappings"
        subtitle="Source-to-target field mappings"
        actions={
          <Button variant="primary" icon="plus" onClick={openNew}>New Mapping</Button>
        }
      />

      {error ? (
        <div data-region="error">
          <Callout tone="err" title="Could not load mappings">
            {error}
          </Callout>
          <div className="lane-c-colrow">
            <Button variant="secondary" icon="refresh" onClick={() => onRetry?.()}>Retry</Button>
          </div>
        </div>
      ) : loading ? (
        <div data-region="loading" className="lane-c-region">
          <div className="lane-c-empty">Loading mappings …</div>
        </div>
      ) : !rows || rows.length === 0 ? (
        <div data-region="empty" className="lane-c-region">
          <div className="lane-c-empty">
            <p className="lane-c-empty-title">No mappings yet</p>
            <p className="lane-c-note">Create a field mapping to get started.</p>
            <div className="lane-c-colrow">
              <Button variant="secondary" onClick={openNew}>New Mapping</Button>
            </div>
          </div>
        </div>
      ) : (
        <div data-region="table" className="lane-c-region">
          <Panel title="Mappings">
            <DataTable columns={columns} rows={rows} rowKey="name" onRowClick={(r: MappingRowVM) => onRowSelect?.(r)} />
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
              <Button variant="secondary" onClick={() => onTestMapping?.()}>Test Mapping</Button>
              <Button variant="primary" onClick={() => onEditMapping?.()}>Edit Mapping</Button>
            </>
          }
        >
          {detailError ? (
            <div className="lane-c-note">{detailError}</div>
          ) : detail ? (
            <>
              <DrawerSection title="Mapping Info">
                <div className="lane-c-kv-col">
                  <KeyValue label="Source format" value={detail.sourceFormat} />
                  <KeyValue label="Target format" value={detail.targetFormat} />
                  <KeyValue label="Updated" value={detail.updated} />
                </div>
              </DrawerSection>
              <DrawerSection title="Field Map">
                <DecisionTable
                  columns={[
                    { key: 'source', header: 'Source field' },
                    { key: 'target', header: 'Target field' },
                    { key: 'transform', header: 'Transform' }
                  ]}
                  rows={detail.fields}
                />
              </DrawerSection>
              <DrawerSection title="Canvas Preview">
                <MappingCanvas
                  source={detail.canvas?.source}
                  target={detail.canvas?.target}
                  transform={detail.canvas?.transform}
                  height={detail.canvas?.height}
                />
              </DrawerSection>
            </>
          ) : null}
        </Drawer>
      )}

      {newOpen && (
        <Drawer
          title="New Mapping"
          open
          onClose={() => onNewClose?.()}
          footer={
            <>
              <Button variant="secondary" onClick={() => onNewClose?.()}>Cancel</Button>
              <Button variant="primary" disabled={saving} onClick={() => onMappingCreate?.(draft)}>Create Mapping</Button>
            </>
          }
        >
          <DrawerSection title="Details">
            <div className="lane-c-colrow">
              <Field label="Mapping name">
                <Input
                  code
                  placeholder="Mapping name"
                  value={draft.name}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => patch({ name: e.target.value })}
                />
              </Field>
            </div>
            <div className="lane-c-colrow">
              <Field label="Source format">
                <Select
                  value={draft.sourceFormat}
                  options={FORMAT_OPTIONS}
                  onChange={(e: ChangeEvent<HTMLSelectElement>) => patch({ sourceFormat: e.target.value })}
                />
              </Field>
            </div>
            <div className="lane-c-colrow">
              <Field label="Target format">
                <Select
                  value={draft.targetFormat}
                  options={FORMAT_OPTIONS}
                  onChange={(e: ChangeEvent<HTMLSelectElement>) => patch({ targetFormat: e.target.value })}
                />
              </Field>
            </div>
            <div className="lane-c-colrow">
              <Field label="Base contract">
                <Select
                  value={draft.contract ?? ''}
                  options={(contracts ?? []).map((c) => c.name)}
                  onChange={(e: ChangeEvent<HTMLSelectElement>) => patch({ contract: e.target.value || null })}
                />
              </Field>
            </div>
          </DrawerSection>
          <DrawerSection title="Options">
            <div className="lane-c-colrow">
              <Switch checked={draft.autoMap} onChange={(on) => patch({ autoMap: on })} label="Auto-map matching fields" />
            </div>
            <div className="lane-c-colrow">
              <Switch checked={draft.sampleData} onChange={(on) => patch({ sampleData: on })} label="Include sample data" />
            </div>
          </DrawerSection>
        </Drawer>
      )}
    </div>
  );
}