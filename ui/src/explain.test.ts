import { describe, expect, it } from 'vitest';
import { confidenceWords, explain, humanizeCode, ruleWords, termName, unitWords } from './explain';

describe('explain', () => {
  it('turns engine codes into what is wrong and what to do', () => {
    const e = explain('SECOND_READER_DIFFERS: REVENUE read 163.631, second reader 172.45');
    expect(e.key).toBe('REVENUE');
    expect(e.what).toBe('Revenue: the table reader read 163.631, the second reader (AI) read 172.45.');
    expect(explain('NEGATIVE_VALUE: ST_DEBT').what).toMatch(/Short-term borrowings came out negative/);
    expect(explain('MISSING_LINE_ITEMS: EBITDA, NET_WORTH').what).toBe('Not found in the statement: EBITDA, Net worth (total equity).');
    expect(explain('AMOUNT_NOT_IN_INR: convert').todo).toMatch(/INR crore/);
    expect(explain('SOMETHING_NEW').what).toBe('SOMETHING_NEW');
  });

  it('writes limits and names as people read them', () => {
    expect(ruleWords({ operator: '<=', threshold: 75, unit: 'PERCENT' })).toBe('at most 75%');
    expect(ruleWords({ operator: '>=', threshold: 500, unit: 'INR_CR' })).toBe('at least Rs. 500 crore');
    expect(ruleWords({ operator: '<=', threshold: 3.5, unit: null })).toBe('at most 3.5×');
    expect(ruleWords({ operator: null, threshold: null, unit: null })).toBeNull();
    expect(humanizeCode('DEBT_TO_EBITDA:CONSOLIDATED_SENIOR_SECURED_LEVERAGE_RATIO'))
      .toBe('Consolidated senior secured leverage ratio');
    expect(termName({ name: 'Maximum Loan to Value', metricRaw: 'LOAN_TO_VALUE' })).toBe('Maximum Loan to Value');
    expect(termName({ name: null, metricRaw: 'MIN_NET_WORTH' })).toBe('Min net worth');
    expect(unitWords('lakh')).toBe('Rs. lakh');
    expect(confidenceWords(0.7).label).toBe('Low');
  });

  it('explains the safety-net checks', () => {
    expect(explain('SCALE_JUMP: REVENUE 9003641728.0 against 900.36 for 2024-03-31').what)
      .toBe('Revenue is 9003641728.0 crore; for the period ending 2024-03-31 it was 900.36: more than 20 times different.');
    expect(explain('SCALE_JUMP: REVENUE 9003641728.0 against 900.36 for 2024-03-31').key).toBe('REVENUE');
    expect(explain('PART_ABOVE_TOTAL: INVENTORY 1620.5 is more than total assets 481.1').key).toBe('INVENTORY');
    expect(explain('BALANCE_SHEET_DOES_NOT_BALANCE: assets 1 vs equity and liabilities 2').todo).toMatch(/wrong line or column/);
    expect(explain('POSSIBLE_COVENANT_NOT_UNDERSTOOD').what).toMatch(/reads like a financial covenant/);
  });
});
