import {
  Alert, Anchor, AppShell, Badge, Button, Card, Checkbox, FileInput, Group, Select, Stack, Table, Tabs, Text,
  TextInput, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { r5, type PortalView } from '../api/endpoints5';
import { ColorSchemeToggle } from '../components/ColorSchemeToggle';
import { ErrorNote, Loading } from '../components/Status';

const fail = (e: unknown) => notifications.show({ color: 'red', message: errorMessage(e) });

function CertificateForm({ view }: { view: PortalView }) {
  const qc = useQueryClient();
  const open = view.requirements.filter((r) => r.state !== 'RECEIVED');
  const [period, setPeriod] = useState<string | null>(open[0]?.periodEnd ?? null);
  const [file, setFile] = useState<File | null>(null);
  const [signedBy, setSignedBy] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const submit = useMutation({
    mutationFn: () => r5.submitCertificate(view.cpId, file!, period!, signedBy.trim()),
    onSuccess: (v) => {
      qc.setQueryData(['portal', view.cpId], v);
      notifications.show({ color: 'green', message: 'Certificate sent to the bank. Thank you.' });
      setFile(null);
      setConfirmed(false);
    },
    onError: fail,
  });
  if (open.length === 0) {
    const last = [...view.requirements].filter((r) => r.state === 'RECEIVED')
      .sort((a, b) => b.periodEnd.localeCompare(a.periodEnd))[0];
    const next = view.covenants.map((c) => c.nextDueDate).filter((d): d is string => !!d).sort()[0];
    return (
      <Alert color="green" variant="light" title="You are up to date">
        <Text size="sm">
          {last ? `The bank has your certificate for the period ending ${last.periodEnd}. ` : 'No certificate is due yet. '}
          {next ? `Its next check is on ${next}; a new request will appear here before then. ` : ''}
          See what you sent under &ldquo;What you sent&rdquo;.
        </Text>
      </Alert>
    );
  }
  return (
    <Stack>
      <Select label="For the period ending" value={period} onChange={setPeriod} allowDeselect={false}
        data={open.map((r) => ({ value: r.periodEnd, label: `${r.periodEnd} (due ${r.dueDate})` }))} />
      <FileInput label="Signed certificate (PDF)" value={file} onChange={setFile} accept=".pdf" placeholder="Choose the file" />
      <TextInput label="Signed by (your full name and title)" value={signedBy} onChange={(e) => setSignedBy(e.currentTarget.value)}
        placeholder="Priya Rao, Chief Financial Officer" />
      <Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.currentTarget.checked)}
        label="I confirm this certificate is true and complete, and I am authorised to sign it for the borrower." />
      <Group justify="end">
        <Button onClick={() => submit.mutate()} loading={submit.isPending}
          disabled={!file || !period || signedBy.trim().length < 3 || !confirmed}>Send to the bank</Button>
      </Group>
    </Stack>
  );
}

function StatementForm({ view }: { view: PortalView }) {
  const qc = useQueryClient();
  const [period, setPeriod] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const submit = useMutation({
    mutationFn: () => r5.submitStatement(view.cpId, file!, period),
    onSuccess: (v) => {
      qc.setQueryData(['portal', view.cpId], v);
      notifications.show({ color: 'green', message: 'Statement sent to the bank. It is being read now.' });
      setFile(null);
    },
    onError: fail,
  });
  return (
    <Stack>
      <FileInput label="Financial statement (PDF, XLSX or CSV)" value={file} onChange={setFile} accept=".pdf,.xlsx,.csv"
        placeholder="Choose the file" />
      <TextInput type="date" label="Period end" value={period} onChange={(e) => setPeriod(e.currentTarget.value)} />
      <Group justify="end">
        <Button onClick={() => submit.mutate()} loading={submit.isPending} disabled={!file || !period}>Send to the bank</Button>
      </Group>
    </Stack>
  );
}

function LoanView({ cpId }: { cpId: string }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['portal', cpId], queryFn: () => r5.portalCp(cpId) });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const v = data!;
  return (
    <Stack>
      <Title order={3}>{v.name ?? v.cpId}</Title>
      <Card withBorder>
        <Title order={5} mb="xs">What the bank is waiting for</Title>
        <Table>
          <Table.Thead><Table.Tr><Table.Th>Compliance certificate for</Table.Th><Table.Th>Due</Table.Th><Table.Th>State</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {v.requirements.map((r) => (
              <Table.Tr key={r.id}>
                <Table.Td>Period ending {r.periodEnd}</Table.Td>
                <Table.Td>{r.dueDate}</Table.Td>
                <Table.Td>
                  {r.state === 'RECEIVED' && <Badge color="green" variant="light">received</Badge>}
                  {r.state === 'PENDING' && <Badge color="gray" variant="light">waiting</Badge>}
                  {r.state === 'OVERDUE' && <Badge color="red" variant="light">{r.overdueDays} days late</Badge>}
                </Table.Td>
              </Table.Tr>
            ))}
            {v.requirements.length === 0 && <Table.Tr><Table.Td colSpan={3}><Text c="dimmed">Nothing is due.</Text></Table.Td></Table.Tr>}
          </Table.Tbody>
        </Table>
        {v.covenants.length > 0 && (
          <>
            <Title order={6} mt="md" mb={4}>What the bank checks, and when next</Title>
            <Table>
              <Table.Tbody>
                {[...v.covenants].sort((a, b) => (a.nextDueDate ?? '9').localeCompare(b.nextDueDate ?? '9')).map((c) => (
                  <Table.Tr key={c.name}>
                    <Table.Td><Text size="sm">{c.name}</Text></Table.Td>
                    <Table.Td><Text size="sm" c="dimmed">{c.nextDueDate ?? 'not scheduled'}</Text></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </>
        )}
      </Card>
      <Tabs defaultValue="certificate">
        <Tabs.List>
          <Tabs.Tab value="certificate">Send a compliance certificate</Tabs.Tab>
          <Tabs.Tab value="statement">Send a financial statement</Tabs.Tab>
          <Tabs.Tab value="history">What you sent</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="certificate" pt="md"><CertificateForm view={v} /></Tabs.Panel>
        <Tabs.Panel value="statement" pt="md"><StatementForm view={v} /></Tabs.Panel>
        <Tabs.Panel value="history" pt="md">
          <Table>
            <Table.Thead><Table.Tr><Table.Th>When</Table.Th><Table.Th>What</Table.Th><Table.Th>File</Table.Th><Table.Th>By</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {v.submissions.map((s, i) => (
                <Table.Tr key={i}>
                  <Table.Td>{new Date(s.submittedAt).toLocaleString()}</Table.Td>
                  <Table.Td>{s.kind === 'CERTIFICATE' ? 'Certificate' : 'Statement'}{s.periodEnd ? ` for ${s.periodEnd}` : ''}</Table.Td>
                  <Table.Td>{s.fileName}</Table.Td>
                  <Table.Td>{s.submittedBy}</Table.Td>
                </Table.Tr>
              ))}
              {v.submissions.length === 0 && <Table.Tr><Table.Td colSpan={4}><Text c="dimmed">Nothing sent yet.</Text></Table.Td></Table.Tr>}
            </Table.Tbody>
          </Table>
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

/** C1: the borrower's own portal: only the loans the bank shared with them, and only these actions. */
export function PortalApp({ user, onSignOut }: { user: string; onSignOut?: () => void }) {
  const [cpId, setCpId] = useState<string | null>(null);
  const cps = useQuery({ queryKey: ['portal-cps'], queryFn: r5.portalCps });
  const list = Array.isArray(cps.data) ? cps.data : [];
  const chosen = cpId ?? list[0]?.cpId ?? null;
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Title order={4}>Borrower portal</Title>
          <Group gap="sm">
            <ColorSchemeToggle />
            <Text size="sm">{user} (Borrower)</Text>
            {onSignOut && <Button size="xs" variant="default" onClick={onSignOut}>Sign out</Button>}
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Stack maw={900}>
          <Alert color="blue" variant="light">
            Send your compliance certificates and financial statements to the bank here. You only see the loans the
            bank has shared with you.
          </Alert>
          {cps.isLoading && <Loading />}
          {cps.error && <ErrorNote error={cps.error} />}
          {cps.data && list.length === 0 && <Text c="dimmed">The bank has not shared any loan with you yet.</Text>}
          {list.length > 1 && (
            <Group gap="xs">
              {list.map((c) => (
                <Anchor key={c.cpId} component="button" fw={c.cpId === chosen ? 700 : 400} onClick={() => setCpId(c.cpId)}>
                  {c.name ?? c.cpId}
                </Anchor>
              ))}
            </Group>
          )}
          {chosen && <LoanView key={chosen} cpId={chosen} />}
        </Stack>
      </AppShell.Main>
    </AppShell>
  );
}
