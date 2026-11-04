// R2 shapes: certificates, comparison, analytics, reports, FX.
import type { Approval, AssessmentResult, DispositionStatus } from './types';

export interface Certificate {
  id: string;
  fileId: string;
  fileName: string;
  uploadedBy: string;
  uploadedAt: string;
  periodEnd: string | null;
}

export interface CertificateRequirement {
  id: string;
  cpId: string;
  periodEnd: string;
  dueDate: string;
  note: string | null;
  state: 'PENDING' | 'RECEIVED' | 'OVERDUE';
  overdueDays: number;
  certificates: Certificate[];
}

export interface PeriodComparison {
  cpId: string;
  periods: string[];
  rows: {
    appliedCovenantId: string;
    covenantName: string;
    conditionExpression: string | null;
    cells: ({ result: AssessmentResult; computedValue: string | null; assessmentId: string } | null)[];
    trend: 'IMPROVING' | 'STABLE' | 'WORSENING' | null;
  }[];
}

export interface AnalyticsGroup {
  key: string;
  cps: number;
  applied: number;
  compliant: number;
  breached: number;
  error: number;
  dispositioned: number;
  breachRate: number;
}

export interface PortfolioAnalytics {
  total: AnalyticsGroup;
  bySegment: AnalyticsGroup[];
  bySector: AnalyticsGroup[];
}

export interface BreachRow {
  cpId: string;
  appliedCovenantId: string;
  covenantName: string;
  result: AssessmentResult;
  computedValue: string | null;
  since: string;
  daysOpen: number;
  status: DispositionStatus;
  approval: Approval | null;
  escalate: boolean;
}

export interface FxRate {
  id: string;
  fromCurrency: string;
  toCurrency: string;
  rateDate: string;
  rate: number;
  source: string | null;
}

export interface Narrative {
  text: string;
  method: 'RULES' | 'AI';
  advisory: string;
}

/** One CP the user looks after, for the CP chooser (GET /api/v1/analytics/cps). */
export interface CpCard {
  cpId: string;
  name: string | null;
  businessSegment: string | null;
  sector: string | null;
  rmUserId: string | null;
  applied: number;
  compliant: number;
  breached: number;
  error: number;
  pendingReview: number;
  dispositioned: number;
}
