import { fireEvent, screen, waitFor } from '@testing-library/react';
import { isBorrowerOnly } from '../auth/identity';
import { mockFetch, renderAt } from '../test-utils';
import { PortalApp } from './Portal';
import { LoanSyncPanel, PortalAccessCard } from './Phase4';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const sent = (calls: { url: string; init: RequestInit }[], method: string, part: string) =>
  calls.find((c) => (c.init.method ?? 'GET') === method && c.url.includes(part));

const VIEW = {
  cpId: 'CP-1003', name: 'Reliance Industries Ltd',
  requirements: [
    { id: 'q1', periodEnd: '2026-09-30', dueDate: '2026-11-15', state: 'PENDING', overdueDays: 0, files: [] },
    { id: 'q0', periodEnd: '2026-06-30', dueDate: '2026-08-15', state: 'RECEIVED', overdueDays: 0, files: ['cert.pdf'] },
  ],
  covenants: [{ name: 'Minimum DSCR', nextDueDate: '2026-12-31' }],
  submissions: [{ kind: 'CERTIFICATE', fileName: 'cert.pdf', periodEnd: '2026-06-30', submittedBy: 'borrower-reliance',
    submittedAt: '2026-08-10T10:00:00Z', note: null }],
};

describe('borrower portal', () => {
  it('only a borrower with no bank role gets the portal', () => {
    expect(isBorrowerOnly({ user: 'b', roles: ['BORROWER'] })).toBe(true);
    expect(isBorrowerOnly({ user: 'b', roles: ['BORROWER', 'RM'] })).toBe(false);
    expect(isBorrowerOnly({ user: 'b', roles: [] })).toBe(false);
  });

  it('shows what is due and sends a signed certificate', async () => {
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/portal/cps') {
        return { body: [{ cpId: 'CP-1003', name: 'Reliance Industries Ltd' }] };
      }
      if (init.method === 'POST') {
        return { body: { ...VIEW, requirements: [{ ...VIEW.requirements[0], state: 'RECEIVED' }] } };
      }
      return { body: VIEW };
    });
    renderAt('/', '/', <PortalApp user="borrower-reliance" />);
    expect(await screen.findByRole('heading', { name: 'Reliance Industries Ltd' })).toBeInTheDocument();
    expect(screen.getByText('waiting')).toBeInTheDocument();
    expect(screen.getByText(/Minimum DSCR/)).toBeInTheDocument();
    const send = screen.getByRole('button', { name: 'Send to the bank' });
    expect(send).toBeDisabled();
    fireEvent.change(document.querySelector('input[type=file]')!, {
      target: { files: [new File(['%PDF-1.4'], 'cert-q3.pdf', { type: 'application/pdf' })] } });
    fireEvent.change(screen.getByLabelText(/Signed by/), { target: { value: 'Priya Rao, CFO' } });
    fireEvent.click(screen.getByLabelText(/I confirm this certificate/));
    fireEvent.click(send);
    expect(await screen.findByText('Certificate sent to the bank. Thank you.')).toBeInTheDocument();
    const post = sent(calls, 'POST', '/api/v1/portal/cp/CP-1003/certificate')!;
    const form = post.init.body as FormData;
    expect(form.get('periodEnd')).toBe('2026-09-30');
    expect(form.get('signedBy')).toBe('Priya Rao, CFO');
    expect(form.get('confirm')).toBe('true');
  });

  it('sends a statement and lists what was sent', async () => {
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/portal/cps') {
        return { body: [{ cpId: 'CP-1003', name: null }, { cpId: 'CP-1004', name: 'Other' }] };
      }
      return { body: init.method === 'POST' ? VIEW : { ...VIEW, requirements: [] } };
    });
    renderAt('/', '/', <PortalApp user="borrower-reliance" onSignOut={() => undefined} />);
    expect(await screen.findByText('No compliance certificate is waiting.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Other' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Send a financial statement' }));
    fireEvent.change(document.querySelector('input[type=file]')!, {
      target: { files: [new File(['EBITDA,1'], 'q3.csv', { type: 'text/csv' })] } });
    fireEvent.change(screen.getByLabelText('Period end'), { target: { value: '2026-09-30' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send to the bank' }));
    expect(await screen.findByText('Statement sent to the bank. It is being read now.')).toBeInTheDocument();
    expect(sent(calls, 'POST', '/api/v1/portal/cp/CP-1003/statement')).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: 'What you sent' }));
    expect(await screen.findByText('cert.pdf')).toBeInTheDocument();
  });

  it('tells a borrower when no loan is shared yet', async () => {
    mockFetch(() => ({ body: [] }));
    renderAt('/', '/', <PortalApp user="borrower-x" />);
    expect(await screen.findByText('The bank has not shared any loan with you yet.')).toBeInTheDocument();
  });
});

describe('bank side of the portal, and the loan sync', () => {
  it('the RM gives and removes portal access', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const grant = { cpId: 'CP-1003', userId: 'borrower-reliance', grantedBy: 'rm-anil', grantedAt: '2026-11-04T00:00:00Z' };
    const calls = mockFetch((_, init) => ({ body: init.method === 'POST' ? [grant] : init.method === 'DELETE' ? [] : [] }));
    renderAt('/cp/CP-1003', '/cp/:cpId', <PortalAccessCard cpId="CP-1003" canEdit />);
    const give = await screen.findByRole('button', { name: 'Give access' });
    fireEvent.change(screen.getByLabelText("Borrower's user id"), { target: { value: 'borrower-reliance' } });
    fireEvent.click(give);
    expect(await screen.findByText('Portal access given.')).toBeInTheDocument();
    expect(JSON.parse(sent(calls, 'POST', '/portal-access')!.init.body as string)).toEqual({ userId: 'borrower-reliance' });
    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(sent(calls, 'DELETE', '/portal-access/borrower-reliance')).toBeTruthy());
  });

  it('support runs the loan sync with the support token and sees what changed', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const run = { id: 'r1', startedAt: '2026-11-04T01:00:00Z', finishedAt: '2026-11-04T01:00:05Z', cpsChecked: 25,
      cpsChanged: 1, cpsClosed: 1, errors: 0, triggeredBy: 'system',
      changes: [{ cpId: 'CP-1015', field: 'approvalStatus', oldValue: 'APPROVED', newValue: 'CLOSED' }] };
    const calls = mockFetch((_, init) => ({ body: init.method === 'POST' ? run : [run] }));
    renderAt('/ops', '/ops', <LoanSyncPanel token="t0k" />);
    expect(await screen.findByText('CP-1015')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Run the sync now' }));
    expect(await screen.findByText('25 CP(s) checked, 1 changed, 1 closed.')).toBeInTheDocument();
    expect((sent(calls, 'POST', '/api/v1/ops/loan-sync-runs')!.init.headers as Record<string, string>)['X-Support-Token'])
      .toBe('t0k');
  });
});
