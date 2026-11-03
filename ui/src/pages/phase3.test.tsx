import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { setIdentity } from '../auth/identity';
import type { ReviewRow } from '../api/types';
import { mockFetch, renderAt } from '../test-utils';
import { MyTasks } from './MyTasks';
import { AskAgreement, BorrowerReminders, CustomReports, LetterButtons, SpreadReview } from './Phase3';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const sent = (calls: { url: string; init: RequestInit }[], method: string, part: string) =>
  calls.find((c) => (c.init.method ?? 'GET') === method && c.url.includes(part));
const body = (calls: { url: string; init: RequestInit }[], method: string, part: string) =>
  JSON.parse(sent(calls, method, part)!.init.body as string);

const TASK = { id: 't1', cpId: 'CP-1', appliedCovenantId: 'a1', kind: 'BREACH', title: 'Minimum DSCR is breached',
  assignee: 'rm-anil', dueDate: '2020-01-01', status: 'OPEN', note: null, createdAt: '2019-12-25T00:00:00Z',
  closedAt: null, closedBy: null, escalated: true };

describe('my tasks', () => {
  it('lists tasks, marks escalated ones, and finishes a task', async () => {
    const calls = mockFetch((url, init) => {
      if (init.method === 'PATCH') {
        return { body: { ...TASK, status: 'DONE', closedBy: 'rm-anil' } };
      }
      return { body: url.includes('open=false') ? [{ ...TASK, status: 'RESOLVED' }] : [TASK] };
    });
    renderAt('/tasks', '/tasks', <MyTasks />);
    expect(await screen.findByText('Minimum DSCR is breached')).toBeInTheDocument();
    expect(screen.getByText('escalated')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Update' }));
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'called the CFO' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mark done' }));
    expect(await screen.findByText('Task done.')).toBeInTheDocument();
    expect(body(calls, 'PATCH', '/api/v1/tasks/t1')).toEqual({ assignee: 'rm-anil', dueDate: '2020-01-01',
      note: 'called the CFO', done: true });
    fireEvent.click(screen.getByText('Closed'));
    expect(await screen.findByText('sorted out')).toBeInTheDocument();
    expect(sent(calls, 'GET', '/api/v1/tasks?scope=all&open=false')).toBeTruthy();
  });

  it('saves a reassignment without finishing', async () => {
    const calls = mockFetch((_, init) => ({ body: init.method === 'PATCH' ? { ...TASK, assignee: 'wrm-meera' } : [TASK] }));
    renderAt('/tasks', '/tasks', <MyTasks />);
    fireEvent.click(await screen.findByRole('button', { name: 'Update' }));
    fireEvent.change(screen.getByLabelText(/Assigned to/), { target: { value: ' wrm-meera ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Task saved.')).toBeInTheDocument();
    expect(body(calls, 'PATCH', '/api/v1/tasks/t1')).toMatchObject({ assignee: 'wrm-meera' });
  });
});

describe('borrower reminders and agreement questions', () => {
  it('saves the borrower contact and lists reminders sent', async () => {
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/reminders')) {
        return { body: [{ id: 'r1', cpId: 'CP-1', requirementId: 'q', kind: 'OVERDUE', sentOn: '2026-11-04',
          sentTo: 'cfo@b.test', outcome: 'FAILED', detail: 'mail down' }] };
      }
      if (init.method === 'PUT') {
        return { body: { cpId: 'CP-1', contactName: 'Ms Rao', email: 'cfo@b.test', remindersOn: true, daysBefore: 10 } };
      }
      return { status: 204 };
    });
    renderAt('/cp/CP-1', '/cp/:cpId', <BorrowerReminders cpId="CP-1" canEdit />);
    expect(await screen.findByText('mail down')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Contact name'), { target: { value: 'Ms Rao' } });
    fireEvent.change(screen.getByLabelText('E-mail'), { target: { value: 'cfo@b.test' } });
    fireEvent.change(screen.getByLabelText('Days before due'), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Borrower contact saved.')).toBeInTheDocument();
    expect(body(calls, 'PUT', '/borrower-contact')).toEqual({ contactName: 'Ms Rao', email: 'cfo@b.test',
      remindersOn: true, daysBefore: 10 });
  });

  it('asks about an agreement and opens a cited page', async () => {
    const calls = mockFetch(() => ({ body: { answer: 'Dividends are blocked during a breach [p. 7].',
      citations: [{ page: 7, quote: 'shall not declare dividends' }], advisory: 'Advice only.' } }));
    const onPage = vi.fn();
    renderAt('/x', '/x', <AskAgreement documentId="d1" onPage={onPage} />);
    fireEvent.change(screen.getByLabelText('Question about this agreement'), { target: { value: 'Dividends?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    fireEvent.click(await screen.findByRole('button', { name: 'p. 7' }));
    expect(onPage).toHaveBeenCalledWith(7, 'shall not declare dividends');
    expect(body(calls, 'POST', '/dps/v1/documents/d1/ask')).toEqual({ question: 'Dividends?' });
  });
});

describe('letters, reports and the spread', () => {
  it('offers only the letters that fit the covenant and downloads one', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const calls = mockFetch(() => ({ body: {} }));
    const row = { appliedCovenantId: 'a1', latestResult: 'BREACHED', dispositionStatus: 'WAIVER' } as unknown as ReviewRow;
    render(<MantineProvider><LetterButtons cpId="CP-1" row={row} /></MantineProvider>);
    expect(screen.queryByRole('button', { name: 'Extension letter' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Breach notice' }));
    await waitFor(() => expect(sent(calls, 'GET', '/covenants/a1/letter?kind=BREACH_NOTICE')).toBeTruthy());
    expect(screen.getByRole('button', { name: 'Waiver letter' })).toBeInTheDocument();
    const none = { appliedCovenantId: 'a1', latestResult: 'COMPLIANT', dispositionStatus: 'COMPLIED' } as unknown as ReviewRow;
    const { container } = render(<MantineProvider><div data-testid="none"><LetterButtons cpId="CP-1" row={none} /></div></MantineProvider>);
    expect(container.querySelector('[data-testid="none"] button')).toBeNull();
  });

  it('runs a custom report and saves it on a schedule', async () => {
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/reports/run')) {
        return { body: [{ cpId: 'CP-1', covenantName: 'Minimum DSCR', result: 'BREACHED', value: '1.2' }] };
      }
      if (init.method === 'POST') {
        return { status: 201, body: { id: 's1', name: 'Weekly breaches', definition: {}, schedule: 'WEEKLY',
          emailTo: 'head@bank.test', lastSentOn: null } };
      }
      return { body: [{ id: 's0', name: 'Old one', definition: { columns: [], results: [], segment: null, cpFilter: null },
        schedule: 'NONE', emailTo: null, lastSentOn: null }] };
    });
    renderAt('/reports', '/reports', <CustomReports />);
    fireEvent.click(screen.getByRole('button', { name: 'Run' }));
    expect(await screen.findByText('Minimum DSCR')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Report name'), { target: { value: 'Weekly breaches' } });
    fireEvent.click(screen.getByRole('textbox', { name: 'E-mail it' }));
    fireEvent.click(await screen.findByText('Weekly'));
    fireEvent.change(screen.getByLabelText(/To \(comma-separated\)/), { target: { value: 'head@bank.test' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save report' }));
    expect(await screen.findByText('Weekly breaches saved.')).toBeInTheDocument();
    expect(body(calls, 'POST', '/api/v1/reports/saved')).toMatchObject({ name: 'Weekly breaches', schedule: 'WEEKLY',
      emailTo: 'head@bank.test' });
    fireEvent.click(await screen.findByRole('button', { name: 'Old one' }));
    expect(screen.getByLabelText('Report name')).toHaveValue('Old one');
  });

  it('shows the spread by section and saves a correction', async () => {
    const line = (key: string, label: string, section: string, value: number | null, status: string) => ({
      section, standardKey: key, standardLabel: label, sourceLabel: null, value, unit: 'INR_CR', cell: 'B4', page: null,
      confidence: status === 'UNMAPPED' ? 0 : 1, status });
    const calls = mockFetch((_, init) => ({ body: init.method === 'PUT'
      ? [line('EBITDA', 'EBITDA', 'PROFIT_AND_LOSS', 650, 'CONFIRMED')]
      : [line('EBITDA', 'EBITDA', 'PROFIT_AND_LOSS', 640, 'MAPPED'), line('CFO', 'Cash from operating activities', 'CASH_FLOW', null, 'UNMAPPED')] }));
    renderAt('/x', '/x', <SpreadReview documentId="d1" canEdit />);
    expect(await screen.findByText('1 of 2 standard lines found. Amounts in INR crore.')).toBeInTheDocument();
    expect(screen.queryByText('Cash from operating activities')).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Show lines not found'));
    expect(screen.getByText('Cash from operating activities')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Value for EBITDA'), { target: { value: '650' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save corrections' }));
    expect(await screen.findByText('checked by a person')).toBeInTheDocument();
    expect(body(calls, 'PUT', '/dps/v1/documents/d1/spread')).toEqual({ lines: [{ standardKey: 'EBITDA', value: 650 }] });
  });
});

beforeEach(() => setIdentity({ user: 'rm-anil', roles: ['RM'] }));
