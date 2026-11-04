import { SegmentedControl, SimpleGrid, Stack, Table, Text } from '@mantine/core';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { r2 } from '../api/endpoints2';
import { r3 } from '../api/endpoints3';
import type { AnalyticsGroup } from '../api/types2';
import type { TrendPoint } from '../api/types3';
import { ChartCard } from '../components/Board';
import { humanize } from '../components/CpPicker';
import { HBars, Legend, MAGNITUDE, STATUS, StackedColumns } from '../components/charts';
import { ErrorNote, Loading } from '../components/Status';

const SERIES = [
  { key: 'compliant', label: 'Kept', color: MAGNITUDE },
  { key: 'breached', label: 'Breached', color: STATUS.bad },
  { key: 'error', label: 'Could not be checked', color: STATUS.warn },
] as const;

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const monthLabel = (m: string) => {
  const [y, mm] = m.split('-');
  return `${MONTHS[Number(mm) - 1] ?? mm} ${y.slice(2)}`;
};

/** C9: checks per month, stacked by result (kept in the data hue, breached and errors in status colours). */
export function MonthlyResults({ points }: { points: TrendPoint[] }) {
  const breaches = points.reduce((n, p) => n + p.breached, 0);
  return (
    <StackedColumns ariaLabel={`Checks per month for ${points.length} months, ${breaches} breached`}
      points={points.map((p) => ({
        key: p.month, label: monthLabel(p.month),
        parts: SERIES.map((s) => ({ key: s.key, label: s.label, value: p[s.key], color: s.color })),
      }))} />
  );
}

const pct = (n: number) => `${Number(n.toFixed(1))}%`;

/** Share of checked covenants breached in each group, highest first, with the counts behind it. */
export function BreachRate({ bySegment, bySector }: { bySegment: AnalyticsGroup[]; bySector: AnalyticsGroup[] }) {
  const [by, setBy] = useState<'segment' | 'sector'>('segment');
  const rows = by === 'segment' ? bySegment : bySector;
  const title = by === 'segment' ? 'Breach rate by business segment' : 'Breach rate by sector';
  const top = rows.filter((r) => r.applied > 0).sort((a, b) => b.breachRate - a.breachRate);
  return (
    <ChartCard title="Breach rate" subtitle="Breached as a share of covenants checked, highest first"
      actions={<SegmentedControl size="xs" aria-label="Group by" value={by} onChange={(v) => setBy(v as 'segment' | 'sector')}
        data={[{ value: 'segment', label: 'By business segment' }, { value: 'sector', label: 'By sector' }]} />}>
      <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="xl">
        <HBars ariaLabel={title} format={pct} labelWidth={110}
          rows={top.map((r) => ({
            key: r.key, label: humanize(r.key), value: r.breachRate * 100,
            color: r.breachRate > 0 ? STATUS.bad : MAGNITUDE,
            detail: `${r.breached} of ${r.compliant + r.breached} checked, ${r.cps} CP${r.cps === 1 ? '' : 's'}`,
          }))} />
        <Stack gap={0}>
          <Table>
            <Table.Thead>
              <Table.Tr><Table.Th>{by === 'segment' ? 'Segment' : 'Sector'}</Table.Th><Table.Th ta="right">CPs</Table.Th>
                <Table.Th ta="right">Covenants</Table.Th><Table.Th ta="right">Breached</Table.Th>
                <Table.Th ta="right">Errors</Table.Th><Table.Th ta="right">Rate</Table.Th></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((g) => (
                <Table.Tr key={g.key}>
                  <Table.Td>{humanize(g.key)}</Table.Td><Table.Td ta="right">{g.cps}</Table.Td><Table.Td ta="right">{g.applied}</Table.Td>
                  <Table.Td ta="right" c={g.breached ? 'var(--status-bad)' : 'dimmed'} fw={g.breached ? 650 : 400}>{g.breached}</Table.Td>
                  <Table.Td ta="right" c={g.error ? 'orange.8' : 'dimmed'}>{g.error}</Table.Td>
                  <Table.Td ta="right">{(g.breachRate * 100).toFixed(1)}%</Table.Td>
                </Table.Tr>
              ))}
              {rows.length === 0 && <Table.Tr><Table.Td colSpan={6}><Text size="sm" c="dimmed">No covenants yet.</Text></Table.Td></Table.Tr>}
            </Table.Tbody>
          </Table>
        </Stack>
      </SimpleGrid>
    </ChartCard>
  );
}

/** C9: the charts at the top of Reports. */
export function Charts() {
  const trend = useQuery({ queryKey: ['trend', 12], queryFn: () => r3.trend(12) });
  const analytics = useQuery({ queryKey: ['analytics'], queryFn: r2.analytics });
  return (
    <>
      <ChartCard title="Check results by month" subtitle="Every covenant check in the last 12 months, by result"
        actions={<Legend items={SERIES.map((s) => ({ label: s.label, color: s.color }))} />}>
        {trend.isLoading && <Loading />}
        {trend.error && <ErrorNote error={trend.error} />}
        {Array.isArray(trend.data) && trend.data.length === 0 && <Text size="sm" c="dimmed">No checks yet.</Text>}
        {Array.isArray(trend.data) && trend.data.length > 0 && <MonthlyResults points={trend.data} />}
      </ChartCard>
      {analytics.data?.bySegment && <BreachRate bySegment={analytics.data.bySegment} bySector={analytics.data.bySector} />}
    </>
  );
}
