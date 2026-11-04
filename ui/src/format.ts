const NUMBER = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 4 });

/**
 * A covenant value for display: numbers rounded to at most 4 decimals with grouping (Core sends the
 * full double, e.g. 0.007648648648648649); anything that is not a number (true, a date) as it is.
 */
export function formatValue(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) && String(value).trim() !== '' ? NUMBER.format(n) : String(value);
}

/** What a rule needs to know about one measure to read it out. */
export interface MeasureInfo {
  name: string;
  unit: string | null;
}

const WORDS: Record<string, string> = {
  '>=': 'at least', '>': 'above', '<=': 'at most', '<': 'below', '==': 'equal to', '!=': 'not',
};

/** A value with its measure's unit: 3.75x, ₹45,000 cr, 6 mn t. */
export function withUnit(value: string | number | null | undefined, unit: string | null | undefined): string {
  const v = formatValue(value);
  if (v === '—' || !unit || unit === 'None') {
    return v;
  }
  if (unit === 'x') {
    return `${v}x`;
  }
  if (/^INR[ _]?cr$/i.test(unit)) {
    return `₹${v} cr`;
  }
  return `${v} ${unit}`;
}

const SIMPLE = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(>=|<=|==|!=|>|<)\s*(-?\d+(?:\.\d+)?|true|false)\s*$/;

/**
 * A one-measure rule in words, e.g. "NET_LEVERAGE <= 3.75" -> "Net Debt / EBITDA at most 3.75x".
 * Anything more complex, or a measure we do not know, gives null and the caller shows the rule as written.
 */
export function describeRule(expression: string | null | undefined, measures: Map<string, MeasureInfo>):
  { text: string; unit: string | null; limit: number | null; bound?: string } | null {
  const m = expression ? SIMPLE.exec(expression) : null;
  const measure = m ? measures.get(m[1]) : undefined;
  if (!m || !measure) {
    return null;
  }
  const [, , op, raw] = m;
  if (raw === 'true' || raw === 'false') {
    const yes = (raw === 'true') === (op === '==');
    return { text: `${measure.name}: must be ${yes ? 'yes' : 'no'}`, unit: null, limit: null, bound: yes ? 'yes' : 'no' };
  }
  const bound = `${WORDS[op]} ${withUnit(raw, measure.unit)}`;
  return { text: `${measure.name} ${bound}`, unit: measure.unit, limit: Number(raw), bound };
}

/** Headroom with its unit and, when the limit is known, as a share of it: "0.18x (14%)". */
export function describeHeadroom(headroom: number | null, unit: string | null, limit: number | null): string {
  if (headroom === null) {
    return '—';
  }
  const pct = limit ? ` (${Math.round((headroom / Math.abs(limit)) * 100)}%)` : '';
  return `${withUnit(headroom, unit)}${pct}`;
}

/**
 * Core's reason for a failed check, for a person: "Missing values for measures: [DEBT_EBITDA]" becomes
 * "No figure yet for Total Debt to EBITDA". Other messages are shown as they are.
 */
export function explainError(message: string | null | undefined, measures: Map<string, MeasureInfo>): string | null {
  if (!message) {
    return null;
  }
  const missing = /^Missing values? for measures?: \[(.*)\]$/.exec(message.trim());
  if (!missing) {
    return message;
  }
  const names = missing[1].split(',').map((c) => c.trim()).filter(Boolean)
    .map((c) => measures.get(c)?.name ?? c);
  return `No figure yet for ${names.join(', ')}`;
}

const SHORT_DATE = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });

/** "2026-11-06" as "6 Nov 2026". */
export function fmtDate(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const d = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(d.getTime()) ? iso : SHORT_DATE.format(d);
}

const DATE_TIME = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });

/** A timestamp as "3 Nov 2026, 11:30" in the viewer's time zone. */
export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : DATE_TIME.format(d);
}
