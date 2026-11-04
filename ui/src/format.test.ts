import { describe, expect, it } from 'vitest';
import { describeHeadroom, describeRule, explainError, formatValue, withUnit } from './format';

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

describe('rules in words', () => {
  const measures = new Map([
    ['NET_LEVERAGE', { name: 'Net Debt / EBITDA', unit: 'x' }],
    ['TNW', { name: 'Tangible Net Worth', unit: 'INR cr' }],
    ['OUTPUT', { name: 'Crude Steel Output', unit: 'mn t' }],
    ['LICENCE', { name: 'Export Authorisation Valid', unit: 'None' }],
    ['DEBT_EBITDA', { name: 'Total Debt to EBITDA', unit: 'x' }],
  ]);

  it('reads one-measure rules out with units', () => {
    expect(describeRule('NET_LEVERAGE <= 3.75', measures)).toEqual({ text: 'Net Debt / EBITDA at most 3.75x', unit: 'x', limit: 3.75 });
    expect(describeRule('TNW >= 45000', measures)?.text).toBe('Tangible Net Worth at least ₹45,000 cr');
    expect(describeRule('OUTPUT > 6', measures)?.text).toBe('Crude Steel Output above 6 mn t');
    expect(describeRule('LICENCE == true', measures)?.text).toBe('Export Authorisation Valid: must be yes');
    expect(describeRule('LICENCE != true', measures)?.text).toBe('Export Authorisation Valid: must be no');
  });

  it('leaves complex or unknown rules alone', () => {
    expect(describeRule('NET_LEVERAGE <= 3 AND TNW > 1', measures)).toBeNull();
    expect(describeRule('UNKNOWN >= 1', measures)).toBeNull();
    expect(describeRule(null, measures)).toBeNull();
  });

  it('shows headroom with unit and share of the limit', () => {
    expect(describeHeadroom(0.4, 'x', 4)).toBe('0.4x (10%)');
    expect(describeHeadroom(-0.2, 'x', 3.75)).toBe('-0.2x (-5%)');
    expect(describeHeadroom(1, null, null)).toBe('1');
    expect(describeHeadroom(null, 'x', 4)).toBe('—');
    expect(withUnit(null, 'x')).toBe('—');
  });

  it('explains missing figures by measure name', () => {
    expect(explainError('Missing values for measures: [DEBT_EBITDA, ZZZ]', measures))
      .toBe('No figure yet for Total Debt to EBITDA, ZZZ');
    expect(explainError('Rule does not compile', measures)).toBe('Rule does not compile');
    expect(explainError(null, measures)).toBeNull();
  });
});
