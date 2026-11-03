import { api } from './client';
import type {
  Covenant, CpSummary, CreditProposal, DocumentType, DpsDocument, ExtractedTerm, Frequency, Job, Measure, NewCovenant,
  OnboardingRow, Page,
  ReportingSettings, ReviewRow, StatementValue, StatusAudit,
} from './types';

const enc = encodeURIComponent;

// ---------- Core ----------

export const core = {
  summary: () => api<CpSummary[]>('/api/v1/monitoring/summary'),
  review: (cpId: string) => api<ReviewRow[]>(`/api/v1/monitoring/cp/${enc(cpId)}/review`),
  statusAudit: (cpId: string) => api<StatusAudit[]>(`/api/v1/monitoring/cp/${enc(cpId)}/status-audit`),
  requestDisposition: (cpId: string, appliedId: string, body: {
    status: 'WAIVER' | 'EXTENSION'; extensionDate?: string | null; note?: string;
  }) => api<ReviewRow>(`/api/v1/monitoring/cp/${enc(cpId)}/covenants/${enc(appliedId)}/status`,
    { method: 'PUT', json: body }),
  decideDisposition: (cpId: string, appliedId: string, decision: 'APPROVE' | 'REJECT', note?: string) =>
    api<ReviewRow>(`/api/v1/monitoring/cp/${enc(cpId)}/covenants/${enc(appliedId)}/status/decision`,
      { method: 'POST', json: { decision, note } }),
  creditProposal: (cpId: string) => api<CreditProposal>(`/api/v1/credit-proposals/${enc(cpId)}`),
  onboarding: (cpId: string) =>
    api<OnboardingRow[]>(`/api/v1/credit-proposals/${enc(cpId)}/onboarding-recommendations`),
  createCustom: (cpId: string, body: {
    name: string; frequency: Frequency; definition: string; assessmentType: 'AUTO' | 'MANUAL';
  }) => api<{ covenantId: string }>(`/api/v1/credit-proposals/${enc(cpId)}/custom-covenants`,
    { method: 'POST', json: body }),
  apply: (cpId: string, items: {
    covenantMasterId: string; frequency: Frequency; cronExpression?: string | null;
    conditionExpression?: string | null; thresholdSource?: string | null;
  }[]) => api<unknown[]>(`/api/v1/credit-proposals/${enc(cpId)}/applied-covenants`,
    { method: 'POST', json: { items } }),
  covenants: (search = '', page = 0) =>
    api<Page<Covenant>>(`/api/v1/covenants?search=${enc(search)}&page=${page}&size=25`),
  createCovenant: (body: NewCovenant) => api<Covenant>('/api/v1/covenants', { method: 'POST', json: body }),
  measures: () => api<Measure[]>('/api/v1/measures'),
  settings: () => api<ReportingSettings>('/api/v1/settings/reporting'),
  saveSettings: (s: ReportingSettings) => api<ReportingSettings>('/api/v1/settings/reporting', {
    method: 'PUT',
    json: {
      fiscalYearStartMonth: s.fiscalYearStartMonth, periodicIncludesCurrentPeriod: s.periodicIncludesCurrentPeriod,
      dueSoonDays: s.dueSoonDays, upcomingWindowDays: s.upcomingWindowDays,
      dispositionApprovalRequired: s.dispositionApprovalRequired,
      reportingCurrency: s.reportingCurrency, atRiskHeadroomPercent: s.atRiskHeadroomPercent,
    },
  }),
};

// ---------- DPS ----------

export const dps = {
  documents: (cpId: string) => api<DpsDocument[]>(`/dps/v1/documents?cpId=${enc(cpId)}`),
  document: (id: string) => api<DpsDocument>(`/dps/v1/documents/${enc(id)}`),
  upload: (file: File, type: DocumentType, cpIds: string[], asOfDate?: string, amendsDocumentId?: string | null) => {
    const form = new FormData();
    form.append('file', file);
    form.append('type', type);
    cpIds.forEach((c) => form.append('cpIds', c));
    if (asOfDate) {
      form.append('asOfDate', asOfDate);
    }
    if (amendsDocumentId) {
      form.append('amendsDocumentId', amendsDocumentId);
    }
    return api<{ documentId: string; jobId: string | null }>('/dps/v1/documents', { method: 'POST', body: form });
  },
  terms: (documentId: string) => api<ExtractedTerm[]>(`/dps/v1/documents/${enc(documentId)}/terms`),
  values: (documentId: string) => api<StatementValue[]>(`/dps/v1/documents/${enc(documentId)}/values`),
  reviewTerm: (termId: string, body: {
    action: 'APPROVE' | 'REJECT' | 'EDIT'; expression?: string; note?: string;
  }) => api<ExtractedTerm>(`/dps/v1/terms/${enc(termId)}`, { method: 'PATCH', json: body }),
  job: (jobId: string) => api<Job>(`/dps/v1/jobs/${enc(jobId)}`),
  resumeJob: (jobId: string) => api<Job>(`/dps/v1/jobs/${enc(jobId)}/resume`, { method: 'POST' }),
  manualValue: (cpId: string, body: {
    asOfDate: string; key: string; value: number; unit: string; note: string; currency?: string;
  }) => api<StatementValue>(`/dps/v1/cps/${enc(cpId)}/manual-values`, { method: 'POST', json: body }),
};
