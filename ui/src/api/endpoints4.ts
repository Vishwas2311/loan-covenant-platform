import { api, download } from './client';
import type {
  AgreementAnswer, BorrowerContact, LetterKind, ReminderLog, ReportDefinition, SavedReport, SpreadLine, WorkTask,
} from './types4';

const enc = encodeURIComponent;
const mon = (cpId: string) => `/api/v1/monitoring/cp/${enc(cpId)}`;

export const r4 = {
  // ---- C4 work queue ----
  tasks: (scope: 'mine' | 'all', open = true) => api<WorkTask[]>(`/api/v1/tasks?scope=${scope}&open=${open}`),
  updateTask: (id: string, body: { assignee?: string; dueDate?: string; note?: string; done?: boolean }) =>
    api<WorkTask>(`/api/v1/tasks/${enc(id)}`, { method: 'PATCH', json: body }),

  // ---- C2 borrower reminders ----
  contact: (cpId: string) => api<BorrowerContact | null>(`${mon(cpId)}/borrower-contact`),
  saveContact: (cpId: string, body: Omit<BorrowerContact, 'cpId'>) =>
    api<BorrowerContact>(`${mon(cpId)}/borrower-contact`, { method: 'PUT', json: body }),
  reminders: (cpId: string) => api<ReminderLog[]>(`${mon(cpId)}/reminders`),

  // ---- C10 ask about one agreement ----
  askAgreement: (documentId: string, question: string) =>
    api<AgreementAnswer>(`/dps/v1/documents/${enc(documentId)}/ask`, { method: 'POST', json: { question } }),

  // ---- C12 borrower letters ----
  letter: (cpId: string, appliedId: string, kind: LetterKind) =>
    download(`${mon(cpId)}/covenants/${enc(appliedId)}/letter?kind=${kind}`, `${kind.toLowerCase()}-${cpId}.docx`),

  // ---- C11 saved reports ----
  savedReports: () => api<SavedReport[]>('/api/v1/reports/saved'),
  saveReport: (body: { name: string; definition: ReportDefinition; schedule: SavedReport['schedule']; emailTo: string | null }) =>
    api<SavedReport>('/api/v1/reports/saved', { method: 'POST', json: body }),
  updateReport: (id: string, body: { name: string; definition: ReportDefinition; schedule: SavedReport['schedule']; emailTo: string | null }) =>
    api<SavedReport>(`/api/v1/reports/saved/${enc(id)}`, { method: 'PUT', json: body }),
  deleteReport: (id: string) => api<unknown>(`/api/v1/reports/saved/${enc(id)}`, { method: 'DELETE' }),
  runReport: (definition: ReportDefinition) =>
    api<Record<string, unknown>[]>('/api/v1/reports/run', { method: 'POST', json: definition }),
  reportXlsx: (id: string, name: string) => download(`/api/v1/reports/saved/${enc(id)}/export.xlsx`, `${name}.xlsx`),

  // ---- C6 statement spreading ----
  spread: (documentId: string) => api<SpreadLine[]>(`/dps/v1/documents/${enc(documentId)}/spread`),
  confirmSpread: (documentId: string, lines: { standardKey: string; value: number | null }[]) =>
    api<SpreadLine[]>(`/dps/v1/documents/${enc(documentId)}/spread`, { method: 'PUT', json: { lines } }),
};
