import { fireEvent, screen, within } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import type { DpsDocument } from '../api/types';
import type { CpCard } from '../api/types2';
import { mockFetch, renderAt } from '../test-utils';
import { DocumentInbox, OnboardingQueue, stageOf } from './CpQueues';

afterEach(() => vi.unstubAllGlobals());

const cp = (over: Partial<CpCard>): CpCard => ({
  cpId: 'CP-1', name: 'Tata Steel', businessSegment: 'CORPORATE', sector: 'METALS_MINING', rmUserId: 'rm-anil',
  applied: 0, compliant: 0, breached: 0, error: 0, pendingReview: 0, dispositioned: 0, ...over,
});

const doc = (over: Partial<DpsDocument>): DpsDocument => ({
  id: 'd1', type: 'LOAN_AGREEMENT', fileName: 'agreement.pdf', pageCount: 3, asOfDate: null, status: 'PUBLISHED',
  cpIds: ['CP-1'], latestJobId: null, uploadedBy: 'rm-anil', uploadedAt: '2026-11-01T00:00:00Z', ...over,
});

const CPS = [
  cp({}),
  cp({ cpId: 'CP-2', name: 'Infosys', businessSegment: 'CORPORATE', sector: 'CORPORATE' }),
  cp({ cpId: 'CP-3', name: 'NTPC', applied: 4, compliant: 4 }),
];
const DOCS: Record<string, DpsDocument[]> = {
  'CP-1': [doc({})],
  'CP-2': [doc({ id: 'd2', fileName: 'scan.pdf', status: 'NEEDS_REVIEW', cpIds: ['CP-2'] }),
    doc({ id: 'd3', type: 'FINANCIAL_STATEMENT', fileName: 'fy26.csv', asOfDate: '2026-03-31', cpIds: ['CP-2'] })],
  'CP-3': [],
};

function serve() {
  return mockFetch((url) => {
    if (url.endsWith('/analytics/cps')) {
      return { body: CPS };
    }
    const m = /cpId=([^&]+)/.exec(url);
    return m ? { body: DOCS[decodeURIComponent(m[1])] ?? [] } : undefined;
  });
}

function Queue() {
  const navigate = useNavigate();
  return <OnboardingQueue onOpen={(id) => navigate(`/onboarding/${id}`)} />;
}

function Inbox() {
  const navigate = useNavigate();
  return <DocumentInbox onOpen={(id) => navigate(`/documents/${id}`)} />;
}

describe('stageOf', () => {
  it('follows the agreement and what is applied', () => {
    expect(stageOf(cp({ applied: 2 }), [])).toBe('done');
    expect(stageOf(cp({}), [])).toBe('upload');
    expect(stageOf(cp({}), [doc({ status: 'REMOVED' })])).toBe('upload');
    expect(stageOf(cp({}), [doc({ status: 'RUNNING' })])).toBe('reading');
    expect(stageOf(cp({}), [doc({ status: 'FAILED' })])).toBe('check');
    expect(stageOf(cp({}), [doc({})])).toBe('ready');
  });
});

describe('OnboardingQueue', () => {
  it('puts CPs ready to onboard first and filters by stage', async () => {
    serve();
    renderAt('/onboarding', '/onboarding', <Queue />);
    expect(await screen.findByText('Ready to onboard', { selector: 'span' })).toBeInTheDocument();
    const rows = screen.getAllByRole('button', { name: /^Open CP-/ });
    expect(rows[0]).toHaveAccessibleName('Open CP-1');
    expect(screen.getByText('Infosys')).toBeInTheDocument();
    expect(screen.getByText('CP-2 · Corporate')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Onboard' })).toHaveAttribute('href', '/onboarding/CP-1');
    expect(screen.getByRole('link', { name: 'Check agreement' })).toHaveAttribute('href', '/documents/CP-2');
    fireEvent.click(screen.getByRole('button', { name: 'Show CPs: Onboarded' }));
    expect(screen.queryByText('Infosys')).not.toBeInTheDocument();
    expect(screen.getByText('NTPC')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show CPs: Onboarded' }));
    fireEvent.change(screen.getByLabelText('Search your CPs'), { target: { value: 'cp-99' } });
    expect(screen.getByText('No CP matches.')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Open CP-99'));
    expect(await screen.findByText('navigated')).toBeInTheDocument();
  });
});

describe('DocumentInbox', () => {
  it('lists files waiting for review and each CP with its paperwork', async () => {
    serve();
    renderAt('/documents', '/documents', <Inbox />);
    const waiting = await screen.findByRole('heading', { name: 'Waiting for your review' });
    const card = waiting.closest('.mantine-Card-root') as HTMLElement;
    expect(await within(card).findByText('scan.pdf')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Check' })).toHaveAttribute('href', '/documents/CP-2');
    expect(screen.getByText('1 to review')).toBeInTheDocument();
    expect(screen.getByText('Upload the agreement')).toBeInTheDocument();
    expect(screen.getByText('31 Mar 2026')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Open CP-3' }));
    expect(await screen.findByText('navigated')).toBeInTheDocument();
  });
});
