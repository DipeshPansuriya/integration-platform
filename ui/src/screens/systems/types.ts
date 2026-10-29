export type SystemStatus = 'Healthy' | 'Slow' | 'Down' | 'Unknown';

export interface SystemRow {
  id: string;
  name: string;
  code: string;
  status: SystemStatus;
  protocol: string;
  latencyMs?: number;
  lastCheck?: string;
  /** Flow names/codes that reference this system (source or destination), derived client-side. */
  usedBy?: string[];
}

/** Full detail for the System detail drawer (extends the grid row). */
export interface SystemDetailRow extends SystemRow {
  auth?: string;
  baseUrl?: string;
  timeoutMs?: number;
  direction?: string;
}

/** 24h performance band in the System detail drawer. */
export interface SystemKpis {
  avgLatency?: string;
  uptime?: string;
  requests?: string;
  errors?: string;
}

export interface SystemEvent {
  time: string;
  text: string;
  tone?: 'ok' | 'err' | 'warn' | 'info';
}

export type SystemProtocol = 'REST API' | 'SOAP/XML' | 'EDIFACT' | 'Type-B Telex' | 'SFTP';
export type SystemDirection = 'Bidirectional' | 'Inbound Only' | 'Outbound Only';
export type SystemAuthMethod = 'HMAC Signature' | 'OAuth 2.0' | 'API Key' | 'Certificate (mTLS)' | 'Basic Auth';
export type HealthInterval = 'Every 30 seconds' | 'Every minute' | 'Every 5 minutes';

export interface SystemDraft {
  name: string;
  code: string;
  protocol: SystemProtocol;
  direction: SystemDirection;
  baseUrl: string;
  timeoutMs: number;
  retryLimit: number;
  authMethod: SystemAuthMethod;
  /** Write-only inputs — the screen never echoes a stored secret. */
  authClientId: string;
  authClientSecret: string;
  authApiKey: string;
  authUsername: string;
  authPassword: string;
  /** File name only — the certificate value itself is never held in state. */
  certFileName: string;
  healthMonitoring: boolean;
  healthEndpoint: string;
  healthInterval: HealthInterval;
  tags: string[];
}

/** Credential fields start EMPTY even when a seed draft carries stored values. */
export const DEFAULT_SYSTEM_DRAFT: SystemDraft = {
  name: '',
  code: '',
  protocol: 'REST API',
  direction: 'Bidirectional',
  baseUrl: '',
  timeoutMs: 30000,
  retryLimit: 3,
  authMethod: 'HMAC Signature',
  authClientId: '',
  authClientSecret: '',
  authApiKey: '',
  authUsername: '',
  authPassword: '',
  certFileName: '',
  healthMonitoring: true,
  healthEndpoint: '',
  healthInterval: 'Every 30 seconds',
  tags: [],
};

export type ConnectionTestState = 'idle' | 'testing' | 'ok' | 'fail';

export interface SystemsScreenProps {
  systems?: SystemRow[] | null;
  loading?: boolean;
  error?: string;
  /** True when the list shown is the last good data after a failed refresh. */
  stale?: boolean;
  onRetry?: () => void;
  onOpenSystem?: (system: SystemRow) => void;
  onRegisterSystem?: () => void;
}

export interface SystemDetailDrawerProps {
  open?: boolean;
  system?: SystemDetailRow | null;
  kpis?: SystemKpis | null;
  kpisLoading?: boolean;
  usedBy?: string[];
  usedByLoading?: boolean;
  events?: SystemEvent[] | null;
  eventsLoading?: boolean;
  eventsError?: string;
  onRetryEvents?: () => void;
  onClose?: () => void;
  onTestConnection?: (system: SystemDetailRow) => void;
  onEdit?: (system: SystemDetailRow) => void;
}

export interface RegisterSystemDrawerProps {
  open?: boolean;
  /** Seeds non-secret fields only — credential inputs always start empty. */
  draft?: SystemDraft | null;
  testState?: ConnectionTestState;
  testMessage?: string;
  onClose?: () => void;
  onTestConnection?: (draft: SystemDraft) => void;
  onRegister?: (draft: SystemDraft) => void;
}