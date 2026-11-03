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
