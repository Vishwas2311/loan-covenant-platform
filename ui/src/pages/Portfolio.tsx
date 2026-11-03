import {
  Anchor, Badge, Card, Group, SegmentedControl, Select, SimpleGrid, Stack, Table, Text, TextInput, Title,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { core } from '../api/endpoints';
import { r3 } from '../api/endpoints3';
import type { CpSummary } from '../api/types';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';

function Kpi({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <Card withBorder padding="md">
      <Text size="sm" c="dimmed">{label}</Text>
      <Text fw={700} size="xl" c={color}>{value}</Text>
    </Card>
  );
}

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

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

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
  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Title order={4}>Due in the next 30 days</Title>
        {rows.length > 10 && <Text size="sm" c="dimmed">showing 10 of {rows.length}</Text>}
      </Group>
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && rows.length === 0 && <Text c="dimmed">Nothing due.</Text>}
      {rows.length > 0 && (
        <Table>
          <Table.Tbody>
            {rows.slice(0, 10).map((e) => (
              <Table.Tr key={e.appliedCovenantId}>
                <Table.Td>{e.nextDueDate}</Table.Td>
                <Table.Td><Anchor component={Link} to={`/cp/${encodeURIComponent(e.cpId)}`}>{e.cpId}</Anchor></Table.Td>
                <Table.Td>{e.covenantName}</Table.Td>
                <Table.Td>
                  <StatusBadge value={e.timingStatus} />
                  {e.overdueByDays > 0 && <Text span size="xs" c="red"> {e.overdueByDays} d</Text>}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Card>
  );
}

/** FR-25 portfolio scorecard: one row per CP the user can see (Core scopes the list by ownership). */
export function Portfolio() {
  const { data, isLoading, error } = useQuery({ queryKey: ['summary'], queryFn: core.summary });
  const [filter, setFilter] = useState('');
  const [show, setShow] = useState<Show>('all');
  const [sort, setSort] = useState<Sort>('cp');
  const rows = useMemo(
    () => (data ?? []).filter((r) => r.cpId.toLowerCase().includes(filter.trim().toLowerCase()))
      .filter(SHOW[show]).sort(SORT[sort]),
    [data, filter, show, sort],
  );
  const totals = useMemo(() => (data ?? []).reduce(
    (t, r) => ({
      applied: t.applied + r.applied, breached: t.breached + r.breached, error: t.error + r.error,
      pending: t.pending + r.pendingReview,
    }),
    { applied: 0, breached: 0, error: 0, pending: 0 },
  ), [data]);

  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  return (
    <Stack>
      <Title order={2}>Portfolio</Title>
      <SimpleGrid cols={{ base: 2, md: 4 }}>
        <Kpi label="Covenants monitored" value={totals.applied} />
        <Kpi label="Breached" value={totals.breached} color="red" />
        <Kpi label="Data errors" value={totals.error} color="orange" />
        <Kpi label="Awaiting review" value={totals.pending} color="yellow.8" />
      </SimpleGrid>
      <DueSoon />
      <Group align="end">
        <TextInput placeholder="Filter by CP id" aria-label="Filter by CP id" value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)} />
        <SegmentedControl aria-label="Show" value={show} onChange={(v) => setShow(v as Show)} data={[
          { value: 'all', label: 'All' }, { value: 'breached', label: 'With breaches' },
          { value: 'error', label: 'With errors' }, { value: 'pending', label: 'Awaiting review' }]} />
        <Select aria-label="Sort by" w={200} value={sort} allowDeselect={false} onChange={(v) => v && setSort(v as Sort)}
          data={[{ value: 'cp', label: 'Sort: CP number' }, { value: 'breached', label: 'Sort: most breaches' },
            { value: 'error', label: 'Sort: most errors' }, { value: 'pending', label: 'Sort: most awaiting review' }]} />
        {show !== 'all' && <Badge variant="light">{rows.length} of {(data ?? []).length}</Badge>}
      </Group>
      <Table striped highlightOnHover withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>CP</Table.Th><Table.Th>Applied</Table.Th><Table.Th>Compliant</Table.Th>
            <Table.Th>Breached</Table.Th><Table.Th>Error</Table.Th><Table.Th>Pending review</Table.Th>
            <Table.Th>Waived / extended</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((r) => (
            <Table.Tr key={r.cpId}>
              <Table.Td><Anchor component={Link} to={`/cp/${encodeURIComponent(r.cpId)}`}>{r.cpId}</Anchor></Table.Td>
              <Table.Td>{r.applied}</Table.Td>
              <Table.Td>{r.compliant}</Table.Td>
              <Table.Td c={r.breached ? 'red' : undefined}>{r.breached}</Table.Td>
              <Table.Td c={r.error ? 'orange' : undefined}>{r.error}</Table.Td>
              <Table.Td>{r.pendingReview}</Table.Td>
              <Table.Td>{r.dispositioned}</Table.Td>
            </Table.Tr>
          ))}
          {rows.length === 0 && (
            <Table.Tr><Table.Td colSpan={7}><Text c="dimmed">No credit proposals to show.</Text></Table.Td></Table.Tr>
          )}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
