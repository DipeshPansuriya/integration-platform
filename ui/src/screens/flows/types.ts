export type FlowStatus = 'Active' | 'Draft' | 'Error' | 'Paused';

export interface FlowRow {
  id: string;
  code: string;
  name: string;
  status: FlowStatus;
  description?: string;
  lastRun?: string;
  messagesToday?: number;
  successRate?: string;
  sourceSystem?: string;
  targetSystem?: string;
  contract?: string;
  mapping?: string;
  rule?: string;
  workers?: number;
}

/** A recent message row for the flow detail drawer (StatusBadge renders the vocabulary word). */
export interface FlowMessageRow {
  id: string;
  time: string;
  status: string;
  latency: string;
}

export type FlowTrigger = 'Message Received' | 'Scheduled (Cron)' | 'Webhook' | 'File Drop';
export type FlowRetryDelay = 'Exponential backoff' | 'Fixed 30s' | 'Fixed 60s';

export interface FlowDraft {
  name: string;
  description?: string;
  sourceSystemId?: string;
  trigger: FlowTrigger;
  contractId?: string;
  mappingId?: string;
  ruleId?: string;
  targetSystemId?: string;
  retryCount: number;
  retryDelay: FlowRetryDelay;
  deadLetterQueue: boolean;
  alertOnFailure: boolean;
}

export const DEFAULT_FLOW_DRAFT: FlowDraft = {
  name: '',
  description: '',
  sourceSystemId: '',
  trigger: 'Message Received',
  contractId: '',
  mappingId: '',
  ruleId: '',
  targetSystemId: '',
  retryCount: 3,
  retryDelay: 'Exponential backoff',
  deadLetterQueue: true,
  alertOnFailure: true,
};

export interface FlowsScreenProps {
  flows?: FlowRow[] | null;
  loading?: boolean;
  error?: string;
  /** True when the list shown is the last good data after a failed refresh. */
  stale?: boolean;
  onRetry?: () => void;
  onOpenFlow?: (flow: FlowRow) => void;
  onNewFlow?: () => void;
}

export interface FlowDetailDrawerProps {
  open?: boolean;
  flow?: FlowRow | null;
  messages?: FlowMessageRow[] | null;
  messagesLoading?: boolean;
  messagesError?: string;
  onRetryMessages?: () => void;
  onClose?: () => void;
  onPause?: (flow: FlowRow) => void;
  onEdit?: (flow: FlowRow) => void;
}

export interface FlowOption {
  id: string;
  name: string;
}

export interface NewFlowDrawerProps {
  open?: boolean;
  systems?: FlowOption[];
  contracts?: FlowOption[];
  mappings?: FlowOption[];
  rules?: FlowOption[];
  draft?: FlowDraft | null;
  onClose?: () => void;
  onSaveDraft?: (draft: FlowDraft) => void;
  onCreateDeploy?: (draft: FlowDraft) => void;
}