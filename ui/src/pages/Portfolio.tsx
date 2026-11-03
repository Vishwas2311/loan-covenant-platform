import { Anchor, Card, Group, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { core } from '../api/endpoints';
import { ErrorNote, Loading } from '../components/Status';

function Kpi({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <Card withBorder padding="md">
      <Text size="sm" c="dimmed">{label}</Text>
      <Text fw={700} size="xl" c={color}>{value}</Text>
    </Card>
  );
}

/** FR-25 portfolio scorecard: one row per CP the user can see (Core scopes the list by ownership). */
export function Portfolio() {
  const { data, isLoading, error } = useQuery({ queryKey: ['summary'], queryFn: core.summary });
  const [filter, setFilter] = useState('');
  const rows = useMemo(
    () => (data ?? []).filter((r) => r.cpId.toLowerCase().includes(filter.trim().toLowerCase())),
    [data, filter],
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
      <Group>
        <TextInput placeholder="Filter by CP id" aria-label="Filter by CP id" value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)} />
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
