import { api, download } from './client';
import type { Frequency, NewCovenant } from './types';
import type {
  AiSuggestion, Alert, AppliedChange, TrendPoint, AppliedCovenant, Assessment, Attachment, CovenantDetail, CovenantUsage,
  CpDiagnostics, Cycle, DataColumn, DataSource, DataSourceTable, DataSourceWrite, DefinitionChange, ExpressionChange,
  HealthReport, ManualRun, MeasureDetail, MeasureWrite, OpsAudit, TableWrite, Remediation, ScheduleEvent, SentNotification,
  TestConnection, ThresholdSuggestion,
} from './types3';

const enc = encodeURIComponent;
const mon = (cpId: string) => `/api/v1/monitoring/cp/${enc(cpId)}`;

// Core's /api/v1/ops/** also wants the shared support token. The person types it in on the Support page;
// it lives only in this variable, never in storage, so a reload forgets it.
let supportToken = '';
export function setSupportToken(token: string): void {
  supportToken = token.trim();
}
const ops = () => ({ headers: { 'X-Support-Token': supportToken } });

export const r3 = {
  // ---- one covenant on one CP ----
  completeManual: (assessmentId: string, result: 'COMPLIANT' | 'BREACHED' | 'NA', note: string) =>
    api<Assessment>(`/api/v1/assessments/${enc(assessmentId)}/complete-manual`,
      { method: 'POST', json: { result, note } }),
  checkNow: (appliedId: string) =>
    api<Assessment>(`/api/v1/assessments/run?appliedCovenantId=${enc(appliedId)}`, { method: 'POST' }),
  history: (appliedId: string) =>
    api<Assessment[]>(`/api/v1/monitoring/applied-covenants/${enc(appliedId)}/history?size=40`),
  applied: (cpId: string) => api<AppliedCovenant[]>(`/api/v1/credit-proposals/${enc(cpId)}/applied-covenants`),
  updateApplied: (appliedId: string, body: {
    frequency?: Frequency; cronExpression?: string | null; status?: 'ACTIVE' | 'SUSPENDED';
    conditionExpression?: string; reason?: string;
  }) => api<AppliedCovenant>(`/api/v1/applied-covenants/${enc(appliedId)}`, { method: 'PATCH', json: body }),
  appliedChanges: (appliedId: string) => api<AppliedChange[]>(`/api/v1/applied-covenants/${enc(appliedId)}/audit`),
  definitionHistory: (appliedId: string) =>
    api<DefinitionChange[]>(`/api/v1/applied-covenants/${enc(appliedId)}/definition-history`),
  addAttachments: (cpId: string, appliedId: string, files: File[]) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    return api<Attachment[]>(`${mon(cpId)}/covenants/${enc(appliedId)}/attachments`, { method: 'POST', body: form });
  },
  removeAttachment: (cpId: string, appliedId: string, attachmentId: string) =>
    api<Attachment[]>(`${mon(cpId)}/covenants/${enc(appliedId)}/attachments/${enc(attachmentId)}`,
      { method: 'DELETE' }),
  downloadAttachment: (cpId: string, appliedId: string, a: Attachment) =>
    download(`${mon(cpId)}/covenants/${enc(appliedId)}/attachments/${enc(a.id)}/download`, a.fileName),
  aiSuggestions: (cpId: string) => api<AiSuggestion[]>(`${mon(cpId)}/ai-suggestions`, { method: 'POST' }),
  cycles: (cpId: string) => api<Cycle[]>(`${mon(cpId)}/cycles`),
  schedule: (from: string, to: string) =>
    api<ScheduleEvent[]>(`/api/v1/monitoring/schedule?from=${enc(from)}&to=${enc(to)}`),
  thresholdSuggestions: (cpId: string, items: { covenantMasterId: string; covenantName: string;
    currentExpression: string | null }[]) =>
    api<ThresholdSuggestion[]>(`/api/v1/credit-proposals/${enc(cpId)}/ml-threshold-recommendations`,
      { method: 'POST', json: { items } }),

  withdrawRequest: (cpId: string, appliedId: string, note?: string) =>
    api<unknown>(`${mon(cpId)}/covenants/${enc(appliedId)}/status/withdraw`, { method: 'POST', json: { note } }),
  alerts: () => api<Alert[]>('/api/v1/alerts'),
  trend: (months = 12) => api<TrendPoint[]>(`/api/v1/analytics/trend?months=${months}`),
  removeDocument: (documentId: string, reason: string) =>
    api<unknown>(`/dps/v1/documents/${enc(documentId)}/remove`, { method: 'POST', json: { reason } }),

  // ---- deletes for mistakes ----
  deleteCertificate: (cpId: string, id: string) =>
    api<unknown>(`${mon(cpId)}/compliance-certificate/${enc(id)}`, { method: 'DELETE' }),
  downloadCertificate: (cpId: string, id: string, fileName: string) =>
    download(`${mon(cpId)}/compliance-certificate/${enc(id)}/download`, fileName),
  deleteRequirement: (cpId: string, id: string) =>
    api<unknown>(`${mon(cpId)}/certificate-requirements/${enc(id)}`, { method: 'DELETE' }),
  deleteFxRate: (id: string) => api<unknown>(`/api/v1/fx/rates/${enc(id)}`, { method: 'DELETE' }),

  // ---- documents ----
  linkDocument: (documentId: string, cpIds: string[]) =>
    api<unknown>(`/dps/v1/documents/${enc(documentId)}/links`, { method: 'POST', json: { cpIds } }),
  unlinkDocument: (documentId: string, cpIds: string[]) =>
    api<unknown>(`/dps/v1/documents/${enc(documentId)}/links`, { method: 'DELETE', json: { cpIds } }),

  // ---- library ----
  covenant: (id: string) => api<CovenantDetail>(`/api/v1/covenants/${enc(id)}`),
  updateCovenant: (id: string, c: CovenantDetail, patch: Partial<NewCovenant> & { defaultCron?: string | null; policyReference?: string | null }) =>
    api<CovenantDetail>(`/api/v1/covenants/${enc(id)}`, { method: 'PUT', json: {
      name: c.name, category: c.category, description: c.description, conditionExpression: c.conditionExpression,
      businessSegments: c.businessSegments, islamicBanking: c.islamicBanking, assessmentType: c.assessmentType,
      industries: c.industries, countriesOfRisk: c.countriesOfRisk,
      countriesOfIncorporation: c.countriesOfIncorporation, creditProposalTypes: c.creditProposalTypes,
      legalEntityTypes: c.legalEntityTypes, facilityTypes: c.facilityTypes, collateralTypes: c.collateralTypes,
      defaultFrequency: c.defaultFrequency, defaultCron: c.defaultCron, policyReference: c.policyReference ?? null,
      ...patch,
    } }),
  deactivateCovenant: (id: string) => api<CovenantDetail>(`/api/v1/covenants/${enc(id)}`, { method: 'DELETE' }),
  covenantUsage: (id: string) => api<CovenantUsage>(`/api/v1/covenants/${enc(id)}/usage`),
  covenantChanges: (id: string) => api<ExpressionChange[]>(`/api/v1/covenants/${enc(id)}/audit`),
  validateCron: (cronExpression: string) =>
    api<{ valid: boolean; error: string | null; nextFireTimes: string[] | null }>('/api/v1/schedules/validate-cron',
      { method: 'POST', json: { cronExpression } }),

  // ---- measures ----
  measures: () => api<MeasureDetail[]>('/api/v1/measures'),
  createMeasure: (m: MeasureWrite) => api<MeasureDetail>('/api/v1/measures', { method: 'POST', json: m }),
  updateMeasure: (id: string, m: MeasureWrite) =>
    api<MeasureDetail>(`/api/v1/measures/${enc(id)}`, { method: 'PUT', json: m }),
  deactivateMeasure: (id: string) => api<MeasureDetail>(`/api/v1/measures/${enc(id)}`, { method: 'DELETE' }),

  // ---- data sources ----
  dataSources: () => api<DataSource[]>('/api/v1/data-sources'),
  createDataSource: (d: DataSourceWrite) => api<DataSource>('/api/v1/data-sources', { method: 'POST', json: d }),
  updateDataSource: (id: string, d: DataSourceWrite) =>
    api<DataSource>(`/api/v1/data-sources/${enc(id)}`, { method: 'PUT', json: d }),
  deactivateDataSource: (id: string) => api<DataSource>(`/api/v1/data-sources/${enc(id)}`, { method: 'DELETE' }),
  testDataSource: (id: string) => api<TestConnection>(`/api/v1/data-sources/${enc(id)}/test`, { method: 'POST' }),
  tables: (dataSourceId: string) => api<DataSourceTable[]>(`/api/v1/data-sources/${enc(dataSourceId)}/tables`),
  saveTable: (dataSourceId: string, body: TableWrite, tableId?: string) => tableId
    ? api<DataSourceTable>(`/api/v1/data-sources/${enc(dataSourceId)}/tables/${enc(tableId)}`, { method: 'PUT', json: body })
    : api<DataSourceTable>(`/api/v1/data-sources/${enc(dataSourceId)}/tables`, { method: 'POST', json: body }),
  deactivateTable: (dataSourceId: string, tableId: string) =>
    api<DataSourceTable>(`/api/v1/data-sources/${enc(dataSourceId)}/tables/${enc(tableId)}`, { method: 'DELETE' }),
  tableColumns: (dataSourceId: string, tableId: string) =>
    api<DataColumn[]>(`/api/v1/data-sources/${enc(dataSourceId)}/tables/${enc(tableId)}/columns`),

  // ---- support ----
  cpDiagnostics: (cpId: string) => api<CpDiagnostics>(`/api/v1/ops/cps/${enc(cpId)}/diagnostics`, ops()),
  reingest: (cpId: string) =>
    api<Remediation>(`/api/v1/ops/cps/${enc(cpId)}/reingest`, { method: 'POST', ...ops() }),
  reassess: (appliedId: string) =>
    api<Remediation>(`/api/v1/ops/applied-covenants/${enc(appliedId)}/reassess`, { method: 'POST', ...ops() }),
  opsAudit: (targetType: string, targetId?: string) => api<OpsAudit[]>(
    `/api/v1/ops/audit?targetType=${enc(targetType)}${targetId ? `&targetId=${enc(targetId)}` : ''}`, ops()),
  dataSourceHealth: () => api<HealthReport>('/api/v1/ops/health/data-sources', ops()),
  sendReminders: () => api<{ contacts: number; sent: number; failed: number }>('/api/v1/monitoring/reminder-runs',
    { method: 'POST' }),
  sendScheduledReports: () => api<{ sent: number }>('/api/v1/reports/scheduled-runs', { method: 'POST' }),
  sendDailyEmail: () => api<ManualRun>('/api/v1/monitoring/notification-runs', { method: 'POST' }),
  dailyRun: (runId: string) => api<ManualRun>(`/api/v1/monitoring/notification-runs/${encodeURIComponent(runId)}`),
  notifications: () => api<SentNotification[]>('/api/v1/ops/notifications', ops()),
};
