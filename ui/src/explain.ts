/**
 * Plain words for what DPS read and why it waits for a person.
 *
 * DPS reports issues as codes ("THRESHOLD_CONFLICT", "SECOND_READER_DIFFERS: REVENUE read 163.6, second reader
 * 172.4"). A reviewer needs to know what the code means and what to do, so every code the engine can raise has
 * a sentence here; an unknown code is shown as it is.
 */
import type { ExtractedTerm } from './api/types';

export interface Explained {
  /** What is wrong, in one sentence. */
  what: string;
  /** What the reviewer should do. */
  todo: string;
  /** Standard line or covenant the issue is about, when it names one (for highlighting). */
  key?: string;
  severity: 'warn' | 'info';
}

const LINE_NAMES: Record<string, string> = {
  REVENUE: 'Revenue', PBT: 'Profit before tax', PAT: 'Profit after tax', INTEREST_PAID: 'Interest / finance costs',
  DEPRECIATION: 'Depreciation', TOTAL_ASSETS: 'Total assets', NET_WORTH: 'Net worth (total equity)',
  CURRENT_ASSETS: 'Current assets', CURRENT_LIABILITIES: 'Current liabilities', LT_DEBT: 'Long-term borrowings',
  ST_DEBT: 'Short-term borrowings', TOTAL_DEBT: 'Total debt', CASH: 'Cash and cash equivalents',
  CFO: 'Cash from operations', INVENTORY: 'Inventories', RECEIVABLES: 'Trade receivables', EBITDA: 'EBITDA',
  EBIT: 'EBIT', TAX: 'Tax', TRADE_PAYABLES: 'Trade payables',
};

export function lineName(key: string): string {
  return LINE_NAMES[key] ?? humanizeCode(key);
}

/** "DEBT_TO_EBITDA:CONSOLIDATED_SENIOR_SECURED_LEVERAGE_RATIO" -> "Consolidated Senior Secured Leverage Ratio". */
export function humanizeCode(code: string): string {
  const last = code.includes(':') ? code.split(':').pop()! : code;
  return last.toLowerCase().split('_').filter(Boolean).map((w, i) => (i === 0 ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ')
    .replace(/\bdscr\b/i, 'DSCR').replace(/\bebitda\b/gi, 'EBITDA').replace(/\bemi\b/i, 'EMI');
}

const UNIT_WORDS: Record<string, string> = {
  crore: 'Rs. crore', lakh: 'Rs. lakh', million: 'millions', thousand: 'thousands', billion: 'billions',
  rupee: 'full rupees',
};

export function unitWords(scale: string | null | undefined): string | null {
  return scale ? UNIT_WORDS[scale] ?? scale : null;
}

export function explain(issue: string): Explained {
  const [code, ...rest] = issue.split(':');
  const detail = rest.join(':').trim();
  switch (code) {
    case 'THRESHOLD_CONFLICT':
      return { severity: 'warn', what: 'The agreement gives this covenant more than one level (often a step-down schedule).',
        todo: 'Open the page, pick the level that applies now and edit the rule if needed.' };
    case 'QUOTE_NOT_FOUND':
    case 'QUOTE_PAGE_MISSING':
      return { severity: 'warn', what: 'The quoted words could not be found on the cited page.',
        todo: 'Open the page and confirm the covenant really says this.' };
    case 'THRESHOLD_NOT_IN_QUOTE':
      return { severity: 'warn', what: 'The number in the rule does not appear in the quote.',
        todo: 'Check the number against the agreement.' };
    case 'OPERATOR_UNEXPECTED':
      return { severity: 'warn', what: `The direction looks unusual for this covenant (${detail}).`,
        todo: 'Check whether the agreement sets a minimum or a maximum.' };
    case 'FORMULA_INVALID':
      return { severity: 'warn', what: `Core could not read the rule: ${detail}.`, todo: 'Edit the rule.' };
    case 'LOW_CONFIDENCE':
      return { severity: 'warn', what: 'Found outside a numbered covenant clause, so it is less certain.',
        todo: 'Open the page and confirm it is an ongoing covenant, not a pricing level or a one-off condition.' };
    case 'SECOND_COVENANT_OF_FAMILY':
      return { severity: 'warn',
        what: 'The agreement has another covenant of the same kind; this one is proposed as a separate custom covenant.',
        todo: 'Check the formula measures this covenant (for example senior secured debt, not total debt).' };
    case 'AMOUNT_NOT_IN_INR':
      return { severity: 'warn', what: 'The amount is in US dollars; figures from statements are in INR crore.',
        todo: 'Convert the threshold to INR crore before applying the covenant.' };
    case 'LOW_OCR_QUALITY':
      return { severity: 'warn', what: `The statement was a scan and was read with low confidence (${detail.replace(/^scanned pages read at /, '')}).`,
        todo: 'Compare each figure below with the scan and correct any that differ.' };
    case 'SECOND_READER_DIFFERS': {
      const m = /^(\w+) read (\S+), second reader (\S+)/.exec(detail);
      return m ? { severity: 'warn', key: m[1],
        what: `${lineName(m[1])}: the table reader read ${m[2]}, the second reader (AI) read ${m[3]}.`,
        todo: 'Open the page and keep or correct the figure. The table reader\'s figure is used unless you change it.' }
        : { severity: 'warn', what: detail, todo: 'Check the figure.' };
    }
    case 'UNIT_NOT_STATED':
      return { severity: 'warn', what: 'The statement does not say its unit, so amounts were read as INR crore.',
        todo: 'Check the unit on the statement; if it is lakhs or rupees, correct the figures.' };
    case 'MISSING_LINE_ITEMS':
      return { severity: 'warn', what: `Not found in the statement: ${detail.split(',').map((k) => lineName(k.trim())).join(', ')}.`,
        todo: 'Enter these figures by hand if the statement has them; covenants that need them cannot be assessed.' };
    case 'NEGATIVE_VALUE':
      return { severity: 'warn', key: detail, what: `${lineName(detail)} came out negative, which a balance cannot be.`,
        todo: 'Check the figure and its sign.' };
    case 'DEBT_DOES_NOT_ADD_UP':
      return { severity: 'warn', key: 'TOTAL_DEBT', what: `Total debt does not equal long-term plus short-term borrowings (${detail}).`,
        todo: 'Check the three debt lines.' };
    case 'EBIT_ABOVE_EBITDA':
      return { severity: 'warn', key: 'EBITDA', what: 'EBIT is larger than EBITDA, which cannot be right.',
        todo: 'Check EBIT, depreciation and EBITDA.' };
    case 'BALANCE_SHEET_DOES_NOT_BALANCE':
      return { severity: 'warn', key: 'TOTAL_ASSETS', what: `The balance sheet does not balance as read (${detail}).`,
        todo: 'One of the two totals was read from the wrong line or column; check both against the page.' };
    case 'PART_ABOVE_TOTAL': {
      const key = detail.split(' ')[0];
      return { severity: 'warn', key, what: `${lineName(key)} is larger than total assets, which cannot be right.`,
        todo: 'It was probably read from the wrong column (for example the group instead of the company); check it.' };
    }
    case 'PROFIT_DOES_NOT_ADD_UP':
      return { severity: 'warn', key: 'PAT', what: 'Profit before tax less tax is far from profit after tax.',
        todo: 'Check the three lines; one was probably misread (exceptional or discontinued items can explain a small gap).' };
    case 'INCOME_DOES_NOT_ADD_UP':
      return { severity: 'warn', key: 'TOTAL_INCOME', what: 'Revenue plus other income does not equal total income.',
        todo: 'Check the three lines.' };
    case 'CURRENT_ASSETS_TOO_SMALL':
      return { severity: 'warn', key: 'CURRENT_ASSETS', what: 'Cash, inventory and receivables add up to more than current assets.',
        todo: 'One of them, or current assets, was read from the wrong line or column.' };
    case 'SCALE_JUMP': {
      const m = /^(\w+) (\S+) against (\S+) for (\S+)/.exec(detail);
      return m ? { severity: 'warn', key: m[1],
        what: `${lineName(m[1])} is ${m[2]} crore; for the period ending ${m[4]} it was ${m[3]}: more than 20 times different.`,
        todo: 'The unit was probably misread (lakhs, thousands or rupees taken as crore). Check the unit on the statement.' }
        : { severity: 'warn', what: detail, todo: 'Check the unit.' };
    }
    case 'POSSIBLE_COVENANT_NOT_UNDERSTOOD':
      return { severity: 'warn', what: 'This sentence reads like a financial covenant, but DPS did not recognise it.',
        todo: 'Open the page. If it is a covenant, approve it (it is added as a covenant checked by a person) or add it on the CP; if not, reject it.' };
    case 'MORE_POSSIBLE_COVENANTS':
      return { severity: 'info', what: `${detail}.`, todo: 'Read the financial covenants section of the agreement.' };
    default:
      return { severity: 'info', what: issue, todo: 'Check this item.' };
  }
}

const OPERATOR_WORDS: Record<string, string> = {
  '>=': 'at least', '>': 'more than', '<=': 'at most', '<': 'less than', '==': 'exactly',
};

/** "Total Debt to EBITDA: at most 3.5×", "Maximum Loan to Value: at most 75%", "Minimum Net Worth: at least Rs. 500 crore". */
export function ruleWords(t: Pick<ExtractedTerm, 'operator' | 'threshold' | 'unit'>): string | null {
  if (!t.operator || t.threshold === null) {
    return null;
  }
  const n = t.threshold.toLocaleString('en-IN', { maximumFractionDigits: 4 });
  const unit = t.unit ?? '';
  let value: string;
  if (unit === 'PERCENT' || unit === '%') {
    value = `${n}%`;
  } else if (unit === 'INR_CR') {
    value = `Rs. ${n} crore`;
  } else if (unit === 'USD') {
    value = `$${n}`;
  } else {
    value = `${n}×`;
  }
  return `${OPERATOR_WORDS[t.operator] ?? t.operator} ${value}`;
}

export function termName(t: Pick<ExtractedTerm, 'name' | 'metricRaw'>): string {
  return t.name?.trim() || humanizeCode(t.metricRaw);
}

export function confidenceWords(c: number): { label: string; tone: 'good' | 'warn' | 'bad' } {
  if (c >= 0.9) {
    return { label: 'High', tone: 'good' };
  }
  if (c >= 0.8) {
    return { label: 'Medium', tone: 'warn' };
  }
  return { label: 'Low', tone: 'bad' };
}
