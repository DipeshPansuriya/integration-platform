// The console's hash-route shape, shared by App.tsx and nav-model.ts.
// ROUTE_PAGES is the one list of screens: nav-model.test.mjs fails when a page here has no role need (or allow-all) entry.
export const ROUTE_PAGES = [
  'home', 'login', 'flows', 'versions', 'systems', 'contracts', 'rules', 'mappings',
  'queue', 'failures', 'messages', 'activity', 'reports', 'approvals', 'destinations',
] as const;

export type Route = {
  page: (typeof ROUTE_PAGES)[number];
  flowId: string | null;
  ruleSetId?: string | null;
  routeId?: string | null;
  trackingId?: string;
  failedHint?: string | null;
};
