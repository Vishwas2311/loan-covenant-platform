import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { setIdentity } from '../auth/identity';
import type { ReviewRow } from '../api/types';
import { mockFetch, renderAt } from '../test-utils';
import { ruleLimit, TrendChart } from './CovenantPanel';
import { CpWorkspace } from './CpWorkspace';
import { DataSources } from './DataSources';
import { Documents } from './Documents';
import { Library } from './Library';
import { Measures } from './Measures';
import { Onboarding } from './Onboarding';
import { Portfolio } from './Portfolio';
import { Support } from './Support';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const MANUAL: ReviewRow = {
  appliedCovenantId: 'ac-ins', covenantMasterId: 'm-ins', covenantName: 'Insurance Cover Maintained',
  conditionExpression: 'Insurance in force', category: 'NON_FINANCIAL', assessmentType: 'MANUAL',
  computedValue: null, headroom: null, latestResult: null, latestAssessmentId: null, lastAssessedAt: null,
  dispositionStatus: 'COMPLIED', extensionDate: null, statusSource: 'SYSTEM', note: null, dispositionable: false,
  nextDueDate: '2026-12-31', timingStatus: null, overdueByDays: 0, approvalStatus: null, requestedStatus: null,
  requestedBy: null, attachments: [],
};
const DSCR: ReviewRow = {
  ...MANUAL, appliedCovenantId: 'ac-dscr', covenantMasterId: 'm1', covenantName: 'Minimum DSCR',
  conditionExpression: 'DSCR >= 1.50', category: 'FINANCIAL', assessmentType: 'AUTO', computedValue: '1.6',
  latestResult: 'COMPLIANT', latestAssessmentId: 'as-9',
  attachments: [{ id: 'att1', fileId: 'f1', fileName: 'letter.pdf', uploadedBy: 'rm-anil',
    uploadedAt: '2026-11-01T10:00:00Z', periodEnd: null }],
};
const HISTORY = [
  { id: 'h1', appliedCovenantId: 'ac-dscr', executedAt: '2026-06-30T06:00:00Z', scheduledAt: null, computedValue: '1.7',
    result: 'COMPLIANT', errorMessage: null, assessmentNote: null, triggeredBy: 'SCHEDULED', triggeredByUser: null,
    status: 'COMPLETED' },
  { id: 'h2', appliedCovenantId: 'ac-dscr', executedAt: '2026-09-30T06:00:00Z', scheduledAt: null, computedValue: '1.6',
    result: 'COMPLIANT', errorMessage: null, assessmentNote: null, triggeredBy: 'SCHEDULED', triggeredByUser: null,
    status: 'COMPLETED' },
];

const sent = (calls: { url: string; init: RequestInit }[], method: string, part: string) =>
  calls.find((c) => (c.init.method ?? 'GET') === method && c.url.includes(part));

describe('trend chart', () => {
  it('reads the limit from a simple rule and draws the points', () => {
    expect(ruleLimit('DSCR >= 1.50')).toBe(1.5);
    expect(ruleLimit('DEBT_EBITDA <= 3')).toBe(3);
    expect(ruleLimit('Insurance in force')).toBeNull();
    render(<MantineProvider><TrendChart limit={1.5} points={[{ label: 'a', value: 1.7 }, { label: 'b', value: 1.4 }]} /></MantineProvider>);
    expect(screen.getByRole('img', { name: 'Trend of 2 results, limit 1.5' })).toBeInTheDocument();
    render(<MantineProvider><TrendChart limit={null} points={[{ label: 'a', value: 1 }]} /></MantineProvider>);
    expect(screen.getByText(/at least two numeric results/)).toBeInTheDocument();
  });
});

describe('CP workspace: one covenant', () => {
  it('records the result of a person-checked covenant, opening a check first', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/review')) {
        return { body: [MANUAL] };
      }
      if (url.includes('/assessments/run')) {
        return { body: { id: 'new-as', result: 'PENDING_REVIEW' } };
      }
      if (url.includes('/complete-manual') && init.method === 'POST') {
        return { body: { id: 'new-as', result: 'COMPLIANT' } };
      }
      return { body: [] };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByRole('button', { name: 'Record result' }));
    expect(screen.getByRole('button', { name: 'Save result' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/What you checked/), { target: { value: 'Policy renewed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save result' }));
    expect(await screen.findByText('Result recorded.')).toBeInTheDocument();
    expect(sent(calls, 'POST', '/assessments/run?appliedCovenantId=ac-ins')).toBeTruthy();
    expect(JSON.parse(sent(calls, 'POST', '/assessments/new-as/complete-manual')!.init.body as string))
      .toEqual({ result: 'COMPLIANT', note: 'Policy renewed' });
  });

  it('opens a covenant: trend, check now, evidence and pausing', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/review')) {
        return { body: [DSCR] };
      }
      if (url.includes('/history')) {
        return { body: HISTORY };
      }
      if (url.includes('/assessments/run')) {
        return { body: { id: 'x', result: 'BREACHED', computedValue: '1.2' } };
      }
      if (url.includes('/attachments/att1') && init.method === 'DELETE') {
        return { body: [] };
      }
      if (url.endsWith('/applied-covenants') && !init.method) {
        return { body: [{ id: 'ac-dscr', cpId: 'CP-1', frequency: 'QUARTERLY', cronExpression: null, status: 'ACTIVE' }] };
      }
      if (url.endsWith('/applied-covenants/ac-dscr') && init.method === 'PATCH') {
        return { body: { id: 'ac-dscr', status: 'SUSPENDED' } };
      }
      if (url.endsWith('/audit')) {
        return { body: [{ id: 'c1', changeType: 'STATUS', oldValue: null, newValue: 'active',
          changedDate: '2025-04-01T10:00:00Z', changedBy: 'seed' }] };
      }
      return { body: [] };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByRole('button', { name: 'Minimum DSCR' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByRole('img', { name: 'Trend of 2 results, limit 1.5' })).toBeInTheDocument();

    fireEvent.click(within(drawer).getByRole('button', { name: 'Check now' }));
    expect(await screen.findByText('Checked: BREACHED (1.2)')).toBeInTheDocument();

    fireEvent.click(within(drawer).getByRole('tab', { name: /Evidence/ }));
    expect(await within(drawer).findByText('letter.pdf')).toBeInTheDocument();
    fireEvent.click(within(drawer).getByRole('button', { name: 'Remove' }));
    expect(await within(drawer).findByText('No evidence yet.')).toBeInTheDocument();

    fireEvent.click(within(drawer).getByRole('tab', { name: 'Schedule and changes' }));
    expect(await within(drawer).findByText('Monitoring', { selector: 'td' })).toBeInTheDocument();
    fireEvent.click(within(drawer).getByRole('button', { name: 'Pause monitoring' }));
    await waitFor(() => expect(sent(calls, 'PATCH', '/applied-covenants/ac-dscr')).toBeTruthy());
    expect(JSON.parse(sent(calls, 'PATCH', '/applied-covenants/ac-dscr')!.init.body as string))
      .toEqual({ status: 'SUSPENDED' });
  });

  it('shows AI suggestions and renewal cycles', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    mockFetch((url) => {
      if (url.endsWith('/review')) {
        return { body: [DSCR] };
      }
      if (url.endsWith('/ai-suggestions')) {
        return { body: [{ appliedCovenantId: 'ac-dscr', recommendedStatus: 'BREACHED', rationale: 'DSCR falling',
          confidence: 0.8 }] };
      }
      if (url.endsWith('/cycles')) {
        return { body: [{ cycleNumber: 1, cpId: 'CP-0', current: false }, { cycleNumber: 2, cpId: 'CP-1', current: true }] };
      }
      return { body: [] };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByRole('tab', { name: 'AI suggestions' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Get AI suggestions' }));
    expect(await screen.findByText('DSCR falling')).toBeInTheDocument();
    expect(screen.getByText('80%')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Cycles' }));
    expect(await screen.findByRole('link', { name: 'CP-0' })).toBeInTheDocument();
    expect(screen.getByText('current')).toBeInTheDocument();
  });
});

const LIB = {
  id: 'cov1', name: 'Minimum DSCR', category: 'FINANCIAL', description: 'Debt service cover',
  conditionExpression: 'DSCR >= 1.5', assessmentType: 'AUTO', defaultFrequency: 'QUARTERLY', defaultCron: null,
  businessSegments: ['ALL'], islamicBanking: false, industries: ['ALL'], countriesOfRisk: ['ALL'],
  countriesOfIncorporation: ['ALL'], creditProposalTypes: ['ALL'], legalEntityTypes: ['ALL'], facilityTypes: ['ALL'],
  collateralTypes: ['ALL'], active: true, custom: false,
};

describe('library detail', () => {
  it('shows usage and changes, edits with the existing fields, and switches off', async () => {
    setIdentity({ user: 'author-1', roles: ['AUTHOR'] });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/usage')) {
        return { body: { covenantId: 'cov1', totalApplied: 3, activelyApplied: 2, sampleCpIds: ['CP-1001'] } };
      }
      if (url.endsWith('/cov1/audit')) {
        return { body: [{ id: 'x1', oldExpression: 'DSCR >= 1.25', newExpression: 'DSCR >= 1.5',
          changedDate: '2026-01-01T00:00:00Z', changedBy: 'author-1' }] };
      }
      if (url.endsWith('/covenants/cov1')) {
        return { body: init.method === 'DELETE' ? { ...LIB, active: false }
          : init.method === 'PUT' ? { ...LIB, recheck: { checked: 2, breached: 1, errors: 0 } } : LIB };
      }
      if (url.startsWith('/api/v1/measures')) {
        return { body: [] };
      }
      return { body: { content: [LIB], totalElements: 1, number: 0, last: true } };
    });
    renderAt('/library', '/library', <Library />);
    fireEvent.click(await screen.findByRole('button', { name: 'Minimum DSCR' }));
    expect(await screen.findByText(/On 3 loan\(s\), 2 still monitored/)).toBeInTheDocument();
    // An author cannot open CPs, so the CP is named but not linked.
    expect(screen.getByText(/CP-1001/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'CP-1001' })).not.toBeInTheDocument();
    expect(await screen.findByText('DSCR >= 1.25 → DSCR >= 1.5')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.change(screen.getByLabelText(/^Rule/), { target: { value: 'DSCR >= 1.4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(sent(calls, 'PUT', '/covenants/cov1')).toBeTruthy());
    const put = JSON.parse(sent(calls, 'PUT', '/covenants/cov1')!.init.body as string);
    expect(put).toMatchObject({ name: 'Minimum DSCR', conditionExpression: 'DSCR >= 1.4', industries: ['ALL'],
      businessSegments: [], defaultCron: null });
    expect(await screen.findByText(/The new rule was checked on 2 CP covenant\(s\): 1 breached\./)).toBeInTheDocument();

    fireEvent.click(await screen.findByRole('button', { name: 'Switch off' }));
    expect(await screen.findByText(/switched off/)).toBeInTheDocument();
  });

  it('a custom schedule is checked before saving', async () => {
    setIdentity({ user: 'author-1', roles: ['AUTHOR'] });
    const calls = mockFetch((url) => {
      if (url.endsWith('/validate-cron')) {
        return { body: { valid: false, error: 'bad cron', nextFireTimes: null } };
      }
      if (url.startsWith('/api/v1/measures')) {
        return { body: [] };
      }
      return { body: { content: [], totalElements: 0, number: 0, last: true } };
    });
    renderAt('/library', '/library', <Library />);
    fireEvent.click(await screen.findByRole('button', { name: 'New covenant' }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Mid-month check' } });
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'd' } });
    fireEvent.change(screen.getByLabelText(/^Rule/), { target: { value: 'DSCR >= 1' } });
    fireEvent.click(screen.getByRole('textbox', { name: 'How often' }));
    fireEvent.click(await screen.findByRole('option', { name: 'CUSTOM' }));
    fireEvent.change(screen.getByLabelText(/Custom schedule/), { target: { value: 'nope' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to library' }));
    expect(await screen.findByText('Custom schedule: bad cron')).toBeInTheDocument();
    expect(sent(calls, 'POST', '/api/v1/covenants')).toBeUndefined();
  });
});

describe('measures and data sources', () => {
  it('edits and switches off a measure', async () => {
    setIdentity({ user: 'author-1', roles: ['AUTHOR'] });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const measure = { id: 'me1', code: 'DSCR', name: 'DSCR', description: 'cover', dataSourceTableId: 't1',
      dataSourceId: 's1', tableName: 'ratios', sourceColumn: 'dscr', sourceCode: null, defaultAggregation: 'LATEST',
      dataType: 'NUMBER', unit: 'x', active: true };
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/data-sources') {
        return { body: [{ id: 's1', name: 'Bank DB', type: 'MSSQL', description: null, connectionConfig: {}, active: true }] };
      }
      if (url.endsWith('/s1/tables')) {
        return { body: [{ id: 't1', dataSourceId: 's1', name: 'Ratios', schemaName: 'dbo', tableName: 'ratios',
          entityKeyColumn: 'cp_id', asOfColumn: 'as_of', layout: 'COLUMNAR', active: true }] };
      }
      if (url.endsWith('/columns')) {
        return { body: [{ key: 'dscr', label: 'dscr', dataType: 'NUMBER', description: null }] };
      }
      if (url.endsWith('/measures/me1')) {
        return { body: init.method === 'DELETE' ? { ...measure, active: false } : measure };
      }
      return { body: [measure] };
    });
    renderAt('/measures', '/measures', <Measures />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Debt service cover' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save measure' })).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save measure' }));
    await waitFor(() => expect(sent(calls, 'PUT', '/measures/me1')).toBeTruthy());
    expect(JSON.parse(sent(calls, 'PUT', '/measures/me1')!.init.body as string))
      .toMatchObject({ code: 'DSCR', name: 'Debt service cover', dataSourceTableId: 't1', sourceColumn: 'dscr' });
    fireEvent.change(screen.getByLabelText('Search measures'), { target: { value: 'zzz' } });
    expect(screen.queryByText('ratios')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Search measures'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Switch off' }));
    expect(await screen.findByText('DSCR switched off.')).toBeInTheDocument();
  });

  it('keeps a stored password, and tests a connection', async () => {
    const source = { id: 's1', name: 'Bank DB', type: 'MSSQL', description: 'core',
      connectionConfig: { jdbcUrl: 'jdbc:sqlserver://db:1433', username: 'reader', password: '********' }, active: true };
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/test')) {
        return { body: { reachable: false, columnCount: 0, message: 'timeout' } };
      }
      if (url.endsWith('/tables')) {
        return { body: [] };
      }
      if (url.endsWith('/data-sources/s1') && init.method === 'PUT') {
        return { body: source };
      }
      return { body: [source] };
    });
    renderAt('/data-sources', '/data-sources', <DataSources />);
    fireEvent.click(await screen.findByRole('button', { name: 'Test' }));
    expect(await screen.findByText('Not reachable: timeout')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.getByText('Stored password kept unless you type a new one')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent(calls, 'PUT', '/data-sources/s1')).toBeTruthy());
    expect(JSON.parse(sent(calls, 'PUT', '/data-sources/s1')!.init.body as string).connectionConfig)
      .toEqual({ jdbcUrl: 'jdbc:sqlserver://db:1433', username: 'reader', password: '********' });
  });
});

describe('support tools', () => {
  it('sends the typed support token and shows sent e-mails', async () => {
    const calls = mockFetch((url) => {
      if (url.endsWith('/diagnostics')) {
        return { body: { cpId: 'CP-1', obligorId: null, covenantCount: 1, covenants: [{ appliedCovenantId: 'a1',
          covenantName: 'Minimum DSCR', assessmentType: 'AUTO', conditionExpression: 'DSCR >= 1.5', compiled: true,
          compileError: null, wouldResolve: false }] } };
      }
      if (url.endsWith('/notifications')) {
        return { body: [{ id: 'n1', runId: 'r1', emailTo: 'rm@bank.test', emailCc: 'wrm@bank.test', cpIds: 'CP-1',
          requestBody: '{"templateCode":"BREACH"}', httpStatus: 200, outcome: 'SUCCESS', errorMessage: null,
          changedBy: 'system', changedDate: '2026-11-03T06:00:00Z' }] };
      }
      return { body: {} };
    });
    renderAt('/ops/support', '/ops/support', <Support />);
    fireEvent.change(screen.getByLabelText('Support token'), { target: { value: ' s3cret ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Use token' }));
    fireEvent.change(screen.getByLabelText('CP'), { target: { value: 'cp-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Diagnose' }));
    expect(await screen.findByText('missing')).toBeInTheDocument();
    const diag = sent(calls, 'GET', '/api/v1/ops/cps/CP-1/diagnostics')!;
    expect((diag.init.headers as Record<string, string>)['X-Support-Token']).toBe('s3cret');

    fireEvent.click(screen.getByRole('tab', { name: 'Sent e-mails' }));
    fireEvent.click(await screen.findByText('rm@bank.test'));
    expect(await screen.findByText(/"templateCode": "BREACH"/)).toBeInTheDocument();
  });
});

describe('portfolio, documents and onboarding additions', () => {
  it('filters to CPs with breaches and lists what is due', async () => {
    mockFetch((url) => {
      if (url.startsWith('/api/v1/monitoring/schedule')) {
        return { body: [{ cpId: 'CP-2', cpName: null, appliedCovenantId: 'a', covenantName: 'Quarterly MIS',
          frequency: 'QUARTERLY', nextDueDate: '2025-01-01', lastResult: null, timingStatus: 'OVERDUE', overdueByDays: 5 }] };
      }
      return { body: [
        { cpId: 'CP-1', applied: 3, compliant: 3, breached: 0, error: 0, pendingReview: 0, dispositioned: 0 },
        { cpId: 'CP-2', applied: 2, compliant: 1, breached: 1, error: 0, pendingReview: 0, dispositioned: 0 },
      ] };
    });
    renderAt('/portfolio', '/portfolio', <Portfolio />);
    expect(await screen.findByText('Quarterly MIS')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'CP-1' })).toHaveLength(1);
    fireEvent.click(screen.getByText('With breaches'));
    expect(screen.queryByRole('link', { name: 'CP-1' })).not.toBeInTheDocument();
    expect(screen.getByText('1 of 2')).toBeInTheDocument();
  });

  it('links a document to another CP', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const doc = { id: 'd1', fileName: 'agreement.pdf', type: 'EVIDENCE', status: 'PUBLISHED', cpIds: ['CP-1'],
      latestJobId: null, uploadedAt: '2026-11-01T00:00:00Z', uploadedBy: 'rm-anil', asOfDate: null };
    const calls = mockFetch((url) => (url.startsWith('/dps/v1/documents?') ? { body: [doc] } : { body: doc }));
    renderAt('/documents/CP-1', '/documents/:cpId', <Documents />);
    expect(screen.getByRole('heading', { name: 'Upload to CP-1' })).toBeInTheDocument();
    fireEvent.click(await screen.findByText('agreement.pdf'));
    const input = await screen.findByRole('textbox', { name: 'Applies to CPs' });
    fireEvent.change(input, { target: { value: 'cp-2' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.click(screen.getByRole('button', { name: 'Save links' }));
    await waitFor(() => expect(sent(calls, 'POST', '/dps/v1/documents/d1/links')).toBeTruthy());
    expect(JSON.parse(sent(calls, 'POST', '/dps/v1/documents/d1/links')!.init.body as string)).toEqual({ cpIds: ['CP-2'] });
  });

  it('suggests thresholds for the ticked rows', async () => {
    const calls = mockFetch((url) => {
      if (url.endsWith('/ml-threshold-recommendations')) {
        return { body: [{ covenantMasterId: 'm1', recommendedExpression: 'DSCR >= 1.43', confidence: 0.7,
          rationale: 'peer median' }] };
      }
      return { body: [{ covenantId: 'm1', name: 'Minimum DSCR', matchReason: 'agreement', assessmentType: 'AUTO',
        defaultFrequency: 'QUARTERLY', defaultCron: null, custom: false, alreadyApplied: false, source: 'EXTRACTED',
        conditionOverride: 'DSCR >= 1.5', thresholdSource: 'EXTRACTED', extractionConfidence: 0.9, evidence: null,
        mlRecommendedExpression: null, customDefinition: null }] };
    });
    renderAt('/onboarding/CP-1', '/onboarding/:cpId', <Onboarding />);
    fireEvent.click(await screen.findByRole('button', { name: 'Suggest thresholds' }));
    expect(await screen.findByText('Suggested (70%): DSCR >= 1.43. peer median')).toBeInTheDocument();
    expect(screen.getByLabelText('Rule for Minimum DSCR')).toHaveValue('DSCR >= 1.43');
    expect(JSON.parse(sent(calls, 'POST', '/ml-threshold-recommendations')!.init.body as string).items[0])
      .toEqual({ covenantMasterId: 'm1', covenantName: 'Minimum DSCR', currentExpression: 'DSCR >= 1.5' });
  });
});
