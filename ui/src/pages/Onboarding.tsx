import {
  Alert, Badge, Button, Checkbox, Group, Select, Stack, Table, Text, TextInput, Title, Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { core } from '../api/endpoints';
import { r3 } from '../api/endpoints3';
import type { Frequency, OnboardingRow } from '../api/types';
import { hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { CpPicker } from '../components/CpPicker';
import { ErrorNote, Loading } from '../components/Status';

const FREQUENCIES: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'];
const SOURCE_COLOR: Record<OnboardingRow['source'], string> = {
  RECOMMENDED: 'gray', ML: 'grape', EXTRACTED: 'teal', EXTRACTED_CUSTOM: 'cyan',
};

interface Draft {
  selected: boolean;
  frequency: Frequency;
  expression: string;
}

function rowKey(r: OnboardingRow, i: number): string {
  return r.covenantId ?? `custom-${i}-${r.name}`;
}

export function initialDraft(r: OnboardingRow): Draft {
  return {
    selected: !r.alreadyApplied && r.source !== 'RECOMMENDED',
    frequency: r.defaultFrequency === 'CUSTOM' ? 'QUARTERLY' : r.defaultFrequency,
    expression: r.conditionOverride ?? '',
  };
}

/**
 * Where the threshold came from, as stored on the applied covenant: EXTRACTED or ML when the RM kept
 * the proposed rule, USER when they edited it, none (master) when there is no override.
 */
export function thresholdSourceFor(r: OnboardingRow, expression: string): string | null {
  const trimmed = expression.trim();
  if (!trimmed) {
    return null;
  }
  if (trimmed === (r.conditionOverride ?? '').trim()) {
    return r.thresholdSource === 'MASTER' ? null : r.thresholdSource;
  }
  return 'USER';
}

/** FR-06 onboarding: master, ML and loan-agreement proposals in one grid; the RM confirms what applies. */
export function Onboarding() {
  const { cpId } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({
    queryKey: ['onboarding', cpId], queryFn: () => core.onboarding(cpId!), enabled: !!cpId,
  });
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const canApply = hasRole(useIdentity(), 'RM', 'APPROVER', 'ADMIN');
  useEffect(() => {
    const next: Record<string, Draft> = {};
    (data ?? []).forEach((r, i) => { next[rowKey(r, i)] = initialDraft(r); });
    setDrafts(next);
  }, [data]);

  const apply = useMutation({
    mutationFn: async () => {
      const rows = (data ?? []).map((r, i) => ({ r, d: drafts[rowKey(r, i)] })).filter((x) => x.d?.selected);
      const items = [];
      for (const { r, d } of rows) {
        let masterId = r.covenantId;
        if (!masterId) {
          const created = await core.createCustom(cpId!, {
            name: r.name, frequency: d.frequency,
            definition: d.expression.trim() || r.customDefinition || r.name,
            assessmentType: d.expression.trim() ? 'AUTO' : 'MANUAL',
          });
          masterId = created.covenantId;
          items.push({ covenantMasterId: masterId, frequency: d.frequency });
          continue;
        }
        const source = thresholdSourceFor(r, d.expression);
        items.push({
          covenantMasterId: masterId, frequency: d.frequency,
          conditionExpression: r.assessmentType === 'MANUAL' || !d.expression.trim() ? null : d.expression.trim(),
          thresholdSource: r.assessmentType === 'MANUAL' ? null : source,
        });
      }
      return core.apply(cpId!, items);
    },
    onSuccess: (applied) => {
      notifications.show({ color: 'green', message: `${applied.length} covenant(s) applied.` });
      qc.invalidateQueries({ queryKey: ['onboarding', cpId] });
      navigate(`/cp/${encodeURIComponent(cpId!)}`);
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });

  const [suggested, setSuggested] = useState<Record<string, string>>({});
  // A11: ask the threshold model for the ticked library covenants and pre-fill its rule; the RM still decides.
  const suggest = useMutation({
    mutationFn: () => {
      const items = (data ?? []).map((r, i) => ({ r, key: rowKey(r, i) }))
        .filter(({ r, key }) => r.covenantId && r.assessmentType !== 'MANUAL' && (drafts[key] ?? initialDraft(r)).selected)
        .map(({ r, key }) => ({ covenantMasterId: r.covenantId!, covenantName: r.name,
          currentExpression: (drafts[key] ?? initialDraft(r)).expression || null }));
      return r3.thresholdSuggestions(cpId!, items);
    },
    onSuccess: (list) => {
      const notes: Record<string, string> = {};
      (data ?? []).forEach((r, i) => {
        const s = list.find((x) => x.covenantMasterId === r.covenantId);
        if (s) {
          const key = rowKey(r, i);
          update(key, { ...(drafts[key] ?? initialDraft(r)), expression: s.recommendedExpression });
          notes[key] = `Suggested${s.confidence === null ? '' : ` (${Math.round(Number(s.confidence) * 100)}%)`}: `
            + `${s.recommendedExpression}${s.rationale ? `. ${s.rationale}` : ''}`;
        }
      });
      setSuggested(notes);
      notifications.show({ color: list.length ? 'green' : 'gray',
        message: list.length ? `${list.length} threshold(s) suggested. Check them before applying.` : 'No suggestions.' });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });

  const update = (key: string, patch: Partial<Draft>) =>
    setDrafts((prev) => ({ ...prev, [key]: { ...prev[key], ...patch } }));
  const selectedCount = Object.values(drafts).filter((d) => d.selected).length;

  return (
    <Stack>
      <Group justify="space-between" align="end">
        <Title order={2}>{cpId ? `Onboard CP ${cpId}` : 'Onboarding'}</Title>
        <CpPicker key={cpId} initial={cpId} onOpen={(cp) => navigate(`/onboarding/${encodeURIComponent(cp)}`)} />
      </Group>
      {cpId && isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && (
        <>
          <Alert color="blue" variant="light">
            Rows from the loan agreement (EXTRACTED) show the page they came from. Nothing is monitored until you
            apply it here.
          </Alert>
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Apply</Table.Th><Table.Th>Covenant</Table.Th><Table.Th>Source</Table.Th>
                <Table.Th>Rule</Table.Th><Table.Th>Frequency</Table.Th><Table.Th>Why</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.map((r, i) => {
                const key = rowKey(r, i);
                const d = drafts[key] ?? initialDraft(r);
                return (
                  <Table.Tr key={key}>
                    <Table.Td>
                      <Checkbox aria-label={`Apply ${r.name}`} checked={d.selected} disabled={r.alreadyApplied}
                        onChange={(e) => update(key, { selected: e.currentTarget.checked })} />
                    </Table.Td>
                    <Table.Td>
                      <Text>{r.name}</Text>
                      {r.alreadyApplied && <Badge size="xs" color="gray">already applied</Badge>}
                    </Table.Td>
                    <Table.Td>
                      <Badge color={SOURCE_COLOR[r.source]} variant="light">{r.source.replace('_', ' ')}</Badge>
                      {r.extractionConfidence !== null && (
                        <Text size="xs" c="dimmed">{Math.round(r.extractionConfidence * 100)}% confidence</Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      {r.assessmentType === 'MANUAL' && r.covenantId ? <Text size="sm" c="dimmed">Manual check</Text> : (
                        <TextInput aria-label={`Rule for ${r.name}`} value={d.expression} ff="monospace"
                          placeholder="Master rule" onChange={(e) => update(key, { expression: e.currentTarget.value })} />
                      )}
                      {suggested[key] && <Text size="xs" c="grape">{suggested[key]}</Text>}
                      {r.mlRecommendedExpression && r.source === 'EXTRACTED' && (
                        <Text size="xs" c="dimmed">ML suggested {r.mlRecommendedExpression}</Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Select aria-label={`Frequency for ${r.name}`} w={130} value={d.frequency} allowDeselect={false}
                        data={FREQUENCIES} onChange={(v) => v && update(key, { frequency: v as Frequency })} />
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{r.matchReason}</Text>
                      {r.evidence?.quote && (
                        <Tooltip label={r.evidence.quote} multiline w={360}>
                          <Text size="xs" c="teal" td="underline">“{r.evidence.quote.slice(0, 60)}…”</Text>
                        </Tooltip>
                      )}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
          {canApply && <Group justify="end">
            <Button variant="light" onClick={() => suggest.mutate()} loading={suggest.isPending}
              disabled={selectedCount === 0}>Suggest thresholds</Button>
            <Button onClick={() => apply.mutate()} loading={apply.isPending} disabled={selectedCount === 0}>
              Apply {selectedCount} covenant(s)
            </Button>
          </Group>}
        </>
      )}
    </Stack>
  );
}
