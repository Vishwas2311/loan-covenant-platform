import { Card, Group, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { r2 } from '../api/endpoints2';
import { r3 } from '../api/endpoints3';
import type { TrendPoint } from '../api/types3';
import { ErrorNote, Loading } from '../components/Status';

const SERIES = [
  { key: 'compliant', label: 'Compliant', color: 'var(--mantine-color-green-6)' },
  { key: 'breached', label: 'Breached', color: 'var(--mantine-color-red-6)' },
  { key: 'error', label: 'Error', color: 'var(--mantine-color-orange-5)' },
] as const;

function Legend() {
  return (
    <Group gap="md">
      {SERIES.map((s) => (
        <Group key={s.key} gap={4}>
          <svg width="10" height="10" aria-hidden="true"><rect width="10" height="10" rx="2" fill={s.color} /></svg>
          <Text size="xs">{s.label}</Text>
        </Group>
      ))}
    </Group>
  );
}

/** C9: checks per month, stacked by result. */
export function MonthlyResults({ points }: { points: TrendPoint[] }) {
  const W = 640;
  const H = 200;
  const pad = 26;
  const max = Math.max(1, ...points.map((p) => p.compliant + p.breached + p.error));
  const slot = (W - 2 * pad) / Math.max(1, points.length);
  const bar = Math.min(28, slot * 0.6);
  const scale = (v: number) => (v * (H - 2 * pad)) / max;
  const breaches = points.reduce((n, p) => n + p.breached, 0);
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img"
      aria-label={`Checks per month for ${points.length} months, ${breaches} breached`}>
      <line x1={pad} x2={W - pad} y1={H - pad} y2={H - pad} stroke="var(--mantine-color-gray-4)" />
      <text x={pad - 4} y={pad + 4} fontSize="10" textAnchor="end" fill="var(--mantine-color-dimmed)">{max}</text>
      {points.map((p, i) => {
        const x = pad + i * slot + (slot - bar) / 2;
        let y = H - pad;
        return (
          <g key={p.month}>
            {SERIES.map((s) => {
              const h = scale(p[s.key]);
              y -= h;
              return h > 0 ? <rect key={s.key} x={x} y={y} width={bar} height={h} fill={s.color}><title>{`${p.month} ${s.label}: ${p[s.key]}`}</title></rect> : null;
            })}
            {(i % Math.ceil(points.length / 6) === 0 || i === points.length - 1) && (
              <text x={x + bar / 2} y={H - 8} fontSize="10" textAnchor="middle" fill="var(--mantine-color-dimmed)">
                {p.month}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

/** C9: share of covenants breached in each group, as horizontal bars. */
function BreachRateBars({ title, rows }: { title: string; rows: { key: string; breachRate: number; applied: number }[] }) {
  const top = rows.filter((r) => r.applied > 0).slice(0, 8);
  return (
    <Card withBorder>
      <Text fw={600} size="sm" mb="xs">{title}</Text>
      {top.length === 0 && <Text size="sm" c="dimmed">No covenants yet.</Text>}
      <Stack gap={6}>
        {top.map((r) => (
          <div key={r.key}>
            <Group justify="space-between"><Text size="xs">{r.key}</Text>
              <Text size="xs">{(r.breachRate * 100).toFixed(0)}%</Text></Group>
            <svg width="100%" height="8" role="img" aria-label={`${r.key} ${(r.breachRate * 100).toFixed(0)}% breached`}>
              <rect width="100%" height="8" rx="4" fill="var(--mantine-color-gray-2)" />
              <rect width={`${Math.max(r.breachRate * 100, r.breachRate > 0 ? 2 : 0)}%`} height="8" rx="4"
                fill="var(--mantine-color-red-6)" />
            </svg>
          </div>
        ))}
      </Stack>
    </Card>
  );
}

/** C9: the charts at the top of Reports. */
export function Charts() {
  const trend = useQuery({ queryKey: ['trend', 12], queryFn: () => r3.trend(12) });
  const analytics = useQuery({ queryKey: ['analytics'], queryFn: r2.analytics });
  return (
    <Stack>
      <Card withBorder>
        <Group justify="space-between" mb="xs">
          <Title order={4}>Check results by month</Title>
          <Legend />
        </Group>
        {trend.isLoading && <Loading />}
        {trend.error && <ErrorNote error={trend.error} />}
        {Array.isArray(trend.data) && <MonthlyResults points={trend.data} />}
      </Card>
      {analytics.data?.bySegment && (
        <SimpleGrid cols={{ base: 1, md: 2 }}>
          <BreachRateBars title="Breach rate by business segment" rows={analytics.data.bySegment} />
          <BreachRateBars title="Breach rate by sector" rows={analytics.data.bySector} />
        </SimpleGrid>
      )}
    </Stack>
  );
}
