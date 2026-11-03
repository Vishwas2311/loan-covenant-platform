import { Alert, Anchor, Badge, Button, Stack, Table, Text } from '@mantine/core';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { core } from '../api/endpoints';
import { r3 } from '../api/endpoints3';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';

/** A11: the AI service's suggested status for each covenant. Advice only; a person decides. */
export function AiSuggestions({ cpId }: { cpId: string }) {
  const review = useQuery({ queryKey: ['review', cpId], queryFn: () => core.review(cpId) });
  const ask = useMutation({ mutationFn: () => r3.aiSuggestions(cpId) });
  const names = new Map((review.data ?? []).map((r) => [r.appliedCovenantId, r.covenantName]));
  return (
    <Stack>
      <Alert color="blue" variant="light">
        The AI reads this CP&apos;s covenant results and uploaded files and suggests a status for each covenant.
        It is advice only: nothing changes until a person acts.
      </Alert>
      <Button w="fit-content" onClick={() => ask.mutate()} loading={ask.isPending}>Get AI suggestions</Button>
      {ask.error && <ErrorNote error={ask.error} />}
      {ask.data && ask.data.length === 0 && (
        <Text c="dimmed">No suggestions. The AI service may be unavailable, or it has nothing to add.</Text>
      )}
      {ask.data && ask.data.length > 0 && (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr><Table.Th>Covenant</Table.Th><Table.Th>Suggested status</Table.Th><Table.Th>Why</Table.Th>
              <Table.Th>Confidence</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {ask.data.map((s) => (
              <Table.Tr key={s.appliedCovenantId}>
                <Table.Td>{names.get(s.appliedCovenantId) ?? s.appliedCovenantId}</Table.Td>
                <Table.Td><StatusBadge value={s.recommendedStatus} /></Table.Td>
                <Table.Td>{s.rationale}</Table.Td>
                <Table.Td>{s.confidence === null ? '—' : `${Math.round(s.confidence * 100)}%`}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

/** A13: this loan's renewals: each yearly cycle is its own CP. */
export function Cycles({ cpId }: { cpId: string }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['cycles', cpId], queryFn: () => r3.cycles(cpId) });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  if ((data ?? []).length <= 1) {
    return <Text c="dimmed">This loan has no earlier or later cycles.</Text>;
  }
  return (
    <Table withTableBorder maw={480}>
      <Table.Thead><Table.Tr><Table.Th>Cycle</Table.Th><Table.Th>CP</Table.Th><Table.Th /></Table.Tr></Table.Thead>
      <Table.Tbody>
        {(data ?? []).map((c) => (
          <Table.Tr key={c.cpId}>
            <Table.Td>{c.cycleNumber}</Table.Td>
            <Table.Td>
              {c.cpId === cpId ? c.cpId
                : <Anchor component={Link} to={`/cp/${encodeURIComponent(c.cpId)}`}>{c.cpId}</Anchor>}
            </Table.Td>
            <Table.Td>{c.current && <Badge size="sm" variant="light">current</Badge>}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
