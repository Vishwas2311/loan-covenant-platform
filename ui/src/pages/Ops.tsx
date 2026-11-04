import { Button, Group, Select, Stack, Table, Text, Textarea, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { Job } from '../api/types';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';

interface AskAnswer { answer: string; citations: string[]; method: string; advisory: string }

export const ops = {
  jobs: (status: string | null) => api<Job[]>(`/dps/v1/jobs${status ? `?status=${status}` : ''}`),
  retry: (id: string) => api<Job>(`/dps/v1/jobs/${encodeURIComponent(id)}/retry`, { method: 'POST' }),
  ask: (question: string) => api<AskAnswer>('/dps/v1/ask', { method: 'POST', json: { question } }),
};

/** FR-17: document jobs for support: filter by status, read step errors, retry failed ones (audited in DPS). */
export function Jobs() {
  const qc = useQueryClient();
  const [status, setStatus] = useState<string | null>('FAILED');
  const jobs = useQuery({ queryKey: ['jobs', status], queryFn: () => ops.jobs(status) });
  const retry = useMutation({
    mutationFn: ops.retry,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['jobs'] }),
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={1}>Document jobs</Title>
        <Select aria-label="Job status" clearable placeholder="All" value={status} onChange={setStatus}
          data={['QUEUED', 'RUNNING', 'NEEDS_REVIEW', 'DONE', 'FAILED']} w={180} />
      </Group>
      {jobs.isLoading && <Loading />}
      {jobs.error && <ErrorNote error={jobs.error} />}
      {jobs.data && (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr><Table.Th>Job</Table.Th><Table.Th>Flow</Table.Th><Table.Th>Status</Table.Th>
              <Table.Th>Pool</Table.Th><Table.Th>Attempts</Table.Th><Table.Th>Error</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {jobs.data.map((j) => (
              <Table.Tr key={j.id}>
                <Table.Td><Text size="xs" ff="monospace">{j.id.slice(0, 8)}</Text></Table.Td>
                <Table.Td>{j.flow}</Table.Td>
                <Table.Td><StatusBadge value={j.status} /></Table.Td>
                <Table.Td>{j.pool}</Table.Td>
                <Table.Td>{j.attempts}</Table.Td>
                <Table.Td><Text size="xs" c="var(--text-bad)">{j.error}</Text></Table.Td>
                <Table.Td>{j.status === 'FAILED' && (
                  <Button size="xs" onClick={() => retry.mutate(j.id)} loading={retry.isPending}>Retry</Button>)}</Table.Td>
              </Table.Tr>
            ))}
            {jobs.data.length === 0 && (
              <Table.Tr><Table.Td colSpan={7}><Text c="dimmed">No jobs.</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

/** FR-27: questions about the caller's own portfolio; the answer lists the records it used. */
export function AskAi() {
  const [question, setQuestion] = useState('');
  const ask = useMutation({ mutationFn: () => ops.ask(question) });
  return (
    <Stack>
      <Title order={4}>Ask about your portfolio</Title>
      <Group align="end">
        <Textarea aria-label="Question" placeholder="Which breaches should I escalate?" value={question}
          onChange={(e) => setQuestion(e.currentTarget.value)} autosize minRows={1} style={{ flex: 1 }} />
        <Button onClick={() => ask.mutate()} disabled={!question.trim()} loading={ask.isPending}>Ask</Button>
      </Group>
      {ask.error && <ErrorNote error={ask.error} />}
      {ask.data && (
        <Stack gap={4}>
          <Text>{ask.data.answer}</Text>
          {ask.data.citations.length > 0 && <Text size="xs" c="dimmed">Sources: {ask.data.citations.join(', ')}</Text>}
          <Text size="xs" c="dimmed">{ask.data.advisory}</Text>
        </Stack>
      )}
    </Stack>
  );
}
