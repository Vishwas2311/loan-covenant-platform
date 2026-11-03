// Shapes for the screens added in Phase 1 (covenant detail, library detail, measures, data sources,
// support tools, cycles and the schedule). Field names follow Core's JSON exactly.
import type { AssessmentResult, AssessmentType, DispositionStatus, Frequency } from './types';

export interface Assessment {
  id: string;
  appliedCovenantId: string;
  executedAt: string | null;
  scheduledAt: string | null;
  computedValue: string | null;
  result: AssessmentResult;
  errorMessage: string | null;
  assessmentNote: string | null;
  triggeredBy: string;
  triggeredByUser: string | null;
  status: string;
}

export interface Attachment {
  id: string;
  fileId: string;
  fileName: string;
  uploadedBy: string;
  uploadedAt: string;
  periodEnd: string | null;
}

/** A frequency / cron / status change on one CP's covenant. */
export interface AppliedChange {
  id: string;
  changeType: 'FREQUENCY' | 'CRON' | 'STATUS';
  oldValue: string | null;
  newValue: string | null;
  changedDate: string;
  changedBy: string;
}

export interface DefinitionChange {
  changedAt: string;
  changedBy: string;
  oldExpression: string | null;
  newExpression: string | null;
  source: string;
  reason?: string | null;
}

export interface AppliedCovenant {
  id: string;
  cpId: string;
  frequency: Frequency;
  cronExpression: string | null;
  conditionOverride?: string | null;
  effectiveCondition?: string | null;
  status: 'ACTIVE' | 'SUSPENDED' | 'EXPIRED';
  [key: string]: unknown;
}

export interface CovenantDetail {
  id: string;
  name: string;
  category: 'FINANCIAL' | 'NON_FINANCIAL' | string;
  description: string | null;
  conditionExpression: string | null;
  assessmentType: AssessmentType;
  defaultFrequency: Frequency;
  defaultCron: string | null;
  businessSegments: string[];
  islamicBanking: boolean;
  industries: string[];
  countriesOfRisk: string[];
  countriesOfIncorporation: string[];
  creditProposalTypes: string[];
  legalEntityTypes: string[];
  facilityTypes: string[];
  collateralTypes: string[];
  active: boolean;
  custom: boolean;
  /** C5: the credit policy clause or link behind it. */
  policyReference?: string | null;
}

export interface CovenantUsage {
  covenantId: string;
  totalApplied: number;
  activelyApplied: number;
  sampleCpIds: string[];
}

export interface ExpressionChange {
  id: string;
  oldExpression: string | null;
  newExpression: string;
  changedDate: string;
  changedBy: string;
}

export type Aggregation = 'AVG' | 'SUM' | 'MIN' | 'MAX' | 'LATEST' | 'CURRENT' | 'COUNT';

export interface MeasureDetail {
  id: string;
  code: string;
  name: string;
  description: string | null;
  dataSourceTableId: string | null;
  dataSourceId: string | null;
  tableName: string | null;
  sourceColumn: string | null;
  sourceCode: string | null;
  defaultAggregation: Aggregation | null;
  dataType: 'NUMBER' | 'STRING' | 'DATE' | null;
  unit: string | null;
  active: boolean;
}

export interface MeasureWrite {
  code: string;
  name: string;
  description: string;
  dataSourceTableId: string | null;
  sourceColumn: string | null;
  defaultAggregation: Aggregation;
  dataType: 'NUMBER' | 'STRING' | 'DATE';
  unit: string | null;
  sourceCode: string | null;
}

export interface DataSource {
  id: string;
  name: string;
  type: 'REST' | 'MSSQL';
  description: string | null;
  /** Core replaces any password with ******** before sending it. */
  connectionConfig: Record<string, string>;
  active: boolean;
}

export interface DataSourceWrite {
  name: string;
  type: 'REST' | 'MSSQL';
  description: string;
  connectionConfig: Record<string, string>;
}

export interface DataSourceTable {
  id: string;
  dataSourceId: string;
  name: string;
  schemaName: string | null;
  tableName: string;
  entityKeyColumn: string;
  asOfColumn: string | null;
  layout: 'COLUMNAR';
  active: boolean;
}

export interface DataColumn {
  key: string;
  label: string;
  dataType: string;
  description: string | null;
}

export interface TestConnection {
  reachable: boolean;
  columnCount: number;
  message: string;
}

export interface AiSuggestion {
  appliedCovenantId: string;
  recommendedStatus: DispositionStatus;
  rationale: string;
  confidence: number | null;
}

export interface ThresholdSuggestion {
  covenantMasterId: string;
  recommendedExpression: string;
  confidence: number | null;
  rationale: string | null;
}

export interface Cycle {
  cycleNumber: number;
  cpId: string;
  current: boolean;
}

export interface ScheduleEvent {
  cpId: string;
  cpName: string | null;
  appliedCovenantId: string;
  covenantName: string;
  frequency: Frequency;
  nextDueDate: string | null;
  lastResult: AssessmentResult | null;
  timingStatus: 'NOT_STARTED' | 'UP_TO_DATE' | 'DUE_SOON' | 'OVERDUE';
  overdueByDays: number;
}

export interface OpsAudit {
  id: string;
  action: string;
  targetType: string;
  targetId: string | null;
  performedBy: string;
  paramsSummary: string | null;
  outcome: string | null;
  detail: string | null;
  performedAt: string;
}

export interface CpDiagnostics {
  cpId: string;
  obligorId: string | null;
  covenantCount: number;
  covenants: {
    appliedCovenantId: string;
    covenantName: string;
    assessmentType: string;
    conditionExpression: string | null;
    compiled: boolean;
    compileError: string | null;
    wouldResolve: boolean;
  }[];
}

export interface Remediation {
  action: string;
  cpId: string | null;
  appliedCovenantId: string | null;
  tablesTouched: string[];
  ingested: boolean;
  assessmentResult: string | null;
  warnings: string[];
}

export interface HealthReport {
  checkedAt: string;
  sourceCount: number;
  reachableCount: number;
  allReachable: boolean;
  sources: {
    id: string;
    name: string;
    type: string;
    active: boolean;
    reachable: boolean;
    latencyMs: number;
    tableCount: number;
    stagingRowCount: number;
    lastIngestAt: string | null;
    message: string | null;
  }[];
}

export interface RunResult {
  runId: string;
  covenantsAssessed: number;
  cpsTouched: number;
  notificationsSent: number;
  notificationsFailed: number;
}

export interface SentNotification {
  id: string;
  runId: string;
  emailTo: string;
  emailCc: string | null;
  cpIds: string;
  requestBody: string | null;
  httpStatus: number | null;
  outcome: string;
  errorMessage: string | null;
  changedBy: string;
  changedDate: string;
}

/** B6: one thing waiting on the signed-in user. */
export interface Alert {
  kind: 'APPROVAL' | 'BREACH' | 'OVERDUE' | 'REVIEW' | 'AT_RISK';
  cpId: string;
  appliedCovenantId: string;
  covenantName: string;
  message: string;
}

/** C9: check results in one month. */
export interface TrendPoint {
  month: string;
  compliant: number;
  breached: number;
  error: number;
  total: number;
}
