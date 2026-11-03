import { fireEvent, screen, waitFor } from '@testing-library/react';
import { mockFetch, renderAt } from '../test-utils';
import { SourcePage } from './Documents';
import { AskAi, Jobs } from './Ops';

afterEach(() => vi.unstubAllGlobals());

describe('jobs page', () => {
  it('lists failed jobs and retries one', async () => {
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/retry') && init.method === 'POST') {
        return { status: 202, body: {} };
      }
      return { body: [{ id: 'job-12345678', documentId: 'D1', flow: 'AGREEMENT_INTAKE', status: 'FAILED',
        pool: 'STANDARD', attempts: 3, currentStep: 'parse', error: 'Unreadable PDF', steps: [] }] };
    });
    renderAt('/ops/jobs', '/ops/jobs', <Jobs />);
    expect(await screen.findByText('Unreadable PDF')).toBeInTheDocument();
    expect(calls[0].url).toBe('/dps/v1/jobs?status=FAILED');
    fireEvent.click(screen.getByText('Retry'));
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/retry'))).toBe(true));
  });

  it('empty and error states', async () => {
    mockFetch(() => ({ body: [] }));
    const view = renderAt('/ops/jobs', '/ops/jobs', <Jobs />);
    expect(await screen.findByText('No jobs.')).toBeInTheDocument();
    view.unmount();
    mockFetch(() => ({ status: 403, body: { message: 'Forbidden' } }));
    renderAt('/ops/jobs', '/ops/jobs', <Jobs />);
    expect(await screen.findByText('Forbidden')).toBeInTheDocument();
  });
});

describe('ask ai', () => {
  it('shows the answer with sources and the advisory label', async () => {
    const calls = mockFetch(() => ({ body: { answer: 'CP-1: DSCR breached.', citations: ['breach:a1'],
      method: 'RULES', advisory: 'Advisory only' } }));
    renderAt('/x', '/x', <AskAi />);
    fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'Which breaches?' } });
    fireEvent.click(screen.getByText('Ask'));
    expect(await screen.findByText('CP-1: DSCR breached.')).toBeInTheDocument();
    expect(screen.getByText('Sources: breach:a1')).toBeInTheDocument();
    expect(JSON.parse(calls[0].init.body as string)).toEqual({ question: 'Which breaches?' });
  });
});

describe('source page', () => {
  it('opens the PDF at the cited page', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:pdf');
    vi.stubGlobal('fetch', vi.fn(async () => new Response('%PDF', { headers: { 'content-type': 'application/pdf' } })));
    renderAt('/x', '/x', <SourcePage documentId="D1" page={3} quote="not less than 1.25" onClose={() => {}} />);
    expect(await screen.findByTitle('Source page')).toHaveAttribute('src', 'blob:pdf#page=3');
    expect(screen.getByText('“not less than 1.25”')).toBeInTheDocument();
  });

  it('shows an error when the file cannot be fetched', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })));
    renderAt('/x', '/x', <SourcePage documentId="D1" page={1} quote={null} onClose={() => {}} />);
    expect(await screen.findByText('HTTP 404')).toBeInTheDocument();
  });
});
