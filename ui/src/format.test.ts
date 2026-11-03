import { describe, expect, it } from 'vitest';
import { formatValue } from './format';

describe('formatValue', () => {
  it('rounds and groups numbers', () => {
    expect(formatValue('0.007648648648648649')).toBe('0.0076');
    expect(formatValue(-1.1923513513513513)).toBe('-1.1924');
    expect(formatValue('62000.0')).toBe('62,000');
    expect(formatValue(1.5)).toBe('1.5');
  });

  it('keeps non-numbers and shows a dash for nothing', () => {
    expect(formatValue('true')).toBe('true');
    expect(formatValue('2026-03-31')).toBe('2026-03-31');
    expect(formatValue(null)).toBe('—');
    expect(formatValue(undefined)).toBe('—');
    expect(formatValue('')).toBe('—');
  });
});
