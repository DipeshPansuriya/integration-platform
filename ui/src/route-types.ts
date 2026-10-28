// The console's hash-route shape, shared by App.tsx and nav-model.ts (type only - no runtime code).
export type Route = {
  page: 'home' | 'login' | 'flows' | 'versions' | 'systems' | 'contracts' | 'rules' | 'mappings' | 'queue' | 'failures' | 'messages' | 'activity' | 'reports' | 'approvals';
  flowId: string | null;
  ruleSetId?: string | null;
  routeId?: string | null;
  trackingId?: string;
  failedHint?: string | null;
};
