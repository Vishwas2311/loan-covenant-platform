import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { setIdentity } from '../auth/identity';
import type { ReviewRow } from '../api/types';
import { AlertBell } from '../components/AlertBell';
import { mockFetch, renderAt } from '../test-utils';
import { MonthlyResults } from './Charts';
import { CpWorkspace } from './CpWorkspace';
import { Documents } from './Documents';
import { Reports } from './Reports';
import { Settings } from './Settings';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const ROW: ReviewRow = {
  appliedCovenantId: 'ac-1', covenantMasterId: 'm1', covenantName: 'Minimum DSCR', conditionExpression: 'DSCR >= 1.50',
  category: 'FINANCIAL', assessmentType: 'AUTO', computedValue: '1.55', headroom: 0.05, latestResult: 'COMPLIANT',
  latestAssessmentId: 'a1', lastAssessedAt: null, dispositionStatus: 'COMPLIED', extensionDate: null,
  statusSource: 'SYSTEM', note: null, dispositionable: false, nextDueDate: null, timingStatus: null, overdueByDays: 0,
  approvalStatus: null, requestedStatus: null, requestedBy: null, attachments: [], atRisk: true,
};

const sent = (calls: { url: string; init: RequestInit }[], method: string, part: string) =>
  calls.find((c) => (c.init.method ?? 'GET') === method && c.url.includes(part));

describe('CP workspace, phase 2', () => {
  it('shows at risk, and changes this CP\'s rule with a reason', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/review')) {
        return { body: [ROW] };
      }
      if (url.endsWith('/applied-covenants') && !init.method) {
        return { body: [{ id: 'ac-1', cpId: 'CP-1', frequency: 'QUARTERLY', cronExpression: null, status: 'ACTIVE',
          conditionOverride: 'DSCR >= 1.50' }] };
      }
      if (url.endsWith('/definition-history')) {
        return { body: [{ changedAt: '2026-11-01T00:00:00Z', changedBy: 'rm-anil', oldExpression: 'DSCR >= 1.25',
          newExpression: 'DSCR >= 1.50', source: 'USER', reason: 'Amendment 1' }] };
      }
      if (init.method === 'PATCH') {
        return { body: { id: 'ac-1' } };
      }
      return { body: [] };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    expect(await screen.findByText('at risk')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Minimum DSCR' }));
    const drawer = await screen.findByRole('dialog');
    fireEvent.click(within(drawer).getByRole('tab', { name: 'Schedule and changes' }));
    expect(await within(drawer).findByText('Amendment 1')).toBeInTheDocument();
    const save = within(drawer).getByRole('button', { name: 'Save rule' });
    fireEvent.change(within(drawer).getByLabelText('Rule for this CP'), { target: { value: 'DSCR >= 1.30' } });
    expect(save).toBeDisabled();
    fireEvent.change(within(drawer).getByLabelText('Why'), { target: { value: 'Amendment 2' } });
    fireEvent.click(save);
    expect(await screen.findByText('Rule saved. It applies from the next check.')).toBeInTheDocument();
    expect(JSON.parse(sent(calls, 'PATCH', '/applied-covenants/ac-1')!.init.body as string))
      .toEqual({ conditionExpression: 'DSCR >= 1.30', reason: 'Amendment 2' });
    fireEvent.change(within(drawer).getByLabelText('Why'), { target: { value: 'back to policy' } });
    fireEvent.click(within(drawer).getByRole('button', { name: 'Use the library rule' }));
    await waitFor(() => expect(calls.filter((c) => c.init.method === 'PATCH')).toHaveLength(2));
  });

  it('the requester withdraws a pending request', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const pending = { ...ROW, atRisk: false, latestResult: 'BREACHED' as const, approvalStatus: 'REQUESTED' as const,
      requestedStatus: 'WAIVER' as const, requestedBy: 'rm-anil' };
    const calls = mockFetch((url) => (url.endsWith('/review') ? { body: [pending] } : { body: {} }));
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByRole('button', { name: 'Withdraw' }));
    expect(await screen.findByText('Request withdrawn.')).toBeInTheDocument();
    expect(sent(calls, 'POST', '/covenants/ac-1/status/withdraw')).toBeTruthy();
  });

  it('the waiver dialog shows the covenant\'s credit policy', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const breached = { ...ROW, atRisk: false, latestResult: 'BREACHED' as const, dispositionable: true };
    mockFetch((url) => {
      if (url.endsWith('/review')) {
        return { body: [breached] };
      }
      if (url.endsWith('/covenants/m1')) {
        return { body: { id: 'm1', policyReference: 'https://policy.bank.test/4.2' } };
      }
      return { body: [] };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <CpWorkspace />);
    fireEvent.click(await screen.findByRole('button', { name: 'Waive / extend' }));
    expect(await screen.findByRole('link', { name: 'https://policy.bank.test/4.2' })).toBeInTheDocument();
  });
});

describe('documents, phase 2', () => {
  const agreement = { id: 'd1', fileName: 'facility.pdf', type: 'LOAN_AGREEMENT', status: 'PUBLISHED', cpIds: ['CP-1'],
    latestJobId: null, uploadedAt: '2026-11-01T00:00:00Z', uploadedBy: 'rm-anil', asOfDate: null };
  const amendment = { ...agreement, id: 'd2', fileName: 'amendment.pdf', amendsDocumentId: 'd1' };
  const term = (id: string, documentId: string, expression: string) => ({ id, documentId, metricRaw: 'DSCR',
    covenantMasterId: 'm1', expression, category: 'FINANCIAL', confidence: 0.9, status: 'PUBLISHED', issues: [],
    source: { page: 3, quote: 'q' } });

  it('shows what an amendment changed, and replaces a document', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    const calls = mockFetch((url) => {
      if (url.startsWith('/dps/v1/documents?')) {
        return { body: [agreement, amendment] };
      }
      if (url.endsWith('/d2/terms')) {
        return { body: [term('t2', 'd2', 'DSCR >= 1.30')] };
      }
      if (url.endsWith('/d1/terms')) {
        return { body: [term('t1', 'd1', 'DSCR >= 1.50')] };
      }
      if (url.endsWith('/remove') || url === '/dps/v1/documents') {
        return { body: { documentId: 'd3' } };
      }
      return { body: {} };
    });
    renderAt('/documents/CP-1', '/documents/:cpId', <Documents />);
    fireEvent.click(await screen.findByRole('cell', { name: 'amendment.pdf' }));
    expect(await screen.findByText('DSCR >= 1.50 → DSCR >= 1.30')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Remove or replace' }));
    fireEvent.change(await screen.findByLabelText(/^Why/), { target: { value: 'wrong version' } });
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(await screen.findByText('Removed. Its figures no longer count.')).toBeInTheDocument();
    expect(JSON.parse(sent(calls, 'POST', '/dps/v1/documents/d2/remove')!.init.body as string))
      .toEqual({ reason: 'wrong version' });
  });

  it('the upload card offers the CP\'s agreements to amend', async () => {
    setIdentity({ user: 'rm-anil', roles: ['RM'] });
    mockFetch((url) => (url.startsWith('/dps/v1/documents?') ? { body: [agreement] } : { body: [] }));
    renderAt('/documents/CP-1', '/documents/:cpId', <Documents />);
    expect(await screen.findByRole('textbox', { name: 'Amendment of (optional)' })).toBeInTheDocument();
  });
});

describe('alerts, charts and settings', () => {
  it('the bell counts what needs you and links to the CP', async () => {
    mockFetch(() => ({ body: [
      { kind: 'APPROVAL', cpId: 'CP-1', appliedCovenantId: 'a', covenantName: 'DSCR', message: 'rm-anil asks for a waiver' },
      { kind: 'AT_RISK', cpId: 'CP-2', appliedCovenantId: 'b', covenantName: 'Lev', message: 'Lev is close to its limit' },
    ] }));
    render(<MantineProvider><QueryClientProvider client={new QueryClient()}><MemoryRouter><AlertBell /></MemoryRouter>
    </QueryClientProvider></MantineProvider>);
    const bell = await screen.findByRole('button', { name: 'Alerts (2)' });
    fireEvent.click(bell);
    expect(await screen.findByText('rm-anil asks for a waiver')).toBeInTheDocument();
    expect(screen.getByText('CP-2').closest('a')).toHaveAttribute('href', '/cp/CP-2');
  });

  it('draws months stacked by result, and Reports shows the charts', async () => {
    render(<MantineProvider><MonthlyResults points={[
      { month: '2026-10', compliant: 3, breached: 1, error: 0, total: 4 },
      { month: '2026-11', compliant: 2, breached: 2, error: 1, total: 5 }]} /></MantineProvider>);
    expect(screen.getByRole('img', { name: 'Checks per month for 2 months, 3 breached' })).toBeInTheDocument();

    mockFetch((url) => {
      if (url.startsWith('/api/v1/analytics/trend')) {
        return { body: [{ month: '2026-11', compliant: 1, breached: 0, error: 0, total: 1 }] };
      }
      if (url.startsWith('/api/v1/analytics')) {
        const g = { key: 'CORPORATE', cps: 1, applied: 4, compliant: 3, breached: 1, error: 0, breachRate: 0.25 };
        return { body: { total: g, bySegment: [g], bySector: [] } };
      }
      return { body: [] };
    });
    renderAt('/reports', '/reports', <Reports />);
    expect(await screen.findByRole('img', { name: 'CORPORATE 25% breached' })).toBeInTheDocument();
    expect(screen.getByText('No covenants yet.')).toBeInTheDocument();
  });

  it('saves the at-risk warning level', async () => {
    const calls = mockFetch((url) => (url.startsWith('/api/v1/fx') ? { body: [] } : { body: {
      fiscalYearStartMonth: 4, periodicIncludesCurrentPeriod: false, dueSoonDays: 7, upcomingWindowDays: 30,
      dispositionApprovalRequired: true, reportingCurrency: 'INR', modifiedDate: null, modifiedBy: null,
      atRiskHeadroomPercent: 10 } }));
    renderAt('/settings', '/settings', <Settings />);
    fireEvent.change(await screen.findByLabelText(/At risk/), { target: { value: '15' } });
    fireEvent.click(screen.getByText('Save settings'));
    await waitFor(() => expect(sent(calls, 'PUT', '/settings/reporting')).toBeTruthy());
    expect(JSON.parse(sent(calls, 'PUT', '/settings/reporting')!.init.body as string).atRiskHeadroomPercent).toBe(15);
  });
});
