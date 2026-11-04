import {
  Anchor, Badge, Button, Checkbox, Group, Modal, SegmentedControl, Select, Stack, Table, Text, Textarea, TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fragment, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { r4 } from '../api/endpoints4';
import type { TaskKind, WorkTask } from '../api/types4';
import { useMyCps } from '../components/CpPicker';
import { fmtDate } from '../format';
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
  const all = scope === 'closed' ? (data ?? []).filter((t) => t.status !== 'OPEN') : (data ?? []);
  const today = new Date().toISOString().slice(0, 10);
  const cards = useMyCps().data;
  const borrower = useMemo(() => new Map((cards ?? []).map((c) => [c.cpId, c.name])), [cards]);
  const [q, setQ] = useState('');
  const [kind, setKind] = useState<TaskKind | null>(null);
  const [byCp, setByCp] = useState(false);
  const needle = q.trim().toLowerCase();
  const rows = all.filter((t) => (!kind || t.kind === kind) && (!needle
    || [t.title, t.cpId, borrower.get(t.cpId) ?? '', t.assignee].some((v) => v.toLowerCase().includes(needle))));
  if (byCp) {
    rows.sort((a, b) => a.cpId.localeCompare(b.cpId) || a.dueDate.localeCompare(b.dueDate));
  }
  const late = all.filter((t) => t.status === 'OPEN' && t.dueDate < today).length;
  const kinds = (Object.keys(KIND) as TaskKind[]).filter((k) => all.some((t) => t.kind === k));
  return (
    <Stack>
      <Title order={1}>My tasks</Title>
      <Text c="dimmed" size="sm">
        The app adds a task whenever something needs a person, and closes it by itself when it is sorted out.
        A task still open after its due date is escalated: the CP&apos;s approver sees it too.
      </Text>
      <Group>
        <SegmentedControl w="fit-content" value={scope} onChange={(v) => setScope(v as typeof scope)} data={[
          { value: 'mine', label: 'Mine' }, { value: 'all', label: 'All on my CPs' }, { value: 'closed', label: 'Closed' }]} />
        <TextInput placeholder="Search task, CP or borrower" aria-label="Search tasks" value={q} w={260}
          onChange={(e) => setQ(e.currentTarget.value)} />
        <Select placeholder="Any kind" aria-label="Kind of task" clearable w={180} value={kind}
          onChange={(v) => setKind(v as TaskKind | null)}
          data={kinds.map((k) => ({ value: k, label: `${KIND[k].label} (${all.filter((t) => t.kind === k).length})` }))} />
        <Checkbox label="Group by CP" checked={byCp} onChange={(e) => setByCp(e.currentTarget.checked)} />
        {scope !== 'closed' && late > 0 && <Badge color="red" variant="light">{late} past due</Badge>}
      </Group>
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && (
        <Table.ScrollContainer minWidth={760}>
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr><Table.Th>Due</Table.Th><Table.Th>What</Table.Th><Table.Th>CP</Table.Th>
                <Table.Th>Assigned to</Table.Th><Table.Th>Note</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((t, i) => (
                <Fragment key={t.id}>
                {byCp && (i === 0 || rows[i - 1].cpId !== t.cpId) && (
                  <Table.Tr bg="var(--mantine-color-default-hover)">
                    <Table.Td colSpan={6}>
                      <Text fw={600} size="sm">{borrower.get(t.cpId) ?? t.cpId} · {t.cpId}
                        {' '}<Text span c="dimmed" size="xs">{rows.filter((x) => x.cpId === t.cpId).length} task(s)</Text></Text>
                    </Table.Td>
                  </Table.Tr>
                )}
                <Table.Tr>
                  <Table.Td>
                    <Text size="sm" style={{ whiteSpace: 'nowrap' }} c={t.status === 'OPEN' && t.dueDate < today ? 'var(--text-bad)' : undefined}>{fmtDate(t.dueDate)}</Text>
                    {t.escalated && <Badge size="sm" color="red" mt={2}>escalated</Badge>}
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" fw={500}>{t.title}</Text>
                    <Badge size="sm" mt={2} color={KIND[t.kind].color}>{KIND[t.kind].label}</Badge>
                  </Table.Td>
                  <Table.Td>
                    <Anchor component={Link} to={`/cp/${encodeURIComponent(t.cpId)}`} size="sm" fw={550}>{t.cpId}</Anchor>
                    {borrower.get(t.cpId) && <Text size="xs" c="dimmed">{borrower.get(t.cpId)}</Text>}
                  </Table.Td>
                  <Table.Td>{t.assignee}</Table.Td>
                  <Table.Td><Text size="xs">{t.note}</Text></Table.Td>
                  <Table.Td>
                    {t.status === 'OPEN'
                      ? <Button size="xs" variant="light" onClick={() => setEditing(t)}>Update</Button>
                      : <Text size="xs" c="dimmed">{t.status === 'DONE' ? `done by ${t.closedBy}` : 'sorted out'}</Text>}
                  </Table.Td>
                </Table.Tr>
                </Fragment>
              ))}
              {rows.length === 0 && (
                <Table.Tr><Table.Td colSpan={6}><Text c="dimmed">Nothing here.</Text></Table.Td></Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {editing && <EditTask task={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}
