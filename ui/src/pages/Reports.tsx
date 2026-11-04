import { Anchor, Badge, Button, Group, NumberInput, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { r2 } from '../api/endpoints2';
import type { AnalyticsGroup, BreachRow } from '../api/types2';
import { Charts } from './Charts';
import { CustomReports } from './Phase3';
import { AskAi } from './Ops';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';

/** Data errors grouped by CP: one line per CP instead of one per covenant. */
function ErrorsByCp({ rows }: { rows: BreachRow[] }) {
  const [open, setOpen] = useState(false);
  const byCp = new Map<string, BreachRow[]>();
  rows.forEach((r) => byCp.set(r.cpId, [...(byCp.get(r.cpId) ?? []), r]));
  return (
    <Stack gap="xs">
      <Group justify="space-between">
        <Text fw={600}>Data errors: {rows.length} covenant(s) on {byCp.size} CP(s) could not be checked</Text>
        <Button variant="subtle" size="xs" onClick={() => setOpen((v) => !v)}>{open ? 'Hide' : 'Show'} data errors</Button>
      </Group>
      {open && (
        <Table withTableBorder striped>
          <Table.Thead>
            <Table.Tr><Table.Th>CP</Table.Th><Table.Th>Covenants with an error</Table.Th><Table.Th>Open since</Table.Th>
              <Table.Th>Days</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {[...byCp.entries()].map(([cp, list]) => (
              <Table.Tr key={cp}>
                <Table.Td><Anchor component={Link} to={`/cp/${encodeURIComponent(cp)}`}>{cp}</Anchor></Table.Td>
                <Table.Td>
                  <Text size="sm">{list.length} covenant(s)</Text>
                  <Text size="xs" c="dimmed" lineClamp={2}>{list.map((r) => r.covenantName).join(', ')}</Text>
                </Table.Td>
                <Table.Td>{list.map((r) => r.since).sort()[0]}</Table.Td>
                <Table.Td>{Math.max(...list.map((r) => r.daysOpen))}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

function GroupTable({ title, rows }: { title: string; rows: AnalyticsGroup[] }) {
  return (
    <Stack gap="xs">
      <Title order={5}>{title}</Title>
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr><Table.Th /><Table.Th>CPs</Table.Th><Table.Th>Covenants</Table.Th><Table.Th>Breached</Table.Th>
            <Table.Th>Errors</Table.Th><Table.Th>Breach rate</Table.Th></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((g) => (
            <Table.Tr key={g.key}>
              <Table.Td>{g.key}</Table.Td><Table.Td>{g.cps}</Table.Td><Table.Td>{g.applied}</Table.Td>
              <Table.Td c={g.breached ? 'red' : undefined}>{g.breached}</Table.Td><Table.Td>{g.error}</Table.Td>
              <Table.Td>{(g.breachRate * 100).toFixed(1)}%</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

/** FR-25 analytics and FR-31/32 escalations with the Excel management report. */
export function Reports() {
  const [days, setDays] = useState(14);
  const analytics = useQuery({ queryKey: ['analytics'], queryFn: r2.analytics });
  const breaches = useQuery({ queryKey: ['open-breaches', days], queryFn: () => r2.openBreaches(days) });
  return (
    <Stack>
      <Title order={2}>Reports</Title>
      <Charts />
      <CustomReports />
      <AskAi />
      {analytics.isLoading && <Loading />}
      {analytics.error && <ErrorNote error={analytics.error} />}
      {analytics.data && (
        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          <GroupTable title="By business segment" rows={analytics.data.bySegment} />
          <GroupTable title="By sector" rows={analytics.data.bySector} />
        </SimpleGrid>
      )}
      <Group justify="space-between" align="end">
        <Title order={4}>Open breaches and errors</Title>
        <Group align="end">
          <NumberInput label="Escalate after (days)" min={0} value={days} onChange={(v) => setDays(Number(v) || 0)} w={170} />
          <Button variant="light" onClick={() => r2.openBreachesXlsx(days).catch((e) =>
            notifications.show({ color: 'red', message: errorMessage(e) }))}>Download Excel</Button>
          <Button variant="light" onClick={() => r2.openBreachesPdf(days).catch((e) =>
            notifications.show({ color: 'red', message: errorMessage(e) }))}>Download PDF</Button>
        </Group>
      </Group>
      {breaches.isLoading && <Loading />}
      {breaches.error && <ErrorNote error={breaches.error} />}
      {breaches.data && (
        <Table withTableBorder striped>
          <Table.Thead>
            <Table.Tr><Table.Th>CP</Table.Th><Table.Th>Covenant</Table.Th><Table.Th>Result</Table.Th>
              <Table.Th>Open since</Table.Th><Table.Th>Days</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {breaches.data.filter((b) => b.result !== 'ERROR').map((b) => (
              <Table.Tr key={b.appliedCovenantId}>
                <Table.Td><Anchor component={Link} to={`/cp/${encodeURIComponent(b.cpId)}`}>{b.cpId}</Anchor></Table.Td>
                <Table.Td>{b.covenantName}</Table.Td>
                <Table.Td><StatusBadge value={b.result} /></Table.Td>
                <Table.Td>{b.since}</Table.Td>
                <Table.Td>{b.daysOpen}</Table.Td>
                <Table.Td><StatusBadge value={b.status} />{b.approval && b.approval !== 'APPROVED' &&
                  <Text size="xs">{b.approval.toLowerCase()}</Text>}</Table.Td>
                <Table.Td>{b.escalate && <Badge color="red">Escalate</Badge>}</Table.Td>
              </Table.Tr>
            ))}
            {breaches.data.filter((b) => b.result !== 'ERROR').length === 0 && (
              <Table.Tr><Table.Td colSpan={7}><Text c="dimmed">No open breaches.</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      )}
      {breaches.data && breaches.data.some((b) => b.result === 'ERROR') && (
        <ErrorsByCp rows={breaches.data.filter((b) => b.result === 'ERROR')} />
      )}
    </Stack>
  );
}
