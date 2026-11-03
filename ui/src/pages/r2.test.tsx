import { fireEvent, screen, waitFor } from '@testing-library/react';
import { setIdentity } from '../auth/identity';
import { mockFetch, renderAt } from '../test-utils';
import { Certificates, Comparison, ManualValue } from './CpExtras';
import { Reports } from './Reports';

afterEach(() => vi.unstubAllGlobals());

describe('certificates', () => {
  it('shows requirement states and adds a requirement', async () => {
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/certificate-requirements') && init.method === 'PUT') {
        return { body: {} };
      }
      if (url.endsWith('/certificate-requirements')) {
        return { body: [
          { id: 'r1', cpId: 'CP-1', periodEnd: '2026-03-31', dueDate: '2026-05-15', note: null, state: 'RECEIVED',
            overdueDays: 0, certificates: [{ id: 'c1', fileId: 'f', fileName: 'q1.pdf', uploadedBy: 'u',
              uploadedAt: '2026-05-01T00:00:00Z', periodEnd: '2026-03-31' }] },
          { id: 'r2', cpId: 'CP-1', periodEnd: '2026-06-30', dueDate: '2026-08-14', note: null, state: 'OVERDUE',
            overdueDays: 80, certificates: [] },
        ] };
      }
      return undefined;
    });
    renderAt('/x', '/x', <Certificates cpId="CP-1" />);
    expect(await screen.findByText('q1.pdf')).toBeInTheDocument();
    expect(screen.getByText('80 d overdue')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Require period end'), { target: { value: '2026-09-30' } });
    fireEvent.change(screen.getByLabelText('Due by'), { target: { value: '2026-11-14' } });
    fireEvent.click(screen.getByText('Add requirement'));
    await waitFor(() => expect(calls.some((c) => c.init.method === 'PUT')).toBe(true));
    expect(screen.getByText('Upload').closest('button')).toBeDisabled();
  });

  it('shows an empty state', async () => {
    mockFetch(() => ({ body: [] }));
    renderAt('/x', '/x', <Certificates cpId="CP-1" />);
    expect(await screen.findByText('No certificate requirements.')).toBeInTheDocument();
  });
});

describe('comparison', () => {
  it('renders periods and cells', async () => {
    mockFetch(() => ({ body: { cpId: 'CP-1', periods: ['2026-09-30', '2026-06-30'], rows: [
      { appliedCovenantId: 'a1', covenantName: 'DSCR', conditionExpression: 'DSCR >= 1',
        cells: [{ result: 'BREACHED', computedValue: '0.9', assessmentId: 'x' }, null], trend: 'WORSENING' },
    ] } }));
    renderAt('/x', '/x', <Comparison cpId="CP-1" />);
    expect(await screen.findByText('2026-09-30')).toBeInTheDocument();
    expect(screen.getByText('0.9')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('worsening')).toBeInTheDocument();
  });
});

describe('manual values', () => {
  it('is for approvers only and posts the value', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const view = renderAt('/x', '/x', <ManualValue cpId="CP-1" />);
    expect(screen.getByText('Only an approver can enter values by hand.')).toBeInTheDocument();
    view.unmount();

    setIdentity({ user: 'approver-1', roles: ['APPROVER'] });
    const calls = mockFetch(() => ({ status: 201, body: {} }));
    renderAt('/x', '/x', <ManualValue cpId="CP-1" />);
    fireEvent.change(screen.getByLabelText('Value'), { target: { value: '42' } });
    fireEvent.change(screen.getByLabelText('Period end'), { target: { value: '2026-03-31' } });
    fireEvent.change(screen.getByLabelText('Why (required)'), { target: { value: 'from audited accounts' } });
    fireEvent.click(screen.getByText('Save value'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(JSON.parse(calls[0].init.body as string)).toMatchObject({ key: 'EBITDA', value: 42, unit: 'INR_CR' });
  });
});

describe('reports', () => {
  it('shows analytics and escalations', async () => {
    const group = { key: 'CORPORATE', cps: 2, applied: 5, compliant: 3, breached: 2, error: 0, dispositioned: 0,
      breachRate: 0.4 };
    mockFetch((url) => {
      if (url.startsWith('/api/v1/analytics')) {
        return { body: { total: group, bySegment: [group], bySector: [{ ...group, key: 'STEEL' }] } };
      }
      return { body: [{ cpId: 'CP-1', appliedCovenantId: 'a1', covenantName: 'DSCR', result: 'BREACHED',
        computedValue: '0.9', since: '2026-06-30', daysOpen: 125, status: 'BREACHED', approval: 'REQUESTED',
        escalate: true }] };
    });
    renderAt('/reports', '/reports', <Reports />);
    expect(await screen.findByText('STEEL')).toBeInTheDocument();
    expect(screen.getAllByText('40.0%')).toHaveLength(2);
    expect(await screen.findByText('Escalate')).toBeInTheDocument();
    expect(screen.getByText('requested')).toBeInTheDocument();
  });

  it('empty escalation list', async () => {
    mockFetch((url) => (url.startsWith('/api/v1/analytics') ? { status: 500, body: { message: 'down' } } : { body: [] }));
    renderAt('/reports', '/reports', <Reports />);
    expect(await screen.findByText('No open breaches.')).toBeInTheDocument();
    expect(await screen.findByText('down')).toBeInTheDocument();
  });
});

describe('downloads and FX', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
  });

  it('downloads Excel files with the identity headers', async () => {
    const { r2 } = await import('../api/endpoints2');
    const fetchMock = vi.fn(async () => new Response('xlsx', {
      headers: { 'content-disposition': 'attachment; filename="covenants-CP-1.xlsx"' } }));
    vi.stubGlobal('fetch', fetchMock);
    await r2.comparisonXlsx('CP-1');
    await r2.libraryXlsx();
    await r2.openBreachesXlsx(7);
    await r2.openBreachesPdf(7);
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(URL.createObjectURL).toHaveBeenCalled();
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"message":"nope"}', { status: 403 })));
    await expect(r2.libraryXlsx()).rejects.toMatchObject({ status: 403 });
  });

  it('buttons trigger downloads and FX rates can be added', async () => {
    const { Library } = await import('./Library');
    const { Settings } = await import('./Settings');
    const calls = mockFetch((url, init) => {
      if (url.startsWith('/api/v1/fx/rates') && init.method === 'PUT') {
        return { body: {} };
      }
      if (url.startsWith('/api/v1/fx/rates')) {
        return { body: [{ id: 'f1', fromCurrency: 'USD', toCurrency: 'INR', rateDate: '2026-03-31', rate: 83, source: null }] };
      }
      if (url.startsWith('/api/v1/settings')) {
        return { body: { fiscalYearStartMonth: 4, periodicIncludesCurrentPeriod: false, dueSoonDays: 7,
          upcomingWindowDays: 30, dispositionApprovalRequired: true, reportingCurrency: 'INR', modifiedDate: null,
          modifiedBy: null } };
      }
      if (url.startsWith('/api/v1/covenants?')) {
        return { body: { content: [], totalElements: 0, number: 0, last: true } };
      }
      return { status: 500, body: { message: 'download failed' } };
    });
    const lib = renderAt('/library', '/library', <Library />);
    fireEvent.click(await screen.findByText('Download Excel'));
    expect(await screen.findByText('download failed')).toBeInTheDocument();
    lib.unmount();

    renderAt('/settings', '/settings', <Settings />);
    expect(await screen.findByText('USD/INR')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Reporting currency (ISO code)'), { target: { value: 'usd' } });
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-06-30' } });
    fireEvent.change(screen.getByLabelText('Rate'), { target: { value: '84' } });
    fireEvent.click(screen.getByText('Add'));
    await waitFor(() => expect(calls.some((c) => c.url.startsWith('/api/v1/fx/rates') && c.init.method === 'PUT'))
      .toBe(true));
    const put = calls.find((c) => c.url.startsWith('/api/v1/fx/rates') && c.init.method === 'PUT')!;
    expect(JSON.parse(put.init.body as string)).toEqual({ fromCurrency: 'USD', toCurrency: 'INR',
      rateDate: '2026-06-30', rate: 84 });
  });

  it('comparison and reports Excel buttons report errors', async () => {
    mockFetch((url) => {
      if (url.includes('.xlsx')) {
        return { status: 500, body: { message: 'xlsx down' } };
      }
      if (url.includes('/comparison')) {
        return { body: { cpId: 'CP-1', periods: [], rows: [] } };
      }
      return { body: url.startsWith('/api/v1/analytics') ? { total: null, bySegment: [], bySector: [] } : [] };
    });
    const view = renderAt('/x', '/x', <Comparison cpId="CP-1" />);
    fireEvent.click(await screen.findByText('Download Excel'));
    expect(await screen.findByText('xlsx down')).toBeInTheDocument();
    view.unmount();
    renderAt('/reports', '/reports', <Reports />);
    fireEvent.click(await screen.findByText('Download Excel'));
    expect(await screen.findAllByText('xlsx down')).not.toHaveLength(0);
  });
});
