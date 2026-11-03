import {
  Badge, Button, Group, Modal, SegmentedControl, Select, Stack, Table, TagsInput, Text, Textarea, TextInput, Title,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { notifications } from '@mantine/notifications';
import { errorMessage } from '../api/client';
import { core } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import type { Frequency, NewCovenant } from '../api/types';
import { hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { ErrorNote, Loading } from '../components/Status';

const FREQUENCIES: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'];

/** Adds one covenant to the shared library (AUTHOR / ADMIN; Core checks the role and the rule). */
function NewCovenantForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const [draft, setDraft] = useState<NewCovenant>({
    name: '', category: 'FINANCIAL', description: '', conditionExpression: '',
    assessmentType: 'AUTO', defaultFrequency: 'QUARTERLY', businessSegments: [],
  });
  const set = (patch: Partial<NewCovenant>) => setDraft((d) => ({ ...d, ...patch }));
  const manual = draft.assessmentType === 'MANUAL';
  const measures = useQuery({ queryKey: ['measures'], queryFn: core.measures, enabled: !manual });
  const save = useMutation({
    mutationFn: () => core.createCovenant({
      ...draft, name: draft.name.trim(), description: draft.description.trim(),
      conditionExpression: draft.conditionExpression.trim(),
    }),
    onSuccess: (c) => {
      notifications.show({ color: 'green', message: `${c.name} added to the library.` });
      qc.invalidateQueries({ queryKey: ['covenants'] });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const ready = draft.name.trim() && draft.description.trim() && draft.conditionExpression.trim();

  return (
    <Modal opened onClose={onClose} title="New covenant" size="lg">
      <Stack>
        <TextInput label="Name" required value={draft.name} onChange={(e) => set({ name: e.currentTarget.value })}
          placeholder="Minimum Current Ratio" />
        <SegmentedControl value={draft.category} data={[
          { value: 'FINANCIAL', label: 'Financial' }, { value: 'NON_FINANCIAL', label: 'Non-financial' }]}
          onChange={(v) => {
            // A non-financial covenant has no figures to test, so a person checks it.
            set({ category: v as NewCovenant['category'], assessmentType: v === 'NON_FINANCIAL' ? 'MANUAL' : 'AUTO' });
          }} />
        <Textarea label="Description" required minRows={2} value={draft.description}
          onChange={(e) => set({ description: e.currentTarget.value })}
          placeholder="What the borrower must do, in plain words" />
        <SegmentedControl value={draft.assessmentType} data={[
          { value: 'AUTO', label: 'Checked by the app' }, { value: 'MANUAL', label: 'Checked by a person' }]}
          onChange={(v) => set({ assessmentType: v as NewCovenant['assessmentType'] })} />
        <TextInput label={manual ? 'What to check' : 'Rule'} required value={draft.conditionExpression}
          onChange={(e) => set({ conditionExpression: e.currentTarget.value })}
          styles={manual ? undefined : { input: { fontFamily: 'monospace' } }}
          placeholder={manual ? 'Quarterly MIS submitted within 45 days' : 'CURRENT_RATIO >= 1.20'}
          description={manual ? 'Plain words for the person who checks it.'
            : 'A measure, a comparison and a number, e.g. DSCR >= 1.30 or DEBT_EBITDA <= 3.5.'} />
        {!manual && measures.data && (
          <Text size="xs" c="dimmed">
            Measures: {measures.data.filter((m) => m.active).map((m) => m.code).sort().join(', ')}
          </Text>
        )}
        <Select label="How often" data={FREQUENCIES} value={draft.defaultFrequency} allowDeselect={false}
          onChange={(v) => v && set({ defaultFrequency: v as Frequency })} />
        <TagsInput label="Business segments" value={draft.businessSegments}
          onChange={(v) => set({ businessSegments: v.map((s) => s.toUpperCase()) })}
          placeholder="Leave empty for all, or type e.g. CORPORATE and press Enter" />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!ready}>Add to library</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Covenant library. Everyone can read it; authors and admins can add to it. */
export function Library() {
  const identity = useIdentity();
  const canAuthor = hasRole(identity, 'AUTHOR', 'ADMIN');
  const [creating, setCreating] = useState(false);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const { data, isLoading, error } = useQuery({
    queryKey: ['covenants', search, page], queryFn: () => core.covenants(search, page),
  });
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Covenant library</Title>
        <Group>
          {canAuthor && <Button onClick={() => setCreating(true)}>New covenant</Button>}
          <Button variant="light" onClick={() => r2.libraryXlsx().catch((e) =>
            notifications.show({ color: 'red', message: errorMessage(e) }))}>Download Excel</Button>
        </Group>
      </Group>
      {creating && <NewCovenantForm onClose={() => setCreating(false)} />}
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
