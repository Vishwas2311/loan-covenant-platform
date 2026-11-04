import { fireEvent, render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { theme } from '../theme';
import { HBars, Legend, SplitBar, StackedColumns, niceTicks } from './charts';

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
