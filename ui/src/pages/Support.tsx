import {
  Alert, Badge, Button, Code, Group, Modal, PasswordInput, Select, Stack, Table, Tabs, Text, TextInput, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { r3, setSupportToken } from '../api/endpoints3';
import type { SentNotification } from '../api/types3';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';

const fail = (e: unknown) => notifications.show({ color: 'red', message: errorMessage(e) });
const when = (v: string | null | undefined) => (v ? new Date(v).toLocaleString() : '—');

/** Why a CP's covenants do or do not check, with the two repairs: reload its data and re-check one covenant. */
function Diagnostics() {
  const [cp, setCp] = useState('');
  const [asked, setAsked] = useState('');
  const diag = useQuery({ queryKey: ['diagnostics', asked], queryFn: () => r3.cpDiagnostics(asked), enabled: !!asked });
  const reingest = useMutation({
    mutationFn: () => r3.reingest(asked),
    onSuccess: (r) => {
      notifications.show({ color: r.warnings.length ? 'orange' : 'green',
        message: `Data reloaded${r.tablesTouched.length ? ` from ${r.tablesTouched.join(', ')}` : ''}.`
          + (r.warnings.length ? ` Warnings: ${r.warnings.join('; ')}` : '') });
      diag.refetch();
    },
    onError: fail,
  });
  const reassess = useMutation({
    mutationFn: (appliedId: string) => r3.reassess(appliedId),
    onSuccess: (r) => notifications.show({ color: 'green', message: `Re-checked: ${r.assessmentResult ?? 'no result'}` }),
    onError: fail,
  });
  return (
    <Stack>
      <Group align="end">
        <TextInput label="CP" value={cp} onChange={(e) => setCp(e.currentTarget.value.toUpperCase())} placeholder="CP-1001" />
        <Button onClick={() => setAsked(cp.trim())} disabled={!cp.trim()}>Diagnose</Button>
        {asked && <Button variant="light" onClick={() => reingest.mutate()} loading={reingest.isPending}>Reload data</Button>}
      </Group>
      {diag.isLoading && <Loading />}
      {diag.error && <ErrorNote error={diag.error} />}
      {diag.data && (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr><Table.Th>Covenant</Table.Th><Table.Th>Rule</Table.Th><Table.Th>Rule valid</Table.Th>
              <Table.Th>Figures found</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {diag.data.covenants.map((c) => (
              <Table.Tr key={c.appliedCovenantId}>
                <Table.Td>{c.covenantName}<Text size="xs" c="dimmed">{c.assessmentType}</Text></Table.Td>
                <Table.Td><Text ff="monospace" size="xs">{c.conditionExpression}</Text></Table.Td>
                <Table.Td>{c.compiled ? <Badge color="green" variant="light">yes</Badge>
                  : <Text size="xs" c="red">{c.compileError ?? 'no'}</Text>}</Table.Td>
                <Table.Td>{c.wouldResolve ? <Badge color="green" variant="light">yes</Badge>
                  : <Badge color="orange" variant="light">missing</Badge>}</Table.Td>
                <Table.Td>
                  <Button size="xs" variant="subtle" loading={reassess.isPending && reassess.variables === c.appliedCovenantId}
                    onClick={() => reassess.mutate(c.appliedCovenantId)}>Re-check</Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

function AuditLog() {
  const [type, setType] = useState('cp');
  const [id, setId] = useState('');
  const log = useMutation({ mutationFn: () => r3.opsAudit(type, id.trim() || undefined) });
  return (
    <Stack>
      <Group align="end">
        <Select label="About" w={200} value={type} allowDeselect={false} onChange={(v) => v && setType(v)}
          data={[{ value: 'cp', label: 'A CP' }, { value: 'applied_covenant', label: 'A covenant on a CP' }]} />
        <TextInput label="Id (optional)" value={id} onChange={(e) => setId(e.currentTarget.value)} />
        <Button onClick={() => log.mutate()} loading={log.isPending}>Show</Button>
      </Group>
      {log.error && <ErrorNote error={log.error} />}
      {log.data && (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr><Table.Th>When</Table.Th><Table.Th>Action</Table.Th><Table.Th>On</Table.Th><Table.Th>By</Table.Th>
              <Table.Th>Outcome</Table.Th><Table.Th>Detail</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {log.data.map((a) => (
              <Table.Tr key={a.id}>
                <Table.Td>{when(a.performedAt)}</Table.Td><Table.Td>{a.action}</Table.Td>
                <Table.Td>{a.targetId}</Table.Td><Table.Td>{a.performedBy}</Table.Td>
                <Table.Td>{a.outcome}</Table.Td><Table.Td><Text size="xs">{a.detail ?? a.paramsSummary}</Text></Table.Td>
              </Table.Tr>
            ))}
            {log.data.length === 0 && <Table.Tr><Table.Td colSpan={6}><Text c="dimmed">Nothing recorded.</Text></Table.Td></Table.Tr>}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

function Health() {
  const health = useQuery({ queryKey: ['source-health'], queryFn: r3.dataSourceHealth });
  if (health.isLoading) {
    return <Loading />;
  }
  if (health.error) {
    return <ErrorNote error={health.error} />;
  }
  const h = health.data!;
  return (
    <Stack>
      <Text>{h.reachableCount} of {h.sourceCount} data sources reachable · checked {when(h.checkedAt)}</Text>
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr><Table.Th>Source</Table.Th><Table.Th>Reachable</Table.Th><Table.Th>Response</Table.Th>
            <Table.Th>Tables</Table.Th><Table.Th>Staged rows</Table.Th><Table.Th>Last read</Table.Th></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {h.sources.map((s) => (
            <Table.Tr key={s.id}>
              <Table.Td>{s.name}<Text size="xs" c="dimmed">{s.message}</Text></Table.Td>
              <Table.Td>{s.reachable ? <Badge color="green" variant="light">yes</Badge> : <Badge color="red" variant="light">no</Badge>}</Table.Td>
              <Table.Td>{s.latencyMs} ms</Table.Td><Table.Td>{s.tableCount}</Table.Td>
              <Table.Td>{s.stagingRowCount}</Table.Td><Table.Td>{when(s.lastIngestAt)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Button w="fit-content" variant="light" onClick={() => health.refetch()} loading={health.isFetching}>Check again</Button>
    </Stack>
  );
}

/** B5 + A15: every daily summary e-mail sent, readable here, and a way to send today's now. */
function Emails() {
  const [open, setOpen] = useState<SentNotification | null>(null);
  const list = useQuery({ queryKey: ['sent-emails'], queryFn: r3.notifications });
  const send = useMutation({
    mutationFn: r3.sendDailyEmail,
    onSuccess: (r) => {
      notifications.show({ color: r.notificationsFailed ? 'orange' : 'green',
        message: `${r.covenantsAssessed} covenant(s) checked, ${r.notificationsSent} e-mail(s) sent`
          + (r.notificationsFailed ? `, ${r.notificationsFailed} failed` : '') });
      list.refetch();
    },
    onError: fail,
  });
  let pretty = '';
  try {
    pretty = open?.requestBody ? JSON.stringify(JSON.parse(open.requestBody), null, 2) : '';
  } catch {
    pretty = open?.requestBody ?? '';
  }
  return (
    <Stack>
      <Group>
        <Button onClick={() => window.confirm('Run today\'s check and send the summary e-mails now?') && send.mutate()}
          loading={send.isPending}>Send today&apos;s e-mails now</Button>
      </Group>
      {list.isLoading && <Loading />}
      {list.error && <ErrorNote error={list.error} />}
      {list.data && (
        <Table withTableBorder highlightOnHover>
          <Table.Thead>
            <Table.Tr><Table.Th>When</Table.Th><Table.Th>To</Table.Th><Table.Th>Copy to</Table.Th><Table.Th>CPs</Table.Th>
              <Table.Th>Outcome</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {list.data.map((n) => (
              <Table.Tr key={n.id} onClick={() => setOpen(n)} style={{ cursor: 'pointer' }}>
                <Table.Td>{when(n.changedDate)}</Table.Td><Table.Td>{n.emailTo}</Table.Td><Table.Td>{n.emailCc}</Table.Td>
                <Table.Td>{n.cpIds}</Table.Td>
                <Table.Td><StatusBadge value={n.outcome === 'SUCCESS' ? 'DONE' : 'FAILED'} />
                  {n.errorMessage && <Text size="xs" c="red">{n.errorMessage}</Text>}</Table.Td>
              </Table.Tr>
            ))}
            {list.data.length === 0 && <Table.Tr><Table.Td colSpan={5}><Text c="dimmed">No e-mails sent yet.</Text></Table.Td></Table.Tr>}
          </Table.Tbody>
        </Table>
      )}
      <Modal opened={!!open} onClose={() => setOpen(null)} size="xl" title={`E-mail to ${open?.emailTo ?? ''}`}>
        <Text size="sm">Copy to: {open?.emailCc ?? '—'} · {when(open?.changedDate)} · status {open?.httpStatus ?? '—'}</Text>
        <Text size="xs" c="dimmed" mb="xs">What Core sent to the bank&apos;s e-mail service (template and the rows it fills in):</Text>
        <Code block>{pretty}</Code>
      </Modal>
    </Stack>
  );
}

/** A12: tools for Support staff. Core also asks for the shared support token on these actions. */
export function Support() {
  const [token, setToken] = useState('');
  const [ready, setReady] = useState(false);
  return (
    <Stack>
      <Title order={2}>Support tools</Title>
      <Alert color="blue" variant="light">
        These tools change or reveal data, so Core asks for the support token as well as your role. It is kept
        only while this page is open. Every action is written to the audit log.
      </Alert>
      <Group align="end">
        <PasswordInput label="Support token" value={token} w={280} onChange={(e) => setToken(e.currentTarget.value)} />
        <Button onClick={() => { setSupportToken(token); setReady(true); }} disabled={!token.trim()}>Use token</Button>
      </Group>
      {ready && (
        <Tabs defaultValue="diagnostics" keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="diagnostics">Diagnose a CP</Tabs.Tab>
            <Tabs.Tab value="emails">Sent e-mails</Tabs.Tab>
            <Tabs.Tab value="health">Data source health</Tabs.Tab>
            <Tabs.Tab value="audit">Audit log</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="diagnostics" pt="md"><Diagnostics /></Tabs.Panel>
          <Tabs.Panel value="emails" pt="md"><Emails /></Tabs.Panel>
          <Tabs.Panel value="health" pt="md"><Health /></Tabs.Panel>
          <Tabs.Panel value="audit" pt="md"><AuditLog /></Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}
