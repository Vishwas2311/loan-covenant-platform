import {
  ActionIcon, Badge, Button, Group, Modal, PasswordInput, SegmentedControl, Stack, Table, Text, Textarea, TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { r3 } from '../api/endpoints3';
import type { DataSource, DataSourceTable, DataSourceWrite, TableWrite } from '../api/types3';
import { ErrorNote, Loading } from '../components/Status';

const MASK = '********';

/** The connection settings each type expects; any other key can still be added below. */
const SUGGESTED: Record<DataSourceWrite['type'], string[]> = {
  MSSQL: ['jdbcUrl', 'username', 'password'],
  REST: ['baseUrl', 'valuesPath'],
};

function SourceForm({ existing, onClose }: { existing?: DataSource; onClose: () => void }) {
  const qc = useQueryClient();
  const [type, setType] = useState<DataSourceWrite['type']>(existing?.type ?? 'MSSQL');
  const [name, setName] = useState(existing?.name ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [rows, setRows] = useState<[string, string][]>(() => {
    const config = existing?.connectionConfig ?? {};
    const keys = [...new Set([...SUGGESTED[existing?.type ?? 'MSSQL'], ...Object.keys(config)])];
    return keys.map((k) => [k, config[k] ?? '']);
  });
  const setRow = (i: number, key: string, value: string) =>
    setRows((r) => r.map((row, j) => (j === i ? [key, value] : row)));
  const save = useMutation({
    mutationFn: () => {
      const connectionConfig = Object.fromEntries(rows.filter(([k, v]) => k.trim() && v !== '')
        .map(([k, v]) => [k.trim(), v.trim()]));
      const body: DataSourceWrite = { name: name.trim(), type, description: description.trim(), connectionConfig };
      return existing ? r3.updateDataSource(existing.id, body) : r3.createDataSource(body);
    },
    onSuccess: (s) => {
      notifications.show({ color: 'green', message: `${s.name} saved. Use Test to check the connection.` });
      qc.invalidateQueries({ queryKey: ['data-sources'] });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Modal opened onClose={onClose} title={existing ? `Edit ${existing.name}` : 'New data source'} size="lg">
      <Stack>
        <TextInput label="Name" required value={name} onChange={(e) => setName(e.currentTarget.value)}
          placeholder="Loan system figures" />
        <SegmentedControl value={type} disabled={!!existing} data={[
          { value: 'MSSQL', label: 'SQL Server database' }, { value: 'REST', label: 'Web service (REST)' }]}
          onChange={(v) => {
            const next = v as DataSourceWrite['type'];
            setType(next);
            setRows(SUGGESTED[next].map((k) => [k, '']));
          }} />
        <Textarea label="Description" value={description} minRows={2}
          onChange={(e) => setDescription(e.currentTarget.value)} />
        <Text fw={600} size="sm">Connection settings</Text>
        {rows.map(([k, v], i) => (
          <Group key={i} align="end" wrap="nowrap">
            <TextInput aria-label="Setting name" value={k} w={170} onChange={(e) => setRow(i, e.currentTarget.value, v)} />
            {k === 'password'
              ? <PasswordInput aria-label="password" value={v} style={{ flex: 1 }}
                  description={v === MASK ? 'Stored password kept unless you type a new one' : undefined}
                  onChange={(e) => setRow(i, k, e.currentTarget.value)} />
              : <TextInput aria-label={k || 'Setting value'} value={v} style={{ flex: 1 }}
                  placeholder={k === 'jdbcUrl' ? 'jdbc:sqlserver://host:1433;databaseName=...' : undefined}
                  onChange={(e) => setRow(i, k, e.currentTarget.value)} />}
            <ActionIcon variant="subtle" color="red" aria-label={`Remove ${k}`}
              onClick={() => setRows((r) => r.filter((_, j) => j !== i))}><IconTrash size={16} /></ActionIcon>
          </Group>
        ))}
        <Button variant="subtle" w="fit-content" onClick={() => setRows((r) => [...r, ['', '']])}>Add setting</Button>
        <Text size="xs" c="dimmed">
          Database hosts must be on the approved list set by the platform team. In production use secretRef
          (a vault reference) instead of a plain password.
        </Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!name.trim()}>Save</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** A8: the tables of one source: where a CP's figures sit (one row per CP and as-of date). */
function TableForm({ source, existing, onClose }: { source: DataSource; existing?: DataSourceTable; onClose: () => void }) {
  const qc = useQueryClient();
  const [t, setT] = useState<TableWrite>({
    name: existing?.name ?? '', schemaName: existing?.schemaName ?? '', tableName: existing?.tableName ?? '',
    entityKeyColumn: existing?.entityKeyColumn ?? 'cp_id', asOfColumn: existing?.asOfColumn ?? 'as_of_date',
    layout: 'COLUMNAR', codeColumn: '', valueColumn: '', entityKeyLevel: 'CP_ID',
  });
  const set = (patch: Partial<TableWrite>) => setT((x) => ({ ...x, ...patch }));
  const ident = /^[A-Za-z_][A-Za-z0-9_]*$/;
  const ready = t.name.trim() && ident.test(t.tableName) && ident.test(t.entityKeyColumn) && ident.test(t.asOfColumn)
    && (t.schemaName === '' || ident.test(t.schemaName));
  const save = useMutation({
    mutationFn: () => r3.saveTable(source.id, { ...t, name: t.name.trim() }, existing?.id),
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Table saved. Measures can now point at it.' });
      qc.invalidateQueries({ queryKey: ['tables', source.id] });
      qc.invalidateQueries({ queryKey: ['all-tables'] });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Modal opened onClose={onClose} title={existing ? `Edit table ${existing.tableName}` : `New table in ${source.name}`}>
      <Stack>
        <TextInput label="Name" required value={t.name} onChange={(e) => set({ name: e.currentTarget.value })}
          placeholder="Quarterly ratios" />
        <Group grow>
          <TextInput label="Schema" value={t.schemaName} onChange={(e) => set({ schemaName: e.currentTarget.value })}
            placeholder="dbo" />
          <TextInput label="Table" required value={t.tableName} onChange={(e) => set({ tableName: e.currentTarget.value })}
            placeholder="cp_ratios" />
        </Group>
        <Group grow>
          <TextInput label="CP id column" required value={t.entityKeyColumn}
            onChange={(e) => set({ entityKeyColumn: e.currentTarget.value })} />
          <TextInput label="As-of date column" required value={t.asOfColumn}
            onChange={(e) => set({ asOfColumn: e.currentTarget.value })} />
        </Group>
        <Text size="xs" c="dimmed">Names must be plain SQL identifiers (letters, digits and _). Each other column holds one figure.</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!ready}>Save table</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function Tables({ source }: { source: DataSource }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<DataSourceTable | 'new' | null>(null);
  const { data, isLoading, error } = useQuery({ queryKey: ['tables', source.id], queryFn: () => r3.tables(source.id) });
  const off = useMutation({
    mutationFn: (id: string) => r3.deactivateTable(source.id, id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tables', source.id] }),
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const rows = Array.isArray(data) ? data : [];
  return (
    <Stack gap={2} mt={4}>
      {rows.length === 0 && <Text size="xs" c="dimmed">Tables: none yet</Text>}
      {rows.map((t) => (
        <Group key={t.id} gap="xs">
          <Text size="xs">{`${t.schemaName ? `${t.schemaName}.` : ''}${t.tableName}${t.active ? '' : ' (off)'}`}</Text>
          <Button size="compact-xs" variant="subtle" onClick={() => setEditing(t)}>Edit</Button>
          {t.active && (
            <Button size="compact-xs" variant="subtle" color="gray"
              onClick={() => window.confirm(`Switch off table ${t.tableName}? Measures on it stop being read.`) && off.mutate(t.id)}>
              Switch off</Button>
          )}
        </Group>
      ))}
      <Button size="compact-xs" variant="subtle" w="fit-content" onClick={() => setEditing('new')}>Add table</Button>
      {editing && <TableForm source={source} existing={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

/** A8: where Core reads figures from (Admin). Passwords are never shown back. */
export function DataSources() {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<DataSource | 'new' | null>(null);
  const { data, isLoading, error } = useQuery({ queryKey: ['data-sources'], queryFn: r3.dataSources });
  const test = useMutation({
    mutationFn: (s: DataSource) => r3.testDataSource(s.id),
    onSuccess: (r) => notifications.show({ color: r.reachable ? 'green' : 'red',
      message: r.reachable ? `Connected: ${r.columnCount} columns found.` : `Not reachable: ${r.message}` }),
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const switchOff = useMutation({
    mutationFn: (id: string) => r3.deactivateDataSource(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['data-sources'] }),
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={1}>Data sources</Title>
        <Button onClick={() => setEditing('new')}>New data source</Button>
      </Group>
      <Text c="dimmed" size="sm">
        The databases and services the app reads covenant figures from. Each measure points at a table here.
      </Text>
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && (
        <Table withTableBorder>
          <Table.Thead><Table.Tr><Table.Th>Name</Table.Th><Table.Th>Type</Table.Th><Table.Th /></Table.Tr></Table.Thead>
          <Table.Tbody>
            {data.map((s) => (
              <Table.Tr key={s.id}>
                <Table.Td>{s.name}<Text size="xs" c="dimmed">{s.description}</Text><Tables source={s} /></Table.Td>
                <Table.Td>{s.type === 'MSSQL' ? 'SQL Server' : 'REST'}</Table.Td>
                <Table.Td>
                  <Group gap="xs" wrap="nowrap">
                    {!s.active && <Badge color="gray">inactive</Badge>}
                    <Button size="xs" variant="light" onClick={() => test.mutate(s)}
                      loading={test.isPending && test.variables?.id === s.id}>Test</Button>
                    <Button size="xs" variant="subtle" onClick={() => setEditing(s)}>Edit</Button>
                    {s.active && (
                      <Button size="xs" variant="subtle" color="gray"
                        onClick={() => window.confirm(`Switch off ${s.name}? Its figures stop being read.`)
                          && switchOff.mutate(s.id)}>Switch off</Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing && <SourceForm existing={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}
