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
  { text: string; unit: string | null; limit: number | null } | null {
  const m = expression ? SIMPLE.exec(expression) : null;
  const measure = m ? measures.get(m[1]) : undefined;
  if (!m || !measure) {
    return null;
  }
  const [, , op, raw] = m;
  if (raw === 'true' || raw === 'false') {
    const yes = (raw === 'true') === (op === '==');
    return { text: `${measure.name}: must be ${yes ? 'yes' : 'no'}`, unit: null, limit: null };
  }
  return { text: `${measure.name} ${WORDS[op]} ${withUnit(raw, measure.unit)}`, unit: measure.unit, limit: Number(raw) };
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
