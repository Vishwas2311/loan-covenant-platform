import { Badge, Button, Card, Group, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { r5, syncApi } from '../api/endpoints5';
import { ErrorNote, Loading } from '../components/Status';

const fail = (e: unknown) => notifications.show({ color: 'red', message: errorMessage(e) });

/** C1: who at the borrower may use the portal for this CP. The RM or approver grants and removes access. */
export function PortalAccessCard({ cpId, canEdit }: { cpId: string; canEdit: boolean }) {
  const qc = useQueryClient();
  const [userId, setUserId] = useState('');
  const access = useQuery({ queryKey: ['portal-access', cpId], queryFn: () => r5.portalAccess(cpId) });
  const done = (list: unknown) => qc.setQueryData(['portal-access', cpId], list);
  const grant = useMutation({
    mutationFn: () => r5.grantPortal(cpId, userId.trim()),
    onSuccess: (list) => {
      done(list);
      setUserId('');
      notifications.show({ color: 'green', message: 'Portal access given.' });
    },
    onError: fail,
  });
  const revoke = useMutation({ mutationFn: (u: string) => r5.revokePortal(cpId, u), onSuccess: done, onError: fail });
  const rows = Array.isArray(access.data) ? access.data : [];
  return (
    <Card withBorder>
      <Stack gap="xs">
        <Title order={5}>Borrower portal access</Title>
        <Text size="xs" c="dimmed">
          People at the borrower listed here can sign in to the borrower portal, see what is due on this loan, and
          send certificates and statements. They see nothing else.
        </Text>
        {access.isLoading && <Loading />}
        {access.error && <ErrorNote error={access.error} />}
        {rows.map((a) => (
          <Group key={a.userId} gap="xs">
            <Badge variant="light">{a.userId}</Badge>
            <Text size="xs" c="dimmed">given by {a.grantedBy} on {new Date(a.grantedAt).toLocaleDateString()}</Text>
            {canEdit && (
              <Button size="compact-xs" variant="subtle" color="red"
                onClick={() => window.confirm(`Remove portal access for ${a.userId}?`) && revoke.mutate(a.userId)}>Remove</Button>
            )}
          </Group>
        ))}
        {canEdit && (
          <Group align="end">
            <TextInput label="Borrower's user id" value={userId} onChange={(e) => setUserId(e.currentTarget.value)}
              placeholder="borrower-reliance" />
            <Button onClick={() => grant.mutate()} loading={grant.isPending}
              disabled={!/^[A-Za-z0-9._@-]{3,64}$/.test(userId.trim())}>Give access</Button>
          </Group>
        )}
      </Stack>
    </Card>
  );
}

/** C8: the nightly check against the bank's loan system: what changed, and a way to run it now. */
export function LoanSyncPanel({ token }: { token: string }) {
  const qc = useQueryClient();
  const api = syncApi(token);
  const runs = useQuery({ queryKey: ['loan-sync-runs'], queryFn: api.runs });
  const run = useMutation({
    mutationFn: api.runNow,
    onSuccess: (r) => {
      notifications.show({ color: r.errors ? 'orange' : 'green',
        message: `${r.cpsChecked} CP(s) checked, ${r.cpsChanged} changed, ${r.cpsClosed} closed${r.errors ? `, ${r.errors} error(s)` : ''}.` });
      qc.invalidateQueries({ queryKey: ['loan-sync-runs'] });
    },
    onError: fail,
  });
  const list = Array.isArray(runs.data) ? runs.data : [];
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        Every night the app asks the bank&apos;s loan system about each CP. A new RM or approver is copied in and
        their open tasks move with them; a closed or repaid loan stops being monitored.
      </Text>
      <Button w="fit-content" onClick={() => window.confirm('Check every CP against the loan system now?') && run.mutate()}
        loading={run.isPending}>Run the sync now</Button>
      {runs.isLoading && <Loading />}
      {runs.error && <ErrorNote error={runs.error} />}
      {list.map((r) => (
        <Card key={r.id} withBorder>
          <Text size="sm" fw={600}>
            {new Date(r.startedAt).toLocaleString()} · {r.cpsChecked} checked · {r.cpsChanged} changed · {r.cpsClosed} closed
            {r.errors > 0 && ` · ${r.errors} error(s)`} · by {r.triggeredBy}
          </Text>
          {r.changes.length > 0 && (
            <Table mt="xs">
              <Table.Thead><Table.Tr><Table.Th>CP</Table.Th><Table.Th>What</Table.Th><Table.Th>From → to</Table.Th></Table.Tr></Table.Thead>
              <Table.Tbody>
                {r.changes.map((c, i) => (
                  <Table.Tr key={i}>
                    <Table.Td>{c.cpId}</Table.Td><Table.Td>{c.field}</Table.Td>
                    <Table.Td>{c.oldValue ?? '—'} → {c.newValue ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Card>
      ))}
      {runs.data && list.length === 0 && <Text c="dimmed">No sync has run yet.</Text>}
    </Stack>
  );
}
