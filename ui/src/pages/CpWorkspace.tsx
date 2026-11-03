import {
  Anchor, Badge, Button, Group, Modal, SegmentedControl, Stack, Table, Tabs, Text, Textarea, TextInput, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { core } from '../api/endpoints';
import type { ReviewRow } from '../api/types';
import { hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { CpPicker } from '../components/CpPicker';
import { Certificates, Comparison, ManualValue } from './CpExtras';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';
import { formatValue } from '../format';

function RequestDialog({ cpId, row, onClose }: { cpId: string; row: ReviewRow; onClose: () => void }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState<'WAIVER' | 'EXTENSION'>('WAIVER');
  const [date, setDate] = useState('');
  const [note, setNote] = useState('');
  const save = useMutation({
    mutationFn: () => core.requestDisposition(cpId, row.appliedCovenantId, {
      status, extensionDate: status === 'EXTENSION' ? date : null, note,
    }),
    onSuccess: (updated) => {
      qc.invalidateQueries({ queryKey: ['review', cpId] });
      notifications.show({
        color: 'green',
        message: updated.approvalStatus === 'REQUESTED' ? 'Request sent for approval.' : 'Status saved.',
      });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Modal opened onClose={onClose} title={`Breach: ${row.covenantName}`}>
      <Stack>
        <SegmentedControl value={status} onChange={(v) => setStatus(v as 'WAIVER' | 'EXTENSION')}
          data={[{ value: 'WAIVER', label: 'Waiver' }, { value: 'EXTENSION', label: 'Extension' }]} />
        {status === 'EXTENSION' && (
          <TextInput type="date" label="Extended to" value={date} onChange={(e) => setDate(e.currentTarget.value)}
            required />
        )}
        <Textarea label="Reason" value={note} onChange={(e) => setNote(e.currentTarget.value)} minRows={3} />
        <Group justify="end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending}
            disabled={status === 'EXTENSION' && !date}>Submit</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function DecisionButtons({ cpId, row }: { cpId: string; row: ReviewRow }) {
  const qc = useQueryClient();
  const decide = useMutation({
    mutationFn: (decision: 'APPROVE' | 'REJECT') => core.decideDisposition(cpId, row.appliedCovenantId, decision),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['review', cpId] }),
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Group gap="xs">
      <Button size="xs" color="green" onClick={() => decide.mutate('APPROVE')} loading={decide.isPending}>
        Approve
      </Button>
      <Button size="xs" color="red" variant="light" onClick={() => decide.mutate('REJECT')}
        loading={decide.isPending}>Reject</Button>
    </Group>
  );
}

function ReviewGrid({ cpId }: { cpId: string }) {
  const identity = useIdentity();
  const approver = hasRole(identity, 'APPROVER', 'ADMIN');
  const [editing, setEditing] = useState<ReviewRow | null>(null);
  const { data, isLoading, error } = useQuery({ queryKey: ['review', cpId], queryFn: () => core.review(cpId) });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  return (
    <>
      <Table striped withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Covenant</Table.Th><Table.Th>Rule</Table.Th><Table.Th>Actual</Table.Th>
            <Table.Th>Headroom</Table.Th><Table.Th>Result</Table.Th><Table.Th>Status</Table.Th>
            <Table.Th>Next due</Table.Th><Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(data ?? []).map((r) => {
            const pending = r.approvalStatus === 'REQUESTED';
            const ownRequest = r.requestedBy === identity.user;
            return (
              <Table.Tr key={r.appliedCovenantId}>
                <Table.Td>{r.covenantName}</Table.Td>
                <Table.Td><Text ff="monospace" size="sm">{r.conditionExpression}</Text></Table.Td>
                <Table.Td>{formatValue(r.computedValue)}</Table.Td>
                <Table.Td c={r.headroom !== null && r.headroom < 0 ? 'red' : undefined}>{formatValue(r.headroom)}</Table.Td>
                <Table.Td><StatusBadge value={r.latestResult} /></Table.Td>
                <Table.Td>
                  <Stack gap={2}>
                    <StatusBadge value={r.dispositionStatus} />
                    {r.approvalStatus && r.approvalStatus !== 'APPROVED' && (
                      <Badge size="xs" variant="outline" color={pending ? 'yellow' : 'gray'}>
                        {r.requestedStatus} {r.approvalStatus.toLowerCase()} ({r.requestedBy})
                      </Badge>
                    )}
                    {r.extensionDate && <Text size="xs">until {r.extensionDate}</Text>}
                  </Stack>
                </Table.Td>
                <Table.Td>{r.nextDueDate ?? '—'}{r.overdueByDays > 0 && <Text size="xs" c="red">{r.overdueByDays} d overdue</Text>}</Table.Td>
                <Table.Td>
                  {pending && approver && !ownRequest && <DecisionButtons cpId={cpId} row={r} />}
                  {pending && (!approver || ownRequest) && <Text size="xs" c="dimmed">Awaiting approver</Text>}
                  {!pending && r.dispositionable && (
                    <Button size="xs" variant="light" onClick={() => setEditing(r)}>Waive / extend</Button>
                  )}
                </Table.Td>
              </Table.Tr>
            );
          })}
          {(data ?? []).length === 0 && (
            <Table.Tr><Table.Td colSpan={8}>
              <Text c="dimmed">No covenants on this CP yet. <Anchor component={Link}
                to={`/onboarding/${encodeURIComponent(cpId)}`}>Onboard covenants</Anchor></Text>
            </Table.Td></Table.Tr>
          )}
        </Table.Tbody>
      </Table>
      {editing && <RequestDialog cpId={cpId} row={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function AuditTrail({ cpId }: { cpId: string }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['audit', cpId], queryFn: () => core.statusAudit(cpId) });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  return (
    <Table withTableBorder>
      <Table.Thead>
        <Table.Tr><Table.Th>When</Table.Th><Table.Th>Change</Table.Th><Table.Th>By</Table.Th><Table.Th>Note</Table.Th></Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {(data ?? []).map((a, i) => (
          <Table.Tr key={`${a.changedDate}-${i}`}>
            <Table.Td>{new Date(a.changedDate).toLocaleString()}</Table.Td>
            <Table.Td>{a.oldStatus} → {a.newStatus}</Table.Td>
            <Table.Td>{a.changedBy}</Table.Td>
            <Table.Td>{a.rationale}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

/** FR-24/FR-12: one CP — covenant results, breach handling with maker-checker, and the status trail. */
export function CpWorkspace() {
  const { cpId } = useParams();
  const navigate = useNavigate();
  return (
    <Stack>
      <Group justify="space-between" align="end">
        <Title order={2}>{cpId ? `CP ${cpId}` : 'CP workspace'}</Title>
        <CpPicker key={cpId} initial={cpId} onOpen={(cp) => navigate(`/cp/${encodeURIComponent(cp)}`)} />
      </Group>
      {cpId && (
        <Tabs defaultValue="covenants" keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="covenants">Covenants</Tabs.Tab>
            <Tabs.Tab value="audit">Status history</Tabs.Tab>
            <Tabs.Tab value="compare">Period comparison</Tabs.Tab>
            <Tabs.Tab value="certificates">Certificates</Tabs.Tab>
            <Tabs.Tab value="values">Manual values</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="covenants" pt="md"><ReviewGrid cpId={cpId} /></Tabs.Panel>
          <Tabs.Panel value="audit" pt="md"><AuditTrail cpId={cpId} /></Tabs.Panel>
          <Tabs.Panel value="compare" pt="md"><Comparison cpId={cpId} /></Tabs.Panel>
          <Tabs.Panel value="certificates" pt="md"><Certificates cpId={cpId} /></Tabs.Panel>
          <Tabs.Panel value="values" pt="md"><ManualValue cpId={cpId} /></Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}
