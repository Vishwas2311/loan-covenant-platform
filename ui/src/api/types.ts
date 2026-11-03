// Shapes returned by Covenant Core (/api/v1) and the Document Processing Service (/dps/v1).

export type AssessmentResult = 'COMPLIANT' | 'BREACHED' | 'ERROR' | null;
export type DispositionStatus = 'COMPLIED' | 'BREACHED' | 'WAIVER' | 'EXTENSION';
export type Approval = 'REQUESTED' | 'APPROVED' | 'REJECTED';
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY' | 'CUSTOM';
export type AssessmentType = 'AUTO' | 'MANUAL';

export interface CpSummary {
  cpId: string;
  applied: number;
  compliant: number;
  breached: number;
  error: number;
  pendingReview: number;
  dispositioned: number;
}

export interface ReviewRow {
  appliedCovenantId: string;
  covenantMasterId: string;
  covenantName: string;
  conditionExpression: string;
  category: 'FINANCIAL' | 'NON_FINANCIAL' | null;
  assessmentType: AssessmentType;
  computedValue: string | null;
  headroom: number | null;
  latestResult: AssessmentResult;
  latestAssessmentId: string | null;
  lastAssessedAt: string | null;
  dispositionStatus: DispositionStatus;
  extensionDate: string | null;
  statusSource: 'SYSTEM' | 'USER' | 'AI';
  note: string | null;
  dispositionable: boolean;
  nextDueDate: string | null;
  timingStatus: string | null;
  overdueByDays: number;
  approvalStatus: Approval | null;
  requestedStatus: DispositionStatus | null;
  requestedBy: string | null;
}

export interface StatusAudit {
  oldStatus: DispositionStatus;
  newStatus: DispositionStatus;
  source: string;
  rationale: string | null;
  changedBy: string;
  changedDate: string;
}

export interface Evidence {
  documentId: string | null;
  fileId: string | null;
  page: number | null;
  quote: string | null;
}

export interface OnboardingRow {
  covenantId: string | null;
  name: string;
  matchReason: string;
  assessmentType: AssessmentType;
  defaultFrequency: Frequency;
  defaultCron: string | null;
  custom: boolean;
  alreadyApplied: boolean;
  source: 'RECOMMENDED' | 'ML' | 'EXTRACTED' | 'EXTRACTED_CUSTOM';
  conditionOverride: string | null;
  thresholdSource: 'MASTER' | 'USER' | 'ML' | 'EXTRACTED';
  mlRecommendedExpression: string | null;
  extractedExpression: string | null;
  extractionConfidence: number | null;
  evidence: Evidence | null;
  customDefinition: string | null;
}

export interface CreditProposal {
  cpId: string;
  [key: string]: unknown;
}

export interface Covenant {
  id: string;
  name: string;
  category: string;
  description: string | null;
  conditionExpression: string | null;
  assessmentType: AssessmentType;
  defaultFrequency: Frequency;
  active: boolean;
  custom: boolean;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  number: number;
  last: boolean;
}

export interface ReportingSettings {
  fiscalYearStartMonth: number;
  periodicIncludesCurrentPeriod: boolean;
  dueSoonDays: number;
  upcomingWindowDays: number;
  dispositionApprovalRequired: boolean;
  reportingCurrency: string;
  modifiedDate: string | null;
  modifiedBy: string | null;
}

export type DocumentType = 'LOAN_AGREEMENT' | 'FINANCIAL_STATEMENT' | 'EVIDENCE';

export interface DpsDocument {
  id: string;
  type: DocumentType;
  fileName: string;
  pageCount: number | null;
  asOfDate: string | null;
  status: string;
  cpIds: string[];
  latestJobId: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

export interface ExtractedTerm {
  id: string;
  documentId: string;
  metricRaw: string;
  covenantMasterId: string | null;
  expression: string | null;
  operator: string | null;
  threshold: number | null;
  unit: string | null;
  frequency: string | null;
  category: string;
  confidence: number;
  method: string;
  status: 'PROPOSED' | 'NEEDS_REVIEW' | 'PUBLISHED' | 'REJECTED';
  issues: string[];
  source: { documentId: string; page: number | null; quote: string | null };
  reviewedBy: string | null;
  reviewNote: string | null;
}

export interface Job {
  id: string;
  documentId: string;
  flow: string;
  status: string;
  pool: string;
  attempts: number;
  currentStep: string | null;
  error: string | null;
  steps: { step: string; at: string; [k: string]: unknown }[];
}

export interface StatementValue {
  key: string;
  value: number;
  unit: string;
  asOfDate: string;
  derived: boolean;
  [k: string]: unknown;
}
