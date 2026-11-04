import { Badge, Button, Group, Modal, Select, Stack, Table, Text, Textarea, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { r3 } from '../api/endpoints3';
import type { Aggregation, MeasureDetail, MeasureWrite } from '../api/types3';
import { hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { ErrorNote, Loading } from '../components/Status';

const AGGREGATIONS: { value: Aggregation; label: string }[] = [
  { value: 'LATEST', label: 'Latest value' }, { value: 'CURRENT', label: 'Current value' },
  { value: 'SUM', label: 'Sum' }, { value: 'AVG', label: 'Average' }, { value: 'MIN', label: 'Lowest' },
  { value: 'MAX', label: 'Highest' }, { value: 'COUNT', label: 'Count' },
];

/** Every data-source table, labelled with its source, for the measure form. */
function useAllTables() {
  return useQuery({
    queryKey: ['all-tables'],
    queryFn: async () => {
      const sources = await r3.dataSources();
      const lists = await Promise.all(sources.filter((s) => s.active).map(async (s) =>
        (await r3.tables(s.id)).filter((t) => t.active).map((t) => ({ ...t, sourceName: s.name }))));
      return lists.flat();
    },
  });
}

function MeasureForm({ existing, onClose }: { existing?: MeasureDetail; onClose: () => void }) {
  const qc = useQueryClient();
  const tables = useAllTables();
  const [m, setM] = useState<MeasureWrite>({
    code: existing?.code ?? '', name: existing?.name ?? '', description: existing?.description ?? '',
    dataSourceTableId: existing?.dataSourceTableId ?? null, sourceColumn: existing?.sourceColumn ?? null,
    defaultAggregation: existing?.defaultAggregation ?? 'LATEST', dataType: existing?.dataType ?? 'NUMBER',
    unit: existing?.unit ?? '', sourceCode: existing?.sourceCode ?? null,
  });
  const set = (patch: Partial<MeasureWrite>) => setM((x) => ({ ...x, ...patch }));
  const table = tables.data?.find((t) => t.id === m.dataSourceTableId);
  const columns = useQuery({
    queryKey: ['table-columns', m.dataSourceTableId], enabled: !!table,
    queryFn: () => r3.tableColumns(table!.dataSourceId, table!.id),
  });
  const save = useMutation({
    mutationFn: () => {
      const body = { ...m, code: m.code.trim().toUpperCase(), name: m.name.trim(), unit: m.unit?.trim() || null };
      return existing ? r3.updateMeasure(existing.id, body) : r3.createMeasure(body);
    },
    onSuccess: (saved) => {
      notifications.show({ color: 'green', message: `${saved.code} saved.` });
      qc.invalidateQueries({ queryKey: ['measures'] });
      qc.invalidateQueries({ queryKey: ['measure-list'] });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const ready = /^[A-Za-z_][A-Za-z0-9_]*$/.test(m.code.trim()) && m.name.trim() && m.dataSourceTableId && m.sourceColumn;
  return (
    <Modal opened onClose={onClose} title={existing ? `Edit ${existing.code}` : 'New measure'} size="lg">
      <Stack>
        <TextInput label="Code" required value={m.code} onChange={(e) => set({ code: e.currentTarget.value })}
          placeholder="FIXED_CHARGE_COVER" description="The name used in covenant rules: letters, digits and _." />
        <TextInput label="Name" required value={m.name} onChange={(e) => set({ name: e.currentTarget.value })}
          placeholder="Fixed charge cover" />
        <Textarea label="Description" value={m.description} onChange={(e) => set({ description: e.currentTarget.value })}
          minRows={2} />
        <Select label="Where the figure comes from" required searchable value={m.dataSourceTableId}
          data={(tables.data ?? []).map((t) => ({ value: t.id, label: `${t.sourceName}: ${t.name}` }))}
          onChange={(v) => set({ dataSourceTableId: v, sourceColumn: null })} />
        <Select label="Column" required searchable value={m.sourceColumn} disabled={!table}
          data={(columns.data ?? []).map((c) => ({ value: c.key, label: c.label === c.key ? c.key : `${c.label} (${c.key})` }))}
          onChange={(v) => set({ sourceColumn: v })}
          description={columns.error ? errorMessage(columns.error) : undefined} />
        <Group grow>
          <Select label="When there are several values" data={AGGREGATIONS} value={m.defaultAggregation}
            allowDeselect={false} onChange={(v) => v && set({ defaultAggregation: v as Aggregation })} />
          <Select label="Type" data={['NUMBER', 'STRING', 'DATE']} value={m.dataType} allowDeselect={false}
            onChange={(v) => v && set({ dataType: v as MeasureWrite['dataType'] })} />
          <TextInput label="Unit" value={m.unit ?? ''} onChange={(e) => set({ unit: e.currentTarget.value })}
            placeholder="x, %, INR_CR" />
        </Group>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!ready}>Save measure</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** A7: the figures covenant rules can use. Authors and admins add and change them. */
export function Measures() {
  const identity = useIdentity();
  const canAuthor = hasRole(identity, 'AUTHOR', 'ADMIN');
  const qc = useQueryClient();
  const [editing, setEditing] = useState<MeasureDetail | 'new' | null>(null);
  const [filter, setFilter] = useState('');
  const { data, isLoading, error } = useQuery({ queryKey: ['measure-list'], queryFn: r3.measures });
  const switchOff = useMutation({
    mutationFn: (id: string) => r3.deactivateMeasure(id),
    onSuccess: (m) => {
      notifications.show({ color: 'green', message: `${m.code} switched off.` });
      qc.invalidateQueries({ queryKey: ['measure-list'] });
      qc.invalidateQueries({ queryKey: ['measures'] });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const q = filter.trim().toLowerCase();
  const rows = (data ?? []).filter((m) => !q || m.code.toLowerCase().includes(q) || m.name.toLowerCase().includes(q))
    .sort((a, b) => a.code.localeCompare(b.code));
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={1}>Measures</Title>
        {canAuthor && <Button onClick={() => setEditing('new')}>New measure</Button>}
      </Group>
      <Text c="dimmed" size="sm">
        A measure is one figure a covenant rule can use, such as DSCR or revenue, and says where the app reads it from.
      </Text>
      <TextInput placeholder="Search code or name" aria-label="Search measures" value={filter} maw={360}
        onChange={(e) => setFilter(e.currentTarget.value)} />
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && (
        <Table withTableBorder striped>
          <Table.Thead>
            <Table.Tr><Table.Th>Code</Table.Th><Table.Th>Name</Table.Th><Table.Th>Source</Table.Th>
              <Table.Th>Column</Table.Th><Table.Th>Unit</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((m) => (
              <Table.Tr key={m.id}>
                <Table.Td><Text ff="monospace" size="sm">{m.code}</Text></Table.Td>
                <Table.Td>{m.name}<Text size="xs" c="dimmed">{m.description}</Text></Table.Td>
                <Table.Td>{m.tableName ?? '—'}</Table.Td>
                <Table.Td>{m.sourceColumn ?? '—'}</Table.Td>
                <Table.Td>{m.unit ?? ''}</Table.Td>
                <Table.Td>
                  <Group gap="xs" wrap="nowrap">
                    {!m.active && <Badge color="gray">inactive</Badge>}
                    {canAuthor && <Button size="xs" variant="subtle" onClick={() => setEditing(m)}>Edit</Button>}
                    {canAuthor && m.active && (
                      <Button size="xs" variant="subtle" color="red" loading={switchOff.isPending}
                        onClick={() => window.confirm(`Switch off ${m.code}? New covenant rules can no longer use it.`)
                          && switchOff.mutate(m.id)}>Switch off</Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing && <MeasureForm existing={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}
