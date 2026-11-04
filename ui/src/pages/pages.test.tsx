import { fireEvent, screen, waitFor } from '@testing-library/react';
import { setIdentity } from '../auth/identity';
import type { OnboardingRow, ReviewRow } from '../api/types';
import { mockFetch, renderAt } from '../test-utils';
import { CpWorkspace } from './CpWorkspace';
import { Documents } from './Documents';
import { Library } from './Library';
import { Onboarding, initialDraft, thresholdSourceFor } from './Onboarding';
import { Portfolio } from './Portfolio';
import { Settings } from './Settings';

afterEach(() => vi.unstubAllGlobals());

const ROW: ReviewRow = {
  appliedCovenantId: 'ac-1', covenantMasterId: 'm1', covenantName: 'Minimum DSCR', conditionExpression: 'DSCR >= 1.25',
  category: 'FINANCIAL', assessmentType: 'AUTO', computedValue: '1.1', headroom: -0.15, latestResult: 'BREACHED',
  latestAssessmentId: 'a1', lastAssessedAt: null, dispositionStatus: 'BREACHED', extensionDate: null,
  statusSource: 'SYSTEM', note: null, dispositionable: true, nextDueDate: '2026-12-31', timingStatus: null,
  overdueByDays: 0, approvalStatus: null, requestedStatus: null, requestedBy: null,
};

describe('Portfolio', () => {
  it('shows totals and filters CPs', async () => {
    mockFetch(() => ({ body: [
      { cpId: 'CP-1', applied: 3, compliant: 2, breached: 1, error: 0, pendingReview: 1, dispositioned: 0 },
      { cpId: 'CP-2', applied: 2, compliant: 1, breached: 0, error: 1, pendingReview: 0, dispositioned: 1 },
    ] }));
    renderAt('/portfolio', '/portfolio', <Portfolio />);
    expect(await screen.findByRole('link', { name: 'CP-1' })).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText(/1 covenant is breached on 1 CP, and 1 could not be checked/)).toBeInTheDocument();
    // The health ladder lists the worst CP first.
    const ladder = screen.getAllByRole('button', { name: /^Open CP-/ });
    expect(ladder[0]).toHaveAccessibleName(/^Open CP-1: 1 breached/);
    fireEvent.click(screen.getByRole('button', { name: 'Show CPs: Data errors' }));
    expect(screen.queryByRole('link', { name: 'CP-1' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show CPs: Data errors' }));
    expect(screen.getByRole('link', { name: 'CP-1' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Review the breaches' }));
    expect(screen.queryByRole('link', { name: 'CP-2' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: 'All' }));
    fireEvent.change(screen.getByLabelText('Filter by CP id'), { target: { value: 'cp-2' } });
    expect(screen.queryByRole('link', { name: 'CP-1' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Filter by CP id'), { target: { value: 'zzz' } });
    expect(screen.getByText('No credit proposals to show.')).toBeInTheDocument();
  });

  it('shows errors', async () => {
    mockFetch(() => ({ status: 500, body: { message: 'down' } }));
    renderAt('/portfolio', '/portfolio', <Portfolio />);
    expect(await screen.findByText('down')).toBeInTheDocument();
  });
});

describe('CP workspace maker-checker', () => {
  it('RM requests a waiver', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/status') && init.method === 'PUT') {
        return { body: { ...ROW, approvalStatus: 'REQUESTED' } };
      }
      if (url.endsWith('/review')) {
        return { body: [ROW] };
      }
      return { body: [] };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByText('Waive / extend'));
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'one-off' } });
    fireEvent.click(screen.getByText('Submit'));
    await waitFor(() => expect(calls.some((c) => c.init.method === 'PUT')).toBe(true));
    const put = calls.find((c) => c.init.method === 'PUT')!;
    expect(JSON.parse(put.init.body as string)).toEqual({ status: 'WAIVER', extensionDate: null, note: 'one-off' });
    expect(await screen.findByText('Request sent for approval.')).toBeInTheDocument();
  });

  it('extension needs a date', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    mockFetch((url) => ({ body: url.endsWith('/review') ? [ROW] : [] }));
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByText('Waive / extend'));
    fireEvent.click(screen.getByText('Extension'));
    expect(screen.getByText('Submit').closest('button')).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Extended to/), { target: { value: '2027-01-31' } });
    expect(screen.getByText('Submit').closest('button')).not.toBeDisabled();
    fireEvent.click(screen.getByText('Cancel'));
  });

  it('approver approves someone else request; requester only waits', async () => {
    const pending = { ...ROW, approvalStatus: 'REQUESTED', requestedStatus: 'WAIVER', requestedBy: 'rm-anil' };
    setIdentity({ user: 'approver-1', roles: ['APPROVER'] });
    const calls = mockFetch((url) => {
      if (url.endsWith('/review')) {
        return { body: [pending] };
      }
      return { body: url.endsWith('/status-audit') ? [] : { ...ROW } };
    });
    const view = renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByText('Approve'));
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/status/decision'))).toBe(true));
    const decision = calls.find((c) => c.url.endsWith('/status/decision'))!;
    expect(JSON.parse(decision.init.body as string).decision).toBe('APPROVE');
    view.unmount();

    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    expect(await screen.findByText('Awaiting approver')).toBeInTheDocument();
  });

  it('reads the rule out, explains a data error, and flags a result from an earlier rule', async () => {
    mockFetch((url) => {
      if (url.startsWith('/api/v1/measures')) {
        return { body: [{ code: 'DSCR', name: 'Debt Service Coverage Ratio', unit: 'x', active: true }] };
      }
      return { body: url.endsWith('/review') ? [
        { ...ROW, latestResult: 'ERROR', computedValue: null, headroom: null, dispositionable: false,
          errorMessage: 'Missing values for measures: [DSCR]' },
        { ...ROW, appliedCovenantId: 'ac-2', latestResult: 'COMPLIANT', computedValue: '1.4', headroom: 0.15,
          dispositionable: false, assessedCondition: 'DSCR >= 1.0' }] : [] };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    expect(await screen.findByText('No figure yet for Debt Service Coverage Ratio')).toBeInTheDocument();
    expect(screen.getAllByText('Debt Service Coverage Ratio at least 1.25x')).toHaveLength(2);
    expect(screen.getByText('1.4x')).toBeInTheDocument();
    expect(screen.getByText('0.15x (12%)')).toBeInTheDocument();
    expect(screen.getByText(/Checked against the earlier rule DSCR >= 1.0/)).toBeInTheDocument();
  });

  it('shows the status history and an empty state', async () => {
    mockFetch((url) => ({
      body: url.endsWith('/status-audit') ? [{ oldStatus: 'BREACHED', newStatus: 'WAIVER', source: 'USER',
        rationale: 'ok', changedBy: 'approver-1', changedDate: '2026-11-02T10:00:00Z' }] : [],
    }));
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    expect(await screen.findByText('Onboard covenants')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Status history'));
    expect(await screen.findByText('BREACHED → WAIVER')).toBeInTheDocument();
  });

  it('asks for a CP when none is open', async () => {
    renderAt('/cp', '/cp', <CpWorkspace />);
    fireEvent.change(screen.getByLabelText('Credit proposal (CP id)'), { target: { value: 'CP-7' } });
    fireEvent.click(screen.getByText('Open'));
    expect(await screen.findByText('navigated')).toBeInTheDocument();
  });
});

const EXTRACTED: OnboardingRow = {
  covenantId: 'm1', name: 'Minimum DSCR', matchReason: 'From loan agreement, page 3', assessmentType: 'AUTO',
  defaultFrequency: 'QUARTERLY', defaultCron: null, custom: false, alreadyApplied: false, source: 'EXTRACTED',
  conditionOverride: 'DSCR >= 1.25', thresholdSource: 'EXTRACTED', mlRecommendedExpression: 'DSCR > 1.6',
  extractedExpression: 'DSCR >= 1.25', extractionConfidence: 0.93,
  evidence: { documentId: 'D1', fileId: null, page: 3, quote: 'not less than 1.25 times' }, customDefinition: null,
};
const CUSTOM: OnboardingRow = {
  ...EXTRACTED, covenantId: null, name: 'Negative Pledge', source: 'EXTRACTED_CUSTOM', assessmentType: 'MANUAL',
  matchReason: 'From loan agreement',
  conditionOverride: null, thresholdSource: 'MASTER', customDefinition: 'No liens', evidence: null,
  extractionConfidence: null, mlRecommendedExpression: null,
};

describe('Onboarding', () => {
  beforeEach(() => setIdentity({ user: 'rm-anil', roles: ['RM'] }));

  it('helpers pick defaults and threshold provenance', () => {
    expect(initialDraft(EXTRACTED)).toEqual({ selected: true, frequency: 'QUARTERLY', expression: 'DSCR >= 1.25' });
    expect(initialDraft({ ...EXTRACTED, source: 'RECOMMENDED', defaultFrequency: 'CUSTOM' }).selected).toBe(false);
    expect(thresholdSourceFor(EXTRACTED, 'DSCR >= 1.25')).toBe('EXTRACTED');
    expect(thresholdSourceFor(EXTRACTED, 'DSCR >= 1.3')).toBe('USER');
    expect(thresholdSourceFor(EXTRACTED, ' ')).toBeNull();
    expect(thresholdSourceFor({ ...EXTRACTED, thresholdSource: 'MASTER' }, 'DSCR >= 1.25')).toBeNull();
  });

  it('applies extracted and custom rows', async () => {
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/onboarding-recommendations')) {
        return { body: [EXTRACTED, CUSTOM] };
      }
      if (url.endsWith('/custom-covenants')) {
        return { status: 201, body: { covenantId: 'c9' } };
      }
      if (url.endsWith('/applied-covenants') && init.method === 'POST') {
        return { status: 201, body: [{}, {}] };
      }
      return undefined;
    });
    renderAt('/onboarding/CP-1', '/onboarding/:cpId', <Onboarding />);
    expect(await screen.findByText('From loan agreement, page 3')).toBeInTheDocument();
    expect(screen.getByText('93% confidence')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Apply 2 covenant(s)'));
    expect(await screen.findByText('navigated')).toBeInTheDocument();
    const custom = calls.find((c) => c.url.endsWith('/custom-covenants'))!;
    expect(JSON.parse(custom.init.body as string)).toMatchObject({ name: 'Negative Pledge', assessmentType: 'MANUAL',
      definition: 'No liens' });
    const apply = calls.find((c) => c.url.endsWith('/applied-covenants'))!;
    expect(JSON.parse(apply.init.body as string).items).toEqual([
      { covenantMasterId: 'm1', frequency: 'QUARTERLY', conditionExpression: 'DSCR >= 1.25',
        thresholdSource: 'EXTRACTED' },
      { covenantMasterId: 'c9', frequency: 'QUARTERLY' },
    ]);
  });

  it('edited rule is recorded as USER and unticked rows are skipped', async () => {
    const calls = mockFetch((url) => {
      if (url.endsWith('/onboarding-recommendations')) {
        return { body: [EXTRACTED, CUSTOM] };
      }
      return { status: 201, body: [{}] };
    });
    renderAt('/onboarding/CP-1', '/onboarding/:cpId', <Onboarding />);
    fireEvent.change(await screen.findByLabelText('Rule for Minimum DSCR'), { target: { value: 'DSCR >= 1.4' } });
    fireEvent.click(screen.getByLabelText('Apply Negative Pledge'));
    fireEvent.click(screen.getByText('Apply 1 covenant(s)'));
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/applied-covenants'))).toBe(true));
    const apply = calls.find((c) => c.url.endsWith('/applied-covenants'))!;
    expect(JSON.parse(apply.init.body as string).items[0]).toMatchObject({
      conditionExpression: 'DSCR >= 1.4', thresholdSource: 'USER' });
  });
});

describe('Documents', () => {
  it('lists documents and reviews extracted terms', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const calls = mockFetch((url, init) => {
      if (url.startsWith('/dps/v1/documents?cpId')) {
        return { body: [{ id: 'D1', type: 'LOAN_AGREEMENT', fileName: 'agreement.pdf', pageCount: 6, asOfDate: null,
          status: 'NEEDS_REVIEW', cpIds: ['CP-1', 'CP-2'], latestJobId: 'J1', uploadedBy: 'rm-anil',
          uploadedAt: '2026-11-02T10:00:00Z' }] };
      }
      if (url.endsWith('/terms')) {
        return { body: [{ id: 'T1', documentId: 'D1', metricRaw: 'DSCR', covenantMasterId: 'm1',
          expression: 'DSCR >= 1.25', operator: '>=', threshold: 1.25, unit: null, frequency: null,
          category: 'FINANCIAL', confidence: 0.85, method: 'REGEX', status: 'NEEDS_REVIEW',
          issues: ['LOW_CONFIDENCE'], source: { documentId: 'D1', page: 3, quote: 'not less than 1.25' },
          reviewedBy: null, reviewNote: null }] };
      }
      if (url.startsWith('/dps/v1/jobs/J1/resume')) {
        return { status: 202, body: {} };
      }
      if (url.startsWith('/dps/v1/jobs/J1')) {
        return { body: { id: 'J1', documentId: 'D1', flow: 'AGREEMENT_INTAKE', status: 'NEEDS_REVIEW',
          pool: 'STANDARD', attempts: 1, currentStep: 'hold', error: null, steps: [] } };
      }
      if (url.startsWith('/dps/v1/terms/T1') && init.method === 'PATCH') {
        return { body: {} };
      }
      return undefined;
    });
    renderAt('/documents/CP-1', '/documents/:cpId', <Documents />);
    fireEvent.click(await screen.findByRole('cell', { name: 'agreement.pdf' }));
    expect(await screen.findByText('LOW_CONFIDENCE')).toBeInTheDocument();
    fireEvent.click(screen.getAllByText('Approve')[0]);
    await waitFor(() => expect(calls.some((c) => c.init.method === 'PATCH')).toBe(true));
    expect(JSON.parse(calls.find((c) => c.init.method === 'PATCH')!.init.body as string).action).toBe('APPROVE');
    fireEvent.click(await screen.findByText('Review done'));
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/resume'))).toBe(true));
  });

  it('upload needs a file, CPs and a period for statements', async () => {
    mockFetch(() => ({ body: [] }));
    renderAt('/documents/CP-1', '/documents/:cpId', <Documents />);
    expect(await screen.findByText('No documents yet.')).toBeInTheDocument();
    expect(screen.getByText('Upload').closest('button')).toBeDisabled();
  });

  it('choosing a spreadsheet switches the type to financial statement', async () => {
    mockFetch(() => ({ body: [] }));
    renderAt('/documents/CP-1', '/documents/:cpId', <Documents />);
    expect(await screen.findByText('No documents yet.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Period end')).not.toBeInTheDocument();
    const input = document.querySelector('input[type=file]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['Revenue,100'], 'figures.csv', { type: 'text/csv' })] } });
    expect(await screen.findByLabelText('Period end')).toBeInTheDocument();
  });
});

describe('Library and settings', () => {
  it('pages through covenants', async () => {
    mockFetch(() => ({ body: { content: [{ id: '1', name: 'Minimum DSCR', category: 'financial', description: 'd',
      conditionExpression: 'DSCR >= 1.5', assessmentType: 'AUTO', defaultFrequency: 'QUARTERLY', active: false,
      custom: false }], totalElements: 30, number: 0, last: false } }));
    renderAt('/library', '/library', <Library />);
    expect(await screen.findByText('Minimum DSCR')).toBeInTheDocument();
    expect(screen.getByText('inactive')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Next'));
    fireEvent.change(screen.getByLabelText('Search covenants'), { target: { value: 'dscr' } });
  });

  it('filters by category, hides switched-off covenants, and reads rules out', async () => {
    const calls = mockFetch((url) => (url.startsWith('/api/v1/measures')
      ? { body: [{ code: 'DSCR', name: 'Debt Service Coverage Ratio', unit: 'x', active: true }] }
      : { body: { content: [{ id: '1', name: 'Minimum DSCR', category: 'FINANCIAL', description: 'd',
        conditionExpression: 'DSCR >= 1.5', assessmentType: 'MANUAL', defaultFrequency: 'QUARTERLY', active: true,
        custom: false }], totalElements: 1, number: 0, last: true } }));
    renderAt('/library', '/library', <Library />);
    expect(await screen.findByText('Debt Service Coverage Ratio at least 1.5x')).toBeInTheDocument();
    expect(screen.getByText('Manual check')).toBeInTheDocument();
    expect(screen.getByText('Quarterly')).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/api/v1/covenants?') && c.url.includes('&active=true'))).toBe(true);
    fireEvent.click(screen.getByText('Non-financial'));
    await waitFor(() => expect(calls.some((c) => c.url.includes('category=NON_FINANCIAL'))).toBe(true));
    fireEvent.click(screen.getByLabelText('Include switched-off covenants'));
    await waitFor(() => expect(calls.some((c) => c.url.includes('category=NON_FINANCIAL')
      && !c.url.includes('active='))).toBe(true));
  });

  it('lets an author add a covenant, and hides the button from an RM', async () => {
    const calls = mockFetch((url, init) => {
      if (url.startsWith('/api/v1/measures')) {
        return { body: [{ code: 'CURRENT_RATIO', name: 'Current ratio', unit: null, active: true }] };
      }
      if (init.method === 'POST') {
        return { status: 201, body: { id: 'n1', name: 'Minimum Current Ratio' } };
      }
      return { body: { content: [], totalElements: 0, number: 0, last: true } };
    });
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const view = renderAt('/library', '/library', <Library />);
    expect(await screen.findByText(/0 covenants/)).toBeInTheDocument();
    expect(screen.queryByText('New covenant')).not.toBeInTheDocument();
    view.unmount();

    setIdentity({ user: 'author-1', roles: ['AUTHOR'] });
    renderAt('/library', '/library', <Library />);
    fireEvent.click(await screen.findByRole('button', { name: 'New covenant' }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: ' Minimum Current Ratio ' } });
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Stay liquid' } });
    fireEvent.change(screen.getByLabelText(/^Rule/), { target: { value: 'CURRENT_RATIO >= 1.2' } });
    expect(await screen.findByText(/Measures: CURRENT_RATIO/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Add to library' }));
    await waitFor(() => expect(calls.some((c) => c.init.method === 'POST')).toBe(true));
    expect(JSON.parse(calls.find((c) => c.init.method === 'POST')!.init.body as string)).toEqual({
      name: 'Minimum Current Ratio', category: 'FINANCIAL', description: 'Stay liquid',
      conditionExpression: 'CURRENT_RATIO >= 1.2', assessmentType: 'AUTO', defaultFrequency: 'QUARTERLY',
      businessSegments: [], defaultCron: null, policyReference: null,
    });
    expect(await screen.findByText('Minimum Current Ratio added to the library.')).toBeInTheDocument();
  });

  it('saves the maker-checker switch', async () => {
    const calls = mockFetch((url, init) => (url.startsWith('/api/v1/fx') ? { body: [] } : { body: {
      fiscalYearStartMonth: 4, periodicIncludesCurrentPeriod: false, dueSoonDays: 7, upcomingWindowDays: 30,
      dispositionApprovalRequired: init.method === 'PUT' ? false : true, reportingCurrency: 'INR',
      modifiedDate: null, modifiedBy: null } }));
    renderAt('/settings', '/settings', <Settings />);
    fireEvent.click(await screen.findByLabelText(/maker-checker/));
    fireEvent.click(screen.getByText('Save settings'));
    await waitFor(() => expect(calls.some((c) => c.init.method === 'PUT')).toBe(true));
    expect(JSON.parse(calls.find((c) => c.init.method === 'PUT')!.init.body as string).dispositionApprovalRequired)
      .toBe(false);
  });
});
