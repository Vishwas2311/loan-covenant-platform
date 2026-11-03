import { Badge, Button, Group, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { notifications } from '@mantine/notifications';
import { errorMessage } from '../api/client';
import { core } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import { ErrorNote, Loading } from '../components/Status';

/** Covenant library (read view). Authoring stays in Core's API (AUTHOR / ADMIN). */
export function Library() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const { data, isLoading, error } = useQuery({
    queryKey: ['covenants', search, page], queryFn: () => core.covenants(search, page),
  });
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Covenant library</Title>
        <Button variant="light" onClick={() => r2.libraryXlsx().catch((e) =>
          notifications.show({ color: 'red', message: errorMessage(e) }))}>Download Excel</Button>
      </Group>
      <TextInput placeholder="Search name or description" aria-label="Search covenants" value={search}
        onChange={(e) => { setSearch(e.currentTarget.value); setPage(0); }} maw={360} />
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && (
        <>
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr><Table.Th>Name</Table.Th><Table.Th>Category</Table.Th><Table.Th>Rule</Table.Th>
                <Table.Th>Type</Table.Th><Table.Th>Frequency</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.content.map((c) => (
                <Table.Tr key={c.id}>
                  <Table.Td>{c.name}<Text size="xs" c="dimmed">{c.description}</Text></Table.Td>
                  <Table.Td>{c.category}</Table.Td>
                  <Table.Td><Text ff="monospace" size="sm">{c.conditionExpression}</Text></Table.Td>
                  <Table.Td>{c.assessmentType}</Table.Td>
                  <Table.Td>{c.defaultFrequency}</Table.Td>
                  <Table.Td>{!c.active && <Badge color="gray">inactive</Badge>}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Group>
            <Button variant="default" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
            <Text size="sm">Page {data.number + 1} · {data.totalElements} covenants</Text>
            <Button variant="default" disabled={data.last} onClick={() => setPage(page + 1)}>Next</Button>
          </Group>
        </>
      )}
    </Stack>
  );
}
