import { fireEvent, render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { theme } from '../theme';
import { HBars, Legend, RATE_BANDS, SplitBar, StackedColumns, Treemap, niceTicks, rateColor, squarify } from './charts';

const wrap = (ui: JSX.Element) => render(<MantineProvider theme={theme}>{ui}</MantineProvider>);

describe('chart kit', () => {
  it('picks round axis steps that include zero', () => {
    expect(niceTicks(0, 23)).toEqual([0, 5, 10, 15, 20, 25]);
    expect(niceTicks(-0.4, 6)).toEqual([-2, 0, 2, 4, 6]);
    expect(niceTicks(0, 0)).toEqual([0, 1]);
  });

  it('draws bars with their values and an axis, growing left for negatives', () => {
    wrap(<HBars ariaLabel="Change by region" rows={[
      { key: 'emea', label: 'EMEA', value: 5.9 }, { key: 'na', label: 'NA', value: -0.2, detail: 'down' }]} />);
    expect(screen.getByRole('img', { name: 'Change by region' })).toBeInTheDocument();
    expect(screen.getByText('EMEA')).toBeInTheDocument();
    expect(screen.getByText('5.9')).toBeInTheDocument();
    expect(screen.getByText('-0.2')).toBeInTheDocument();
  });

  it('says so when there are no bars', () => {
    wrap(<HBars ariaLabel="x" rows={[]} />);
    expect(screen.getByText('Nothing to show yet.')).toBeInTheDocument();
  });

  it('splits a bar by part and shows an empty track for nothing', () => {
    const { rerender } = wrap(<SplitBar ariaLabel="2 kept, 1 breached" parts={[
      { key: 'a', label: 'Kept', value: 2, color: 'blue' }, { key: 'b', label: 'Breached', value: 1, color: 'red' },
      { key: 'c', label: 'None', value: 0, color: 'grey' }]} />);
    expect(screen.getByRole('img', { name: '2 kept, 1 breached' }).children).toHaveLength(2);
    rerender(<MantineProvider theme={theme}><SplitBar ariaLabel="empty" parts={[]} /></MantineProvider>);
    expect(screen.getByRole('img', { name: 'empty' })).toBeInTheDocument();
  });

  it('reads out the hovered month', () => {
    const { container } = wrap(<StackedColumns ariaLabel="Checks per month" points={[
      { key: 'm1', label: 'Oct 26', parts: [{ key: 'k', label: 'Kept', value: 3, color: 'blue' },
        { key: 'b', label: 'Breached', value: 1, color: 'red' }] },
      { key: 'm2', label: 'Nov 26', parts: [{ key: 'k', label: 'Kept', value: 0, color: 'blue' }] }]} />);
    fireEvent.mouseEnter(container.querySelectorAll('g.mark')[0]);
    expect(screen.getByText('Breached:')).toBeInTheDocument();
    fireEvent.mouseLeave(container.querySelector('svg')!);
    expect(screen.queryByText('Breached:')).not.toBeInTheDocument();
  });

  it('lists legend items', () => {
    wrap(<Legend items={[{ label: 'Kept', color: 'blue' }]} />);
    expect(screen.getByRole('listitem')).toHaveTextContent('Kept');
  });
});

describe('treemap (FR-25)', () => {
  it('fills the box with one rectangle per value, areas in proportion', () => {
    const rects = squarify([60, 30, 10], { x: 0, y: 0, w: 200, h: 100 });
    const area = rects.map((r) => r.w * r.h);
    expect(area[0] + area[1] + area[2]).toBeCloseTo(20000, 6);
    expect(area[0] / area[2]).toBeCloseTo(6, 6);
    rects.forEach((r) => {
      expect(r.x).toBeGreaterThanOrEqual(-1e-9);
      expect(r.x + r.w).toBeLessThanOrEqual(200 + 1e-9);
      expect(r.y + r.h).toBeLessThanOrEqual(100 + 1e-9);
    });
    expect(squarify([], { x: 0, y: 0, w: 10, h: 10 })).toEqual([]);
    expect(squarify([0], { x: 0, y: 0, w: 10, h: 10 })[0].w).toBe(0);
  });

  it('shades by breach rate in four bands', () => {
    expect(rateColor(0)).toBe(RATE_BANDS[0].color);
    expect(rateColor(0.05)).toBe(RATE_BANDS[1].color);
    expect(rateColor(0.2)).toBe(RATE_BANDS[2].color);
    expect(rateColor(0.6)).toBe(RATE_BANDS[3].color);
  });

  it('names every tile with its exposure and breach rate', () => {
    wrap(<Treemap ariaLabel="Exposure" format={(n) => `${n} cr`} tiles={[
      { key: 'OIL_GAS', label: 'Oil gas', value: 15000, rate: 0.25 },
      { key: 'STEEL', label: 'Steel', value: 5000, rate: 0 },
      { key: 'NONE', label: 'None', value: 0, rate: 0 }]} />);
    expect(screen.getByRole('list', { name: 'Exposure' })).toBeInTheDocument();
    expect(screen.getByRole('listitem', { name: 'Oil gas: 15000 cr, 25.0% breached' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('says so when there is nothing to draw', () => {
    wrap(<Treemap ariaLabel="Exposure" tiles={[]} />);
    expect(screen.getByText('Nothing to show yet.')).toBeInTheDocument();
  });
});

describe('exposure card', () => {
  it('copes with an answer that has sectors but no total', async () => {
    const { ExposureBySector } = await import('../pages/Charts');
    wrap(<ExposureBySector bySector={[{ key: 'STEEL', cps: 1, applied: 1, compliant: 1, breached: 0, error: 0,
      dispositioned: 0, breachRate: 0, exposureCrore: 500, cpsWithExposure: 1 }]} total={null} />);
    expect(screen.getByRole('listitem', { name: /Steel/ })).toBeInTheDocument();
  });
});
