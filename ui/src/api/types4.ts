// Shapes for the Phase 3 screens: work queue, borrower reminders, agreement Q&A, letters, saved reports
// and statement spreading. Field names follow Core's and DPS's JSON exactly.

export type TaskKind = 'APPROVAL' | 'BREACH' | 'OVERDUE' | 'REVIEW' | 'CERTIFICATE';

export interface WorkTask {
  id: string;
  cpId: string;
  appliedCovenantId: string | null;
  kind: TaskKind;
  title: string;
  assignee: string;
  dueDate: string;
  status: 'OPEN' | 'DONE' | 'RESOLVED';
  note: string | null;
  createdAt: string;
  closedAt: string | null;
  closedBy: string | null;
  escalated: boolean;
}

export interface BorrowerContact {
  cpId: string;
  contactName: string;
  email: string;
  remindersOn: boolean;
  daysBefore: number;
  updatedBy?: string;
  updatedAt?: string;
}

export interface ReminderLog {
  id: string;
  cpId: string;
  requirementId: string;
  kind: 'DUE_SOON' | 'OVERDUE';
  sentOn: string;
  sentTo: string;
  outcome: string;
  detail: string | null;
}

export interface AgreementAnswer {
  answer: string;
  citations: { page: number; quote: string }[];
  advisory: string;
}

export type LetterKind = 'BREACH_NOTICE' | 'WAIVER_GRANTED' | 'EXTENSION_GRANTED';

export interface ReportDefinition {
  columns: string[];
  results: string[];
  segment: string | null;
  cpFilter: string | null;
}

export interface SavedReport {
  id: string;
  name: string;
  definition: ReportDefinition;
  schedule: 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY';
  emailTo: string | null;
  lastSentOn: string | null;
}

export interface SpreadLine {
  section: 'BALANCE_SHEET' | 'PROFIT_AND_LOSS' | 'CASH_FLOW';
  standardKey: string;
  standardLabel: string;
  sourceLabel: string | null;
  value: number | null;
  unit: string | null;
  cell: string | null;
  page: number | null;
  confidence: number;
  status: 'MAPPED' | 'UNMAPPED' | 'CONFIRMED';
}
