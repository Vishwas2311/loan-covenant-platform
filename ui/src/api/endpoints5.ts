import { api } from './client';

// Phase 4: the borrower portal (C1) and the nightly loan-system sync (C8).

const enc = encodeURIComponent;

export interface PortalCp {
  cpId: string;
  name: string | null;
}

export interface PortalRequirement {
  id: string;
  periodEnd: string;
  dueDate: string;
  state: 'PENDING' | 'RECEIVED' | 'OVERDUE';
  overdueDays: number;
  files: string[];
}

export interface PortalSubmission {
  kind: 'CERTIFICATE' | 'STATEMENT';
  fileName: string;
  periodEnd: string | null;
  submittedBy: string;
  submittedAt: string;
  note: string | null;
}

export interface PortalView {
  cpId: string;
  name: string | null;
  requirements: PortalRequirement[];
  covenants: { name: string; nextDueDate: string | null }[];
  submissions: PortalSubmission[];
}

export interface PortalAccess {
  cpId: string;
  userId: string;
  grantedBy: string;
  grantedAt: string;
}

export interface SyncChange {
  cpId: string;
  field: string;
  oldValue: string | null;
  newValue: string | null;
}

export interface SyncRun {
  id: string;
  startedAt: string;
  finishedAt: string | null;
  cpsChecked: number;
  cpsChanged: number;
  cpsClosed: number;
  errors: number;
  triggeredBy: string;
  changes: SyncChange[];
}

export const r5 = {
  // ---- C1 borrower portal (borrower side) ----
  portalCps: () => api<PortalCp[]>('/api/v1/portal/cps'),
  portalCp: (cpId: string) => api<PortalView>(`/api/v1/portal/cp/${enc(cpId)}`),
  submitCertificate: (cpId: string, file: File, periodEnd: string, signedBy: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('periodEnd', periodEnd);
    form.append('signedBy', signedBy);
    form.append('confirm', 'true');
    return api<PortalView>(`/api/v1/portal/cp/${enc(cpId)}/certificate`, { method: 'POST', body: form });
  },
  submitStatement: (cpId: string, file: File, periodEnd: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('periodEnd', periodEnd);
    return api<PortalView>(`/api/v1/portal/cp/${enc(cpId)}/statement`, { method: 'POST', body: form });
  },

  // ---- C1 portal access (bank side) ----
  portalAccess: (cpId: string) => api<PortalAccess[]>(`/api/v1/monitoring/cp/${enc(cpId)}/portal-access`),
  grantPortal: (cpId: string, userId: string) =>
    api<PortalAccess[]>(`/api/v1/monitoring/cp/${enc(cpId)}/portal-access`, { method: 'POST', json: { userId } }),
  revokePortal: (cpId: string, userId: string) =>
    api<PortalAccess[]>(`/api/v1/monitoring/cp/${enc(cpId)}/portal-access/${enc(userId)}`, { method: 'DELETE' }),
};

/** C8: support calls carry the support token, like the other /ops tools. */
export function syncApi(token: string) {
  const headers = { 'X-Support-Token': token };
  return {
    runs: () => api<SyncRun[]>('/api/v1/ops/loan-sync-runs', { headers }),
    runNow: () => api<SyncRun>('/api/v1/ops/loan-sync-runs', { method: 'POST', headers }),
  };
}
