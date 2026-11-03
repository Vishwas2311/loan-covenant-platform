import {
  Anchor, Badge, Button, Group, Modal, SegmentedControl, Stack, Table, Text, Textarea, TextInput, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { r4 } from '../api/endpoints4';
import type { TaskKind, WorkTask } from '../api/types4';
import { ErrorNote, Loading } from '../components/Status';

const KIND: Record<TaskKind, { label: string; color: string }> = {
  APPROVAL: { label: 'Approve', color: 'yellow' },
  BREACH: { label: 'Breach', color: 'red' },
  OVERDUE: { label: 'Overdue check', color: 'red' },
  REVIEW: { label: 'Record result', color: 'yellow' },
  CERTIFICATE: { label: 'Certificate', color: 'orange' },
};

function EditTask({ task, onClose }: { task: WorkTask; onClose: () => void }) {
  const qc = useQueryClient();
  const [assignee, setAssignee] = useState(task.assignee);
  const [due, setDue] = useState(task.dueDate);
  const [note, setNote] = useState(task.note ?? '');
  const save = useMutation({
    mutationFn: (done: boolean) => r4.updateTask(task.id, {
      assignee: assignee.trim(), dueDate: due, note, ...(done ? { done: true } : {}),
    }),
    onSuccess: (t) => {
      notifications.show({ color: 'green', message: t.status === 'DONE' ? 'Task done.' : 'Task saved.' });
      qc.invalidateQueries({ queryKey: ['tasks'] });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Modal opened onClose={onClose} title={task.title}>
      <Stack>
        <TextInput label="Assigned to" value={assignee} onChange={(e) => setAssignee(e.currentTarget.value)}
          description="The user id of the person who should do it, e.g. rm-anil" />
        <TextInput type="date" label="Due by" value={due} onChange={(e) => setDue(e.currentTarget.value)} />
        <Textarea label="Note" value={note} onChange={(e) => setNote(e.currentTarget.value)} minRows={3}
          placeholder="What has been done so far" />
        <Group justify="space-between">
          <Button color="green" variant="light" loading={save.isPending} onClick={() => save.mutate(true)}>Mark done</Button>
          <Group>
            <Button variant="default" onClick={onClose}>Cancel</Button>
            <Button loading={save.isPending} disabled={!assignee.trim() || !due} onClick={() => save.mutate(false)}>Save</Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}

/** C4: everything waiting on the signed-in user (or on everyone, for their CPs), soonest due first. */
export function MyTasks() {
  const [scope, setScope] = useState<'mine' | 'all' | 'closed'>('mine');
  const [editing, setEditing] = useState<WorkTask | null>(null);
  const { data, isLoading, error } = useQuery({
    queryKey: ['tasks', scope],
    queryFn: () => (scope === 'closed' ? r4.tasks('all', false) : r4.tasks(scope)),
  });
  const rows = scope === 'closed' ? (data ?? []).filter((t) => t.status !== 'OPEN') : (data ?? []);
  const today = new Date().toISOString().slice(0, 10);
  return (
    <Stack>
      <Title order={2}>My tasks</Title>
      <Text c="dimmed" size="sm">
        The app adds a task whenever something needs a person, and closes it by itself when it is sorted out.
        A task still open after its due date is escalated: the CP&apos;s approver sees it too.
      </Text>
      <SegmentedControl w="fit-content" value={scope} onChange={(v) => setScope(v as typeof scope)} data={[
        { value: 'mine', label: 'Mine' }, { value: 'all', label: 'All on my CPs' }, { value: 'closed', label: 'Closed' }]} />
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && (
        <Table withTableBorder striped>
          <Table.Thead>
            <Table.Tr><Table.Th>Due</Table.Th><Table.Th>What</Table.Th><Table.Th>CP</Table.Th>
              <Table.Th>Assigned to</Table.Th><Table.Th>Note</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((t) => (
              <Table.Tr key={t.id}>
                <Table.Td>
                  <Text size="sm" c={t.status === 'OPEN' && t.dueDate < today ? 'red' : undefined}>{t.dueDate}</Text>
                  {t.escalated && <Badge size="xs" color="red">escalated</Badge>}
                </Table.Td>
                <Table.Td>
                  <Badge size="xs" variant="light" color={KIND[t.kind].color}>{KIND[t.kind].label}</Badge>
                  <Text size="sm">{t.title}</Text>
                </Table.Td>
                <Table.Td><Anchor component={Link} to={`/cp/${encodeURIComponent(t.cpId)}`}>{t.cpId}</Anchor></Table.Td>
                <Table.Td>{t.assignee}</Table.Td>
                <Table.Td><Text size="xs">{t.note}</Text></Table.Td>
                <Table.Td>
                  {t.status === 'OPEN'
                    ? <Button size="xs" variant="light" onClick={() => setEditing(t)}>Update</Button>
                    : <Text size="xs" c="dimmed">{t.status === 'DONE' ? `done by ${t.closedBy}` : 'sorted out'}</Text>}
                </Table.Td>
              </Table.Tr>
            ))}
            {rows.length === 0 && (
              <Table.Tr><Table.Td colSpan={6}><Text c="dimmed">Nothing here.</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      )}
      {editing && <EditTask task={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}
