import { api, download } from './client';
import type {
  BreachRow, Certificate, CertificateRequirement, FxRate, Narrative, PeriodComparison, PortfolioAnalytics,
} from './types2';

const enc = encodeURIComponent;
const mon = (cpId: string) => `/api/v1/monitoring/cp/${enc(cpId)}`;

export const r2 = {
  certificates: (cpId: string) => api<Certificate[]>(`${mon(cpId)}/compliance-certificate`),
  uploadCertificate: (cpId: string, file: File, periodEnd?: string) => {
    const form = new FormData();
    form.append('files', file);
    const q = periodEnd ? `?periodEnd=${enc(periodEnd)}` : '';
    return api<Certificate[]>(`${mon(cpId)}/compliance-certificate${q}`, { method: 'POST', body: form });
  },
  requirements: (cpId: string) => api<CertificateRequirement[]>(`${mon(cpId)}/certificate-requirements`),
  saveRequirement: (cpId: string, body: { periodEnd: string; dueDate: string; note?: string }) =>
    api<CertificateRequirement>(`${mon(cpId)}/certificate-requirements`, { method: 'PUT', json: body }),
  comparison: (cpId: string, periods = 4) => api<PeriodComparison>(`${mon(cpId)}/comparison?periods=${periods}`),
  comparisonXlsx: (cpId: string) => download(`${mon(cpId)}/comparison.xlsx`, `covenants-${cpId}.xlsx`),
  libraryXlsx: () => download('/api/v1/covenants/export.xlsx', 'covenant-library.xlsx'),
  analytics: () => api<PortfolioAnalytics>('/api/v1/analytics/portfolio'),
  openBreaches: (days = 14) => api<BreachRow[]>(`/api/v1/reports/open-breaches?escalationDays=${days}`),
  openBreachesXlsx: (days = 14) =>
    download(`/api/v1/reports/open-breaches.xlsx?escalationDays=${days}`, 'open-breaches.xlsx'),
  openBreachesPdf: (days = 14) =>
    download(`/api/v1/reports/open-breaches.pdf?escalationDays=${days}`, 'open-breaches.pdf'),
  fxRates: () => api<FxRate[]>('/api/v1/fx/rates'),
  saveFxRate: (body: { fromCurrency: string; toCurrency: string; rateDate: string; rate: number; source?: string }) =>
    api<FxRate>('/api/v1/fx/rates', { method: 'PUT', json: body }),
  narrative: (documentId: string) => api<Narrative>(`/dps/v1/documents/${enc(documentId)}/narrative`),
};
