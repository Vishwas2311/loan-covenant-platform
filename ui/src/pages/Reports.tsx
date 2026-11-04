import { Anchor, Badge, Button, Card, Group, NumberInput, Stack, Table, Tabs, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { r2 } from '../api/endpoints2';
import type { BreachRow } from '../api/types2';
import { Charts } from './Charts';
import { CustomReports } from './Phase3';
import { AskAi } from './Ops';
import { Chip, Kpi, KpiStrip, PageHeader } from '../components/Board';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';
import { fmtDate } from '../format';

/** Data errors grouped by CP: one line per CP instead of one per covenant. */
function ErrorsByCp({ rows }: { rows: BreachRow[] }) {
  const [open, setOpen] = useState(false);
  const byCp = new Map<string, BreachRow[]>();
  rows.forEach((r) => byCp.set(r.cpId, [...(byCp.get(r.cpId) ?? []), r]));
  return (
    <Stack gap="xs">
      <Group justify="space-between">
        <Text fw={600} size="sm">Data errors: {rows.length} covenant(s) on {byCp.size} CP(s) could not be checked</Text>
        <Button variant="light" size="xs" onClick={() => setOpen((v) => !v)}>{open ? 'Hide' : 'Show'} data errors</Button>
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

/** FR-25 analytics and FR-31/32 escalations with the Excel management report. */
export function Reports() {
  const [days, setDays] = useState(14);
  const analytics = useQuery({ queryKey: ['analytics'], queryFn: r2.analytics });
  const breaches = useQuery({ queryKey: ['open-breaches', days], queryFn: () => r2.openBreaches(days) });
  const total = analytics.data?.total;
  const open = (breaches.data ?? []).filter((b) => b.result !== 'ERROR');
  const toEscalate = open.filter((b) => b.escalate).length;
  const errors = (breaches.data ?? []).length - open.length;
  const finding = total && breaches.data
    ? `${(total.breachRate * 100).toFixed(1)}% of checked covenants are breached across ${total.cps} CPs. `
      + `${open.length} breach${open.length === 1 ? ' is' : 'es are'} open`
      + `${toEscalate ? `, ${toEscalate} past ${days} days and due for escalation` : ''}.`
    : undefined;
  const download = (f: (d: number) => Promise<unknown>) => () => f(days).catch((e) =>
    notifications.show({ color: 'red', message: errorMessage(e) }));
  return (
    <Stack gap="md">
      <PageHeader title="Reports" finding={finding}
        chips={<><Chip>Your portfolio</Chip><Chip>Last 12 months</Chip></>}
        actions={<>
          <Button variant="light" onClick={download(r2.openBreachesXlsx)}>Download Excel</Button>
          <Button variant="light" onClick={download(r2.openBreachesPdf)}>Download PDF</Button>
        </>} />
      {total && breaches.data && (
        <KpiStrip ariaLabel="Portfolio risk">
          <Kpi label="Breach rate" value={`${(total.breachRate * 100).toFixed(1)}%`} lead tone={total.breached ? 'bad' : 'neutral'}
            note={`${total.breached} of ${total.compliant + total.breached} checked`} />
          <Kpi label="Open breaches" value={open.length} note="Not yet waived, extended or cured" />
          <Kpi label="Due for escalation" value={toEscalate} tone={toEscalate ? 'bad' : 'neutral'} note={`Open longer than ${days} days`} />
          <Kpi label="Could not be checked" value={errors} tone={errors ? 'warn' : 'neutral'} note="Figures missing" />
        </KpiStrip>
      )}
      <Charts />
      {analytics.isLoading && <Loading />}
      {analytics.error && <ErrorNote error={analytics.error} />}
      <Tabs defaultValue="breaches">
        <Tabs.List>
          <Tabs.Tab value="breaches">Open breaches{breaches.data ? ` (${open.length})` : ''}</Tabs.Tab>
          <Tabs.Tab value="custom">Your reports</Tabs.Tab>
          <Tabs.Tab value="ask">Ask AI</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="breaches" pt="md">
          <Card padding={0}>
            <Group justify="space-between" align="center" p="md" wrap="wrap">
              <Text size="sm" c="dimmed">Breaches first; covenants that could not be checked are grouped by CP below.</Text>
              <Group gap="xs" align="center">
                <Text size="sm" c="dimmed">Escalate after</Text>
                <NumberInput aria-label="Escalate after (days)" min={0} value={days} onChange={(v) => setDays(Number(v) || 0)} w={80} size="xs" />
                <Text size="sm" c="dimmed">days</Text>
              </Group>
            </Group>
            {breaches.isLoading && <Loading />}
            {breaches.error && <ErrorNote error={breaches.error} />}
            {breaches.data && (
              <Table>
                <Table.Thead>
                  <Table.Tr><Table.Th>CP</Table.Th><Table.Th>Covenant</Table.Th><Table.Th>Result</Table.Th>
                    <Table.Th>Open since</Table.Th><Table.Th ta="right">Days</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {open.map((b) => (
                    <Table.Tr key={b.appliedCovenantId}>
                      <Table.Td><Anchor component={Link} to={`/cp/${encodeURIComponent(b.cpId)}`} size="sm" fw={550}>{b.cpId}</Anchor></Table.Td>
                      <Table.Td>{b.covenantName}</Table.Td>
                      <Table.Td><StatusBadge value={b.result} /></Table.Td>
                      <Table.Td style={{ whiteSpace: 'nowrap' }}>{fmtDate(b.since)}</Table.Td>
                      <Table.Td ta="right">{b.daysOpen}</Table.Td>
                      <Table.Td><StatusBadge value={b.status} />{b.approval && b.approval !== 'APPROVED' &&
                        <Text size="xs">{b.approval.toLowerCase()}</Text>}</Table.Td>
                      <Table.Td>{b.escalate && <Badge color="red">Escalate</Badge>}</Table.Td>
                    </Table.Tr>
                  ))}
                  {open.length === 0 && (
                    <Table.Tr><Table.Td colSpan={7}><Text c="dimmed">No open breaches.</Text></Table.Td></Table.Tr>
                  )}
                </Table.Tbody>
              </Table>
            )}
            {breaches.data && breaches.data.some((b) => b.result === 'ERROR') && (
              <div style={{ padding: 'var(--mantine-spacing-md)' }}>
                <ErrorsByCp rows={breaches.data.filter((b) => b.result === 'ERROR')} />
              </div>
            )}
          </Card>
        </Tabs.Panel>
        <Tabs.Panel value="custom" pt="md"><CustomReports /></Tabs.Panel>
        <Tabs.Panel value="ask" pt="md"><AskAi /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
