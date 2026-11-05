import { Button, Grid, Group, SegmentedControl, Table, Text } from '@mantine/core';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { r2 } from '../api/endpoints2';
import { r3 } from '../api/endpoints3';
import type { AnalyticsGroup } from '../api/types2';
import type { TrendPoint } from '../api/types3';
import { ChartCard } from '../components/Board';
import { humanize } from '../components/CpPicker';
import { Legend, MAGNITUDE, RATE_BANDS, STATUS, StackedColumns, Treemap } from '../components/charts';
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


/** Share of checked covenants breached in each group, highest first: one row per group with its bar and counts. */
export function BreachRate({ bySegment, bySector }: { bySegment: AnalyticsGroup[]; bySector: AnalyticsGroup[] }) {
  const [by, setBy] = useState<'segment' | 'sector'>('segment');
  const [all, setAll] = useState(false);
  const rows = [...(by === 'segment' ? bySegment : bySector)]
    .sort((a, b) => b.breachRate - a.breachRate || b.applied - a.applied);
  const max = Math.max(...rows.map((r) => r.breachRate), 0.0001);
  const shown = all ? rows : rows.slice(0, 6);
  return (
    <ChartCard title="Breach rate" subtitle="Breached as a share of covenants checked, highest first"
      actions={<SegmentedControl size="xs" aria-label="Group by" value={by} onChange={(v) => { setBy(v as 'segment' | 'sector'); setAll(false); }}
        data={[{ value: 'segment', label: 'By segment' }, { value: 'sector', label: 'By sector' }]} />}>
      <Table verticalSpacing={6} aria-label={by === 'segment' ? 'Breach rate by business segment' : 'Breach rate by sector'}>
        <Table.Thead>
          <Table.Tr><Table.Th>{by === 'segment' ? 'Segment' : 'Sector'}</Table.Th><Table.Th w="40%">Rate</Table.Th>
            <Table.Th ta="right">Breached</Table.Th><Table.Th ta="right">Errors</Table.Th>
            <Table.Th ta="right">Checked</Table.Th></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {shown.map((g) => (
            <Table.Tr key={g.key}>
              <Table.Td>{humanize(g.key)}</Table.Td>
              <Table.Td>
                <Group gap={8} wrap="nowrap">
                  <div style={{ flex: '1 1 auto', minWidth: 48, height: 6, borderRadius: 3, background: 'var(--app-rule)' }}>
                    <div style={{ width: `${(g.breachRate / max) * 100}%`, height: '100%', borderRadius: 3,
                      background: g.breachRate > 0 ? STATUS.bad : MAGNITUDE }} />
                  </div>
                  <Text size="sm" className="num" w={46} ta="right">{(g.breachRate * 100).toFixed(1)}%</Text>
                </Group>
              </Table.Td>
              <Table.Td ta="right" c={g.breached ? 'var(--text-bad)' : 'dimmed'} fw={g.breached ? 650 : 400}>{g.breached}</Table.Td>
              <Table.Td ta="right" c={g.error ? 'var(--text-warn)' : 'dimmed'}>{g.error}</Table.Td>
              <Table.Td ta="right">{g.compliant + g.breached}</Table.Td>
            </Table.Tr>
          ))}
          {rows.length === 0 && <Table.Tr><Table.Td colSpan={5}><Text size="sm" c="dimmed">Nothing to show yet.</Text></Table.Td></Table.Tr>}
        </Table.Tbody>
      </Table>
      {rows.length > 6 && (
        <Button variant="subtle" size="xs" mt={6} onClick={() => setAll((v) => !v)}>
          {all ? 'Show fewer' : `Show all ${rows.length}`}
        </Button>
      )}
    </ChartCard>
  );
}

const CRORE = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });
const crore = (n: number) => `₹${CRORE.format(n)} cr`;

/** FR-25: facility exposure by sector (tile size) and how much of it is breached (shade and label). */
export function ExposureBySector({ bySector, total }: { bySector: AnalyticsGroup[]; total?: AnalyticsGroup | null }) {
  const tiles = bySector
    .filter((g) => (g.exposureCrore ?? 0) > 0)
    .map((g) => ({ key: g.key, label: humanize(g.key), value: g.exposureCrore ?? 0, rate: g.breachRate,
      detail: `${g.cps} CP(s)` }));
  const covered = total?.cpsWithExposure ?? 0;
  const cps = total?.cps ?? 0;
  return (
    <ChartCard title="Exposure by sector"
      subtitle={`Facility amounts in ₹ crore; tile size is exposure, shade is the share of checks breached.${
        covered < cps ? ` ${covered} of ${cps} CPs have a facility amount.` : ''}`}
      actions={<Legend items={RATE_BANDS.map((b) => ({ label: b.label, color: b.color, ring: true }))} />}>
      {tiles.length === 0
        ? <Text size="sm" c="dimmed">No facility amounts yet: the CP service has not sent any.</Text>
        : <Treemap tiles={tiles} ariaLabel="Facility exposure by sector" format={crore} />}
    </ChartCard>
  );
}

/** C9: the charts at the top of Reports. */
export function Charts() {
  const trend = useQuery({ queryKey: ['trend', 12], queryFn: () => r3.trend(12) });
  const analytics = useQuery({ queryKey: ['analytics'], queryFn: r2.analytics });
  return (
    <Grid gutter="md" align="stretch">
      <Grid.Col span={{ base: 12, lg: 7 }}>
        <ChartCard title="Check results by month" subtitle="Every covenant check in the last 12 months, by result"
          actions={<Legend items={SERIES.map((s) => ({ label: s.label, color: s.color }))} />}>
          {trend.isLoading && <Loading />}
          {trend.error && <ErrorNote error={trend.error} />}
          {Array.isArray(trend.data) && trend.data.length === 0 && <Text size="sm" c="dimmed">No checks yet.</Text>}
          {Array.isArray(trend.data) && trend.data.length > 0 && <MonthlyResults points={trend.data} />}
        </ChartCard>
      </Grid.Col>
      <Grid.Col span={{ base: 12, lg: 5 }}>
        {analytics.data?.bySegment && <BreachRate bySegment={analytics.data.bySegment} bySector={analytics.data.bySector} />}
      </Grid.Col>
      {Array.isArray(analytics.data?.bySector) && (
        <Grid.Col span={12}>
          <ExposureBySector bySector={analytics.data.bySector} total={analytics.data.total} />
        </Grid.Col>
      )}
    </Grid>
  );
}
