import {
  Anchor, Badge, Button, Card, Group, SegmentedControl, Select, SimpleGrid, Stack, Table, Text, TextInput, Title,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { core } from '../api/endpoints';
import { r3 } from '../api/endpoints3';
import { r4 } from '../api/endpoints4';
import { canOpen, pageFor } from '../auth/access';
import { useIdentity } from '../auth/useIdentity';
import type { CpSummary } from '../api/types';
import { ChartCard, Chip, Kpi, KpiStrip, PageHeader } from '../components/Board';
import { humanize, useMyCps } from '../components/CpPicker';
import { MAGNITUDE, STATUS, SplitBar, Legend } from '../components/charts';
import { ErrorNote, Loading } from '../components/Status';
import { fmtDate } from '../format';

type Show = 'all' | 'breached' | 'error' | 'pending';
type Sort = 'cp' | 'breached' | 'error' | 'pending';

const SHOW: Record<Show, (r: CpSummary) => boolean> = {
  all: () => true, breached: (r) => r.breached > 0, error: (r) => r.error > 0, pending: (r) => r.pendingReview > 0,
};
const SORT: Record<Sort, (a: CpSummary, b: CpSummary) => number> = {
  cp: (a, b) => a.cpId.localeCompare(b.cpId),
  breached: (a, b) => b.breached - a.breached || a.cpId.localeCompare(b.cpId),
  error: (a, b) => b.error - a.error || a.cpId.localeCompare(b.cpId),
  pending: (a, b) => b.pendingReview - a.pendingReview || a.cpId.localeCompare(b.cpId),
};

/** Worst first: breaches, then checks that could not run, then waiting decisions. */
const worst = (a: CpSummary, b: CpSummary) => b.breached - a.breached || b.error - a.error
  || b.pendingReview - a.pendingReview || a.cpId.localeCompare(b.cpId);

export const RESULT_PARTS = (r: { compliant: number; breached: number; error: number; applied: number }) => [
  { key: 'compliant', label: 'Kept', value: r.compliant, color: MAGNITUDE },
  { key: 'breached', label: 'Breached', value: r.breached, color: STATUS.bad },
  { key: 'error', label: 'Could not be checked', value: r.error, color: STATUS.warn },
  { key: 'other', label: 'Not checked yet', value: Math.max(0, r.applied - r.compliant - r.breached - r.error), color: STATUS.neutral },
];

const LEGEND = [
  { label: 'Kept', color: MAGNITUDE }, { label: 'Breached', color: STATUS.bad },
  { label: 'Could not be checked', color: STATUS.warn }, { label: 'Not checked yet', color: STATUS.neutral },
];

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/**
 * The health ladder: every CP as one row, its covenants split by result, worst first. It answers
 * "which loans need me?" at a glance; a row opens the CP.
 */
function HealthLadder({ rows, names }: { rows: CpSummary[]; names: Map<string, string | null | undefined> }) {
  const navigate = useNavigate();
  const [all, setAll] = useState(false);
  const sorted = [...rows].sort(worst);
  const shown = all ? sorted : sorted.slice(0, 10);
  return (
    <ChartCard title="Covenant health by CP" subtitle="Worst first. Each bar is one CP's covenants by their latest result."
      actions={<Legend items={LEGEND} />}>
      {rows.length === 0 && <Text size="sm" c="dimmed">No credit proposals yet.</Text>}
      <div role="list" aria-label="Covenant health by CP">
        {shown.map((r) => {
          const name = names.get(r.cpId);
          return (
            <div key={r.cpId} role="listitem" className="ladder-item">
            <button type="button" className="ladder-row"
              onClick={() => navigate(`/cp/${encodeURIComponent(r.cpId)}`)}
              aria-label={`Open ${r.cpId}${name ? `, ${name}` : ''}: ${r.breached} breached, ${r.error} could not be checked`}>
              <Group gap={6} wrap="nowrap" style={{ minWidth: 0 }}>
                <Text size="sm" fw={550} truncate="end">{name ?? r.cpId}</Text>
                {name && <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>{r.cpId}</Text>}
              </Group>
              <SplitBar parts={RESULT_PARTS(r)} height={8} ariaLabel={`${r.compliant} kept, ${r.breached} breached, ${r.error} could not be checked`} />
              <Text size="xs" ta="right" className="num" c="var(--text-bad)" fw={650}>
                {r.breached ? `${r.breached} breached` : ''}
              </Text>
              <Text size="xs" ta="right" className="num" c="var(--text-warn)">
                {r.error ? `${r.error} errors` : ''}
              </Text>
            </button>
            </div>
          );
        })}
      </div>
      {rows.length > 10 && (
        <Button variant="subtle" color="gray" size="xs" mt="xs" onClick={() => setAll((v) => !v)}>
          {all ? 'Show the worst 10' : `Show all ${rows.length}`}
        </Button>
      )}
    </ChartCard>
  );
}

/** The user's next open tasks, soonest due first, so the day's work is one click from the home page. */
function NextTasks() {
  const { data, isLoading, error } = useQuery({ queryKey: ['tasks', 'mine'], queryFn: () => r4.tasks('mine') });
  const today = isoDate(new Date());
  const open = (Array.isArray(data) ? data : []).filter((t) => t.status === 'OPEN')
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  const late = open.filter((t) => t.dueDate < today).length;
  return (
    <ChartCard title="Your next tasks"
      subtitle={open.length ? `${open.length} open${late ? ` · ${late} past due` : ''}` : undefined}
      actions={<Anchor component={Link} to="/tasks" size="sm">Open My tasks</Anchor>}>
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && open.length === 0 && <Text c="dimmed" size="sm">Nothing is waiting on you.</Text>}
      <Stack gap={0}>
        {open.slice(0, 4).map((t) => (
          <Group key={t.id} justify="space-between" wrap="nowrap" py={6} gap="sm" style={{ borderTop: '1px solid var(--app-rule)' }}>
            <Group gap={8} wrap="nowrap" style={{ minWidth: 0 }}>
              <Text size="sm" truncate="end">{t.title}</Text>
              <Anchor component={Link} to={`/cp/${encodeURIComponent(t.cpId)}`} size="xs" style={{ flexShrink: 0 }}>{t.cpId}</Anchor>
            </Group>
            <Text size="xs" c={t.dueDate < today ? 'var(--text-bad)' : 'dimmed'} style={{ flexShrink: 0 }}>
              {fmtDate(t.dueDate)}
            </Text>
          </Group>
        ))}
      </Stack>
    </ChartCard>
  );
}

const humanizeTiming = (v: string | null | undefined) => (v ? v.charAt(0) + v.slice(1).toLowerCase().replace(/_/g, ' ') : '');

/** A14: overdue checks and those due in the next 30 days, soonest first. */
function DueSoon() {
  const today = new Date();
  const to = new Date(today.getTime() + 30 * 86_400_000);
  const from = new Date(today.getTime() - 365 * 86_400_000);
  const { data, isLoading, error } = useQuery({
    queryKey: ['schedule', isoDate(today)], queryFn: () => r3.schedule(isoDate(from), isoDate(to)),
  });
  const rows = (data ?? [])
    .filter((e) => e.nextDueDate && (e.timingStatus === 'OVERDUE' || e.nextDueDate >= isoDate(today)))
    .sort((a, b) => (a.nextDueDate ?? '').localeCompare(b.nextDueDate ?? ''));
  const overdue = rows.filter((e) => e.timingStatus === 'OVERDUE').length;
  return (
    <ChartCard title="Overdue and due in the next 30 days"
      subtitle={rows.length ? `${overdue} overdue · ${rows.length - overdue} coming up` : undefined}>
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && rows.length === 0 && <Text c="dimmed" size="sm">Nothing due.</Text>}
      <Stack gap={0}>
        {rows.slice(0, 7).map((e) => (
          <div key={e.appliedCovenantId} className="due-row">
            <Text size="sm" truncate="end">{e.covenantName}</Text>
            <Text size="xs" ta="right" style={{ whiteSpace: 'nowrap' }}>{fmtDate(e.nextDueDate)}</Text>
            <Text size="xs" c="dimmed" truncate="end">
              <Anchor component={Link} to={`/cp/${encodeURIComponent(e.cpId)}`} size="xs">{e.cpId}</Anchor>
              {e.cpName ? ` · ${e.cpName}` : ''}
            </Text>
            <Text size="xs" ta="right" style={{ whiteSpace: 'nowrap' }} c={e.overdueByDays > 0 ? 'var(--text-bad)' : 'dimmed'}>
              {e.overdueByDays > 0 ? `${e.overdueByDays} days overdue` : humanizeTiming(e.timingStatus)}
            </Text>
          </div>
        ))}
      </Stack>
      {rows.length > 7 && <Text size="xs" c="dimmed" mt="xs">and {rows.length - 7} more</Text>}
    </ChartCard>
  );
}

/** FR-25 portfolio: what needs attention first, then every CP the user can see. */
export function Portfolio() {
  const { data, isLoading, error } = useQuery({ queryKey: ['summary'], queryFn: core.summary });
  const cards = useMyCps().data;
  const withTasks = canOpen(useIdentity(), pageFor('/tasks')!);
  const about = useMemo(() => new Map((cards ?? []).map((c) => [c.cpId, c])), [cards]);
  const names = useMemo(() => new Map((cards ?? []).map((c) => [c.cpId, c.name])), [cards]);
  const [filter, setFilter] = useState('');
  const [show, setShow] = useState<Show>('all');
  const [sort, setSort] = useState<Sort>('cp');
  const rows = useMemo(
    () => (data ?? []).filter((r) => [r.cpId, about.get(r.cpId)?.name]
      .some((v) => v?.toLowerCase().includes(filter.trim().toLowerCase())))
      .filter(SHOW[show]).sort(SORT[sort]),
    [data, filter, show, sort, about],
  );
  const totals = useMemo(() => (data ?? []).reduce(
    (t, r) => ({
      applied: t.applied + r.applied, compliant: t.compliant + r.compliant, breached: t.breached + r.breached,
      error: t.error + r.error, pending: t.pending + r.pendingReview,
      breachedCps: t.breachedCps + (r.breached > 0 ? 1 : 0),
    }),
    { applied: 0, compliant: 0, breached: 0, error: 0, pending: 0, breachedCps: 0 },
  ), [data]);

  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const cps = (data ?? []).length;
  const pick = (s: Show) => setShow((cur) => (cur === s ? 'all' : s));
  const finding = totals.breached || totals.error
    ? `${totals.breached} covenant${totals.breached === 1 ? ' is' : 's are'} breached on ${totals.breachedCps} CP${totals.breachedCps === 1 ? '' : 's'}`
      + `${totals.error ? `, and ${totals.error} could not be checked because figures are missing` : ''}.`
    : 'Every covenant that was checked is kept.';
  return (
    <Stack gap="lg">
      <PageHeader title="Portfolio" finding={finding}
        chips={<><Chip>{cps} credit proposal{cps === 1 ? '' : 's'}</Chip><Chip>As of {fmtDate(isoDate(new Date()))}</Chip></>}
        actions={totals.breached > 0
          ? <Button onClick={() => { setShow('breached'); setSort('breached'); }}>Review the breaches</Button>
          : undefined} />
      <KpiStrip ariaLabel="Totals">
        <Kpi label="Covenants monitored" value={totals.applied}
          note={totals.applied ? `${totals.compliant} kept · ${Math.round((totals.compliant * 100) / totals.applied)}% of all` : undefined} />
        <Kpi label="Breached" value={totals.breached} lead tone={totals.breached ? 'bad' : 'neutral'}
          note="Failed their last check" onClick={() => pick('breached')} selected={show === 'breached'}
          ariaLabel="Show CPs: Breached" />
        <Kpi label="Could not be checked" value={totals.error} tone={totals.error ? 'warn' : 'neutral'}
          note="A figure is missing or too old" onClick={() => pick('error')} selected={show === 'error'}
          ariaLabel="Show CPs: Data errors" />
        <Kpi label="Awaiting review" value={totals.pending}
          note="Waivers and extensions to decide" onClick={() => pick('pending')} selected={show === 'pending'}
          ariaLabel="Show CPs: Awaiting review" />
      </KpiStrip>
      <SimpleGrid cols={{ base: 1, lg: 3 }} spacing="lg" style={{ alignItems: 'start' }}>
        <div style={{ gridColumn: 'span 2' }}><HealthLadder rows={data ?? []} names={names} /></div>
        <Stack gap="lg"><DueSoon />{withTasks && <NextTasks />}</Stack>
      </SimpleGrid>
      <Card padding={0}>
        <Group justify="space-between" p="lg" pb="md" wrap="wrap" gap="sm">
          <Title order={4}>All credit proposals</Title>
          <Group gap="sm">
            <TextInput placeholder="Filter by CP id or borrower" aria-label="Filter by CP id" value={filter} w={240}
              onChange={(e) => setFilter(e.currentTarget.value)} />
            <SegmentedControl aria-label="Show" value={show} onChange={(v) => setShow(v as Show)} data={[
              { value: 'all', label: 'All' }, { value: 'breached', label: 'With breaches' },
              { value: 'error', label: 'With errors' }, { value: 'pending', label: 'Awaiting review' }]} />
            <Select aria-label="Sort by" w={210} value={sort} allowDeselect={false} onChange={(v) => v && setSort(v as Sort)}
              data={[{ value: 'cp', label: 'Sort: CP number' }, { value: 'breached', label: 'Sort: most breaches' },
                { value: 'error', label: 'Sort: most errors' }, { value: 'pending', label: 'Sort: most awaiting review' }]} />
            {show !== 'all' && <Badge variant="light" color="gray">{rows.length} of {cps}</Badge>}
          </Group>
        </Group>
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>CP</Table.Th><Table.Th>Borrower</Table.Th><Table.Th>Segment</Table.Th>
              <Table.Th ta="right">Applied</Table.Th><Table.Th ta="right">Kept</Table.Th>
              <Table.Th ta="right">Breached</Table.Th><Table.Th ta="right">Errors</Table.Th>
              <Table.Th ta="right">Awaiting review</Table.Th><Table.Th ta="right">Waived / extended</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((r) => (
              <Table.Tr key={r.cpId}>
                <Table.Td><Anchor component={Link} to={`/cp/${encodeURIComponent(r.cpId)}`} size="sm" fw={550}>{r.cpId}</Anchor></Table.Td>
                <Table.Td>{about.get(r.cpId)?.name ?? '—'}</Table.Td>
                <Table.Td c="dimmed">{humanize(about.get(r.cpId)?.businessSegment) || '—'}</Table.Td>
                <Table.Td ta="right">{r.applied}</Table.Td>
                <Table.Td ta="right">{r.compliant}</Table.Td>
                <Table.Td ta="right" c={r.breached ? 'var(--text-bad)' : 'dimmed'} fw={r.breached ? 650 : 400}>{r.breached}</Table.Td>
                <Table.Td ta="right" c={r.error ? 'var(--text-warn)' : 'dimmed'}>{r.error}</Table.Td>
                <Table.Td ta="right" c={r.pendingReview ? undefined : 'dimmed'}>{r.pendingReview}</Table.Td>
                <Table.Td ta="right" c={r.dispositioned ? undefined : 'dimmed'}>{r.dispositioned}</Table.Td>
              </Table.Tr>
            ))}
            {rows.length === 0 && (
              <Table.Tr><Table.Td colSpan={9}><Text c="dimmed">No credit proposals to show.</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      </Card>
    </Stack>
  );
}
