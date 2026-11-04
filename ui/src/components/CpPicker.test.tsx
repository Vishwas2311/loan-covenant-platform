import { fireEvent, screen } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import type { CpCard } from '../api/types2';
import { mockFetch, renderAt } from '../test-utils';
import { CpChips, CpChooser, humanize } from './CpPicker';

afterEach(() => vi.unstubAllGlobals());

const card = (over: Partial<CpCard>): CpCard => ({
  cpId: 'CP-1', name: 'Tata Steel', businessSegment: 'CORPORATE', sector: 'METALS_MINING', rmUserId: 'rm-anil',
  applied: 4, compliant: 2, breached: 1, error: 1, pendingReview: 1, dispositioned: 0, ...over,
});

const CARDS = [
  card({}),
  card({ cpId: 'CP-2', name: 'Infosys', businessSegment: 'IT', sector: null, rmUserId: null,
    applied: 3, compliant: 3, breached: 0, error: 0, pendingReview: 0 }),
  card({ cpId: 'CP-3', name: null, applied: 0, compliant: 0, breached: 0, error: 0, pendingReview: 0 }),
];

function Chooser() {
  const navigate = useNavigate();
  return <CpChooser hint="Pick one." onOpen={(cp) => navigate(`/cp/${cp}`)} />;
}

describe('CpChooser', () => {
  it('lists the user CPs, searches, filters and opens one', async () => {
    mockFetch((url) => (url.endsWith('/analytics/cps') ? { body: CARDS } : undefined));
    renderAt('/cp', '/cp', <Chooser />);
    expect(await screen.findByText('Tata Steel')).toBeInTheDocument();
    expect(screen.getByText('Pick one.')).toBeInTheDocument();
    expect(screen.getByText('1 breached')).toBeInTheDocument();
    expect(screen.getByText('1 data error')).toBeInTheDocument();
    expect(screen.getByText('1 awaiting review')).toBeInTheDocument();
    expect(screen.getByText('All in order')).toBeInTheDocument();
    expect(screen.getByText('No covenants yet')).toBeInTheDocument();
    expect(screen.getAllByText('Metals mining').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('Search your CPs'), { target: { value: 'infos' } });
    expect(screen.queryByText('Tata Steel')).not.toBeInTheDocument();
    expect(screen.getByText('Infosys')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Search your CPs'), { target: { value: 'nothing-like-this' } });
    expect(screen.getByText('No CP matches.')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Search your CPs'), { target: { value: '' } });

    fireEvent.click(screen.getByText('Needs attention (1)'));
    expect(screen.queryByText('Infosys')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('No covenants yet (1)'));
    expect(screen.getByText('CP-3')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Open CP-3'));
    expect(await screen.findByText('navigated')).toBeInTheDocument();
  });

  it('says what to do when the user owns no CP', async () => {
    mockFetch(() => ({ body: [] }));
    renderAt('/cp', '/cp', <Chooser />);
    expect(await screen.findByText('No credit proposals of your own')).toBeInTheDocument();
  });

  it('shows an error when the list cannot load', async () => {
    mockFetch(() => ({ status: 500, body: { code: 'X', message: 'down' } }));
    renderAt('/cp', '/cp', <Chooser />);
    expect(await screen.findByText('Something went wrong')).toBeInTheDocument();
  });
});

describe('CpChips', () => {
  it('names the borrower and where the CP sits', async () => {
    mockFetch(() => ({ body: CARDS }));
    renderAt('/cp/CP-1', '/cp/:cpId', <CpChips cpId="CP-1" />);
    expect(await screen.findByText('Tata Steel')).toBeInTheDocument();
    expect(screen.getByText('Corporate')).toBeInTheDocument();
    expect(screen.getByText('Metals mining')).toBeInTheDocument();
    expect(screen.getByText('RM rm-anil')).toBeInTheDocument();
  });

  it('shows nothing for a CP that is not the user own', async () => {
    const calls = mockFetch(() => ({ body: CARDS }));
    renderAt('/cp/CP-9', '/cp/:cpId', <CpChips cpId="CP-9" />);
    await vi.waitFor(() => expect(calls.length).toBe(1));
    expect(screen.queryByText(/RM rm-anil/)).not.toBeInTheDocument();
  });

  it('humanizes codes', () => {
    expect(humanize('OIL_GAS')).toBe('Oil gas');
    expect(humanize(null)).toBe('');
  });
});

describe('ColorSchemeToggle', () => {
  it('switches between light and dark', async () => {
    const { ColorSchemeToggle } = await import('./ColorSchemeToggle');
    renderAt('/', '/', <ColorSchemeToggle />);
    fireEvent.click(screen.getByRole('button', { name: 'Switch to dark mode' }));
    expect(await screen.findByRole('button', { name: 'Switch to light mode' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Switch to light mode' }));
    expect(await screen.findByRole('button', { name: 'Switch to dark mode' })).toBeInTheDocument();
  });
});
