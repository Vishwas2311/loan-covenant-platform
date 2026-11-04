import { fireEvent, screen, waitFor } from '@testing-library/react';
import { setIdentity } from '../auth/identity';
import { mockFetch, renderAt } from '../test-utils';
import { Certificates } from './CpExtras';
import { DataSources } from './DataSources';
import { Measures } from './Measures';
import { Settings } from './Settings';
import { Support } from './Support';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const sent = (calls: { url: string; init: RequestInit }[], method: string, part: string) =>
  calls.find((c) => (c.init.method ?? 'GET') === method && c.url.includes(part));

describe('support tools, the rest', () => {
  it('reloads data, re-checks, reads the audit log and health, and sends the e-mails', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/diagnostics')) {
        return { body: { cpId: 'CP-1', obligorId: null, covenantCount: 1, covenants: [{ appliedCovenantId: 'a1',
          covenantName: 'Minimum DSCR', assessmentType: 'AUTO', conditionExpression: 'X >= 1', compiled: false,
          compileError: 'Unknown measure X', wouldResolve: true }] } };
      }
      if (url.endsWith('/reingest')) {
        return { body: { action: 'reingest', cpId: 'CP-1', appliedCovenantId: null, tablesTouched: ['ratios'],
          ingested: true, assessmentResult: null, warnings: ['one row skipped'] } };
      }
      if (url.endsWith('/reassess')) {
        return { body: { action: 'reassess', cpId: null, appliedCovenantId: 'a1', tablesTouched: [], ingested: false,
          assessmentResult: 'COMPLIANT', warnings: [] } };
      }
      if (url.includes('/ops/audit')) {
        return { body: [{ id: 'o1', action: 'reingest', targetType: 'cp', targetId: 'CP-1', performedBy: 'support-1',
          paramsSummary: null, outcome: 'SUCCESS', detail: 'done', performedAt: '2026-11-03T10:00:00Z' }] };
      }
      if (url.endsWith('/health/data-sources')) {
        return { body: { checkedAt: '2026-11-03T10:00:00Z', sourceCount: 2, reachableCount: 1, allReachable: false,
          sources: [{ id: 's1', name: 'Bank DB', type: 'MSSQL', active: true, reachable: false, latencyMs: 30,
            tableCount: 1, stagingRowCount: 9, lastIngestAt: null, message: 'refused' }] } };
      }
      if (url.endsWith('/notification-runs') && init.method === 'POST') {
        return { body: { runId: 'r', covenantsAssessed: 7, cpsTouched: 2, notificationsSent: 2, notificationsFailed: 1 } };
      }
      return { body: [] };
    });
    renderAt('/ops/support', '/ops/support', <Support />);
    fireEvent.change(screen.getByLabelText('Support token'), { target: { value: 't' } });
    fireEvent.click(screen.getByRole('button', { name: 'Use token' }));
    fireEvent.change(screen.getByLabelText('CP'), { target: { value: 'CP-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Diagnose' }));
    expect(await screen.findByText('Unknown measure X')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reload data' }));
    expect(await screen.findByText(/Data reloaded from ratios\. Warnings: one row skipped/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Re-check' }));
    expect(await screen.findByText('Re-checked: COMPLIANT')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Audit log' }));
    fireEvent.change(await screen.findByLabelText('Id (optional)'), { target: { value: 'CP-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Show' }));
    expect(await screen.findByText('support-1')).toBeInTheDocument();
    expect(sent(calls, 'GET', '/api/v1/ops/audit?targetType=cp&targetId=CP-1')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: 'Data source health' }));
    expect(await screen.findByText('1 of 2 data sources reachable', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('refused')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Sent e-mails' }));
    expect(await screen.findByText('No e-mails sent yet.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Send today/ }));
    expect(await screen.findByText('7 covenant(s) checked, 2 e-mail(s) sent, 1 failed')).toBeInTheDocument();
  });
});

describe('data sources, the rest', () => {
  it('creates a source with its own settings and switches one off', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const existing = { id: 's1', name: 'Old', type: 'REST', description: null, connectionConfig: {}, active: true };
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/tables')) {
        return { body: [{ id: 't1', dataSourceId: 's1', name: 'T', schemaName: 'dbo', tableName: 'ratios',
          entityKeyColumn: 'cp', asOfColumn: null, layout: 'COLUMNAR', active: false }] };
      }
      if (url === '/api/v1/data-sources' && init.method === 'POST') {
        return { status: 201, body: { ...existing, id: 's2', name: 'Loan system' } };
      }
      if (url.endsWith('/s1') && init.method === 'DELETE') {
        return { body: { ...existing, active: false } };
      }
      return { body: [existing] };
    });
    renderAt('/data-sources', '/data-sources', <DataSources />);
    expect(await screen.findByText('dbo.ratios (off)')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Switch off' }));
    await waitFor(() => expect(sent(calls, 'DELETE', '/data-sources/s1')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'New data source' }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Loan system' } });
    fireEvent.click(screen.getByText('Web service (REST)'));
    fireEvent.change(screen.getByLabelText('baseUrl'), { target: { value: 'http://bank-stubs:8080' } });
    fireEvent.click(screen.getByRole('button', { name: 'Remove valuesPath' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add setting' }));
    const names = screen.getAllByLabelText('Setting name');
    fireEvent.change(names[names.length - 1], { target: { value: 'auth' } });
    fireEvent.change(screen.getByLabelText('auth'), { target: { value: 'dps' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Loan system saved. Use Test to check the connection.')).toBeInTheDocument();
    expect(JSON.parse(sent(calls, 'POST', '/api/v1/data-sources')!.init.body as string)).toEqual({
      name: 'Loan system', type: 'REST', description: '',
      connectionConfig: { baseUrl: 'http://bank-stubs:8080', auth: 'dps' },
    });
  });
});

describe('measures, the rest', () => {
  it('a reader sees the list without edit buttons', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    mockFetch(() => ({ body: [{ id: 'm', code: 'DSCR', name: 'DSCR', description: null, dataSourceTableId: null,
      dataSourceId: null, tableName: null, sourceColumn: null, sourceCode: null, defaultAggregation: null,
      dataType: null, unit: null, active: false }] }));
    renderAt('/measures', '/measures', <Measures />);
    expect(await screen.findByText('inactive')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New measure' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });

  it('the new-measure form needs a valid code, a source and a column', async () => {
    setIdentity({ user: 'author-1', roles: ['AUTHOR'] });
    mockFetch(() => ({ body: [] }));
    renderAt('/measures', '/measures', <Measures />);
    fireEvent.click(await screen.findByRole('button', { name: 'New measure' }));
    fireEvent.change(screen.getByLabelText(/^Code/), { target: { value: '1BAD' } });
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'x' } });
    expect(screen.getByRole('button', { name: 'Save measure' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  });
});

describe('deleting mistakes', () => {
  it('deletes a certificate and a requirement', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const calls = mockFetch((url) => (url.endsWith('/certificate-requirements') ? { body: [{ id: 'q1',
      periodEnd: '2026-09-30', dueDate: '2026-11-15', state: 'RECEIVED', overdueDays: 0,
      certificates: [{ id: 'c1', fileName: 'cert.pdf' }] }] } : { body: {} }));
    setIdentity({ user: 'auditor-1', roles: ['AUDITOR'] });
    const view = renderAt('/cp/CP-1', '/cp/:cpId', <Certificates cpId="CP-1" />);
    expect(await screen.findByRole('button', { name: 'cert.pdf' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete cert.pdf' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add requirement' })).not.toBeInTheDocument();
    view.unmount();
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    renderAt('/cp/CP-1', '/cp/:cpId', <Certificates cpId="CP-1" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete cert.pdf' }));
    await waitFor(() => expect(sent(calls, 'DELETE', '/compliance-certificate/c1')).toBeTruthy());
    const buttons = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(buttons[buttons.length - 1]);
    await waitFor(() => expect(sent(calls, 'DELETE', '/certificate-requirements/q1')).toBeTruthy());
  });

  it('deletes an FX rate', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const calls = mockFetch((url) => {
      if (url.startsWith('/api/v1/fx/rates')) {
        return { body: url.includes('/fx/rates/') ? {} : [{ id: 'fx1', fromCurrency: 'USD', toCurrency: 'INR',
          rateDate: '2026-11-01', rate: 84, source: null }] };
      }
      return { body: { fiscalYearStartMonth: 4, periodicIncludesCurrentPeriod: false, dueSoonDays: 7,
        upcomingWindowDays: 30, dispositionApprovalRequired: true, reportingCurrency: 'INR', modifiedDate: null,
        modifiedBy: null } };
    });
    renderAt('/settings', '/settings', <Settings />);
    expect(await screen.findByText('USD/INR')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(sent(calls, 'DELETE', '/api/v1/fx/rates/fx1')).toBeTruthy());
  });
});

describe('data-source tables and support sends', () => {
  it('adds a table to a source', async () => {
    const source = { id: 's1', name: 'Bank DB', type: 'MSSQL', description: null, connectionConfig: {}, active: true };
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/s1/tables') && init.method === 'POST') {
        return { status: 201, body: { id: 't9', dataSourceId: 's1', name: 'Ratios', schemaName: 'dbo', tableName: 'cp_ratios',
          entityKeyColumn: 'cp_id', asOfColumn: 'as_of_date', layout: 'COLUMNAR', active: true } };
      }
      return { body: url.endsWith('/tables') ? [] : [source] };
    });
    renderAt('/data-sources', '/data-sources', <DataSources />);
    fireEvent.click(await screen.findByRole('button', { name: 'Add table' }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Ratios' } });
    fireEvent.change(screen.getByLabelText(/^Schema/), { target: { value: 'dbo' } });
    fireEvent.change(screen.getByLabelText(/^Table/), { target: { value: 'cp_ratios' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save table' }));
    expect(await screen.findByText('Table saved. Measures can now point at it.')).toBeInTheDocument();
    expect(JSON.parse(sent(calls, 'POST', '/data-sources/s1/tables')!.init.body as string)).toMatchObject({
      name: 'Ratios', schemaName: 'dbo', tableName: 'cp_ratios', entityKeyColumn: 'cp_id', asOfColumn: 'as_of_date',
      layout: 'COLUMNAR', entityKeyLevel: 'CP_ID' });
  });

  it('support sends borrower reminders and scheduled reports now', async () => {
    const calls = mockFetch((url) => {
      if (url.endsWith('/reminder-runs')) {
        return { body: { contacts: 2, sent: 1, failed: 0 } };
      }
      if (url.endsWith('/scheduled-runs')) {
        return { body: { sent: 3 } };
      }
      return { body: [] };
    });
    renderAt('/ops/support', '/ops/support', <Support />);
    fireEvent.change(screen.getByLabelText('Support token'), { target: { value: 't' } });
    fireEvent.click(screen.getByRole('button', { name: 'Use token' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Sent e-mails' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Send borrower reminders now' }));
    expect(await screen.findByText('Borrower reminders: 1 sent (2 contact(s) checked)')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Send scheduled reports now' }));
    expect(await screen.findByText('Scheduled reports: 3 sent')).toBeInTheDocument();
    expect(sent(calls, 'POST', '/api/v1/monitoring/reminder-runs')).toBeTruthy();
  });
});
