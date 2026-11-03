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
import type { DataSource, DataSourceWrite } from '../api/types3';
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

function Tables({ source }: { source: DataSource }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['tables', source.id], queryFn: () => r3.tables(source.id) });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  return (
    <Text size="xs" c="dimmed">
      Tables: {(data ?? []).length === 0 ? 'none yet'
        : (data ?? []).map((t) => `${t.schemaName ? `${t.schemaName}.` : ''}${t.tableName}${t.active ? '' : ' (off)'}`)
          .join(', ')}
    </Text>
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
        <Title order={2}>Data sources</Title>
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
                      <Button size="xs" variant="subtle" color="red"
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
