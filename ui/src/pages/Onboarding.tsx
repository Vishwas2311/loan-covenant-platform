import {
  Badge, Button, Card, Checkbox, Group, Select, Stack, Table, Text, TextInput, Title, Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { core } from '../api/endpoints';
import { r3 } from '../api/endpoints3';
import type { Frequency, OnboardingRow } from '../api/types';
import { hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { Kpi, KpiStrip, PageHeader } from '../components/Board';
import { CpChips, CpChooser, CpPicker, humanize } from '../components/CpPicker';
import { useMeasures } from '../components/useMeasures';
import { describeRule } from '../format';
import { ErrorNote, Loading } from '../components/Status';

const FREQUENCIES: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'];
const SOURCE_COLOR: Record<OnboardingRow['source'], string> = {
  RECOMMENDED: 'gray', ML: 'gray', EXTRACTED: 'brand', EXTRACTED_CUSTOM: 'brand',
};
const SOURCE_LABEL: Record<OnboardingRow['source'], string> = {
  RECOMMENDED: 'Library match', ML: 'ML suggested', EXTRACTED: 'From agreement', EXTRACTED_CUSTOM: 'New, from agreement',
};

type Show = 'all' | 'agreement' | 'chosen' | 'library' | 'applied';
const SHOW: Record<Show, (r: OnboardingRow, selected: boolean) => boolean> = {
  all: () => true,
  agreement: (r) => r.source === 'EXTRACTED' || r.source === 'EXTRACTED_CUSTOM',
  chosen: (_r, selected) => selected,
  library: (r) => r.source === 'RECOMMENDED' || r.source === 'ML',
  applied: (r) => r.alreadyApplied,
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
  const measures = useMeasures();
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
  const [q, setQ] = useState('');
  const [show, setShow] = useState<Show>('all');
  const view = useMemo(() => {
    const needle = q.trim().toLowerCase();
    // Read from the agreement first, then ML and library suggestions; already applied last.
    const rank = (r: OnboardingRow) => (r.alreadyApplied ? 3 : SHOW.agreement(r, false) ? 0 : r.source === 'ML' ? 1 : 2);
    return (data ?? []).map((r, i) => ({ r, i })).filter(({ r, i }) => {
      const d = drafts[rowKey(r, i)] ?? initialDraft(r);
      return SHOW[show](r, d.selected) && (!needle || r.name.toLowerCase().includes(needle)
        || (r.conditionOverride ?? '').toLowerCase().includes(needle));
    }).sort((a, b) => rank(a.r) - rank(b.r) || a.r.name.localeCompare(b.r.name));
  }, [data, drafts, q, show]);
  const count = (s: Show) => (data ?? []).filter((r, i) => SHOW[s](r, (drafts[rowKey(r, i)] ?? initialDraft(r)).selected)).length;

  const go = (cp: string) => navigate(`/onboarding/${encodeURIComponent(cp)}`);
  const pick = (s: Show) => setShow((v) => (v === s ? 'all' : s));
  const fromAgreement = count('agreement');
  const applied = count('applied');
  let finding: string | undefined;
  if (!cpId) {
    finding = 'Pick a credit proposal to choose which covenants to monitor. CPs with no covenants yet are the ones to start with.';
  } else if (data) {
    finding = data.length === 0 ? 'No covenants proposed yet. Upload the loan agreement on Documents to have its covenants read.'
      : `${data.length} covenant${data.length === 1 ? '' : 's'} proposed, ${fromAgreement} read from the loan agreement. `
        + `${selectedCount} ticked to apply; nothing is monitored until you apply it.`;
  }
  return (
    <Stack gap="lg">
      <PageHeader title={cpId ? `Onboard ${cpId}` : 'Onboarding'} finding={finding}
        chips={cpId ? <CpChips cpId={cpId} /> : undefined}
        actions={<CpPicker key={cpId} initial={cpId} onOpen={go} />} />
      {!cpId && <CpChooser onOpen={go} />}
      {cpId && isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && data.length > 0 && (
        <KpiStrip ariaLabel="Proposed covenants">
          <Kpi label="Proposed" value={data.length} onClick={() => setShow('all')} selected={show === 'all'}
            ariaLabel="Show covenants: All" note="Library, ML and agreement" />
          <Kpi label="From the agreement" value={fromAgreement} onClick={() => pick('agreement')} selected={show === 'agreement'}
            ariaLabel="Show covenants: From the agreement" note="With the page they came from" />
          <Kpi label="Library matches" value={count('library')} onClick={() => pick('library')} selected={show === 'library'}
            ariaLabel="Show covenants: Library matches" note="Suggested for this segment and sector" />
          <Kpi label="Already applied" value={applied} onClick={() => pick('applied')} selected={show === 'applied'}
            ariaLabel="Show covenants: Already applied" note={applied ? 'Monitored now' : 'None yet'} />
          <Kpi label="Ticked to apply" value={selectedCount} lead tone={selectedCount ? 'info' : 'neutral'}
            onClick={() => pick('chosen')} selected={show === 'chosen'} ariaLabel="Show covenants: Ticked"
            note={selectedCount ? 'Check rule and frequency, then apply' : 'Tick the covenants to monitor'} />
        </KpiStrip>
      )}
      {data && (
        <Card padding={0}>
          <Group justify="space-between" align="flex-end" p="lg" pb="md" wrap="wrap">
            <Stack gap={2}>
              <Title order={4}>Proposed covenants</Title>
              <Text size="sm" c="dimmed">Leave a rule empty to use the library rule.</Text>
            </Stack>
            <TextInput placeholder="Search covenants" aria-label="Search covenants" value={q} w={260}
              onChange={(e) => setQ(e.currentTarget.value)} />
          </Group>
          <Table.ScrollContainer minWidth={900}>
            <Table verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th w={56}>Apply</Table.Th><Table.Th>Covenant</Table.Th><Table.Th>Source</Table.Th>
                  <Table.Th>Rule</Table.Th><Table.Th>Frequency</Table.Th><Table.Th>Why</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {view.map(({ r, i }) => {
                  const key = rowKey(r, i);
                  const d = drafts[key] ?? initialDraft(r);
                  const words = describeRule(d.expression || r.conditionOverride, measures)?.text;
                  return (
                    <Table.Tr key={key} bg={d.selected ? 'var(--app-raised)' : undefined}>
                      <Table.Td>
                        <Checkbox aria-label={`Apply ${r.name}`} checked={d.selected} disabled={r.alreadyApplied}
                          onChange={(e) => update(key, { selected: e.currentTarget.checked })} />
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm" fw={550}>{r.name}</Text>
                        {r.alreadyApplied && <Text size="xs" c="dimmed">Already applied</Text>}
                      </Table.Td>
                      <Table.Td style={{ whiteSpace: 'nowrap' }}>
                        <Badge color={SOURCE_COLOR[r.source]} variant="light">{SOURCE_LABEL[r.source]}</Badge>
                        {r.extractionConfidence !== null && (
                          <Text size="xs" c="dimmed" mt={2}>{Math.round(r.extractionConfidence * 100)}% confidence</Text>
                        )}
                      </Table.Td>
                      <Table.Td miw={240}>
                        {r.assessmentType === 'MANUAL' && r.covenantId ? <Text size="sm" c="dimmed">Manual check</Text> : (
                          <TextInput aria-label={`Rule for ${r.name}`} value={d.expression} ff="monospace" size="sm"
                            placeholder="Library rule" onChange={(e) => update(key, { expression: e.currentTarget.value })} />
                        )}
                        {words && <Text size="xs" c="dimmed" mt={4}>{words}</Text>}
                        {suggested[key] && <Text size="xs" c="var(--data-hue)" mt={2}>{suggested[key]}</Text>}
                        {r.mlRecommendedExpression && r.source === 'EXTRACTED' && (
                          <Text size="xs" c="dimmed" mt={2}>ML suggested {r.mlRecommendedExpression}</Text>
                        )}
                      </Table.Td>
                      <Table.Td>
                        <Select aria-label={`Frequency for ${r.name}`} w={140} size="sm" value={d.frequency} allowDeselect={false}
                          data={FREQUENCIES.map((f) => ({ value: f, label: humanize(f) }))}
                          onChange={(v) => v && update(key, { frequency: v as Frequency })} />
                      </Table.Td>
                      <Table.Td maw={320}>
                        <Text size="sm">{r.matchReason}</Text>
                        {r.evidence?.quote && (
                          <Tooltip label={r.evidence.quote} multiline w={360}>
                            <Text size="xs" c="dimmed" fs="italic" lineClamp={2}>“{r.evidence.quote}”</Text>
                          </Tooltip>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
                {view.length === 0 && (
                  <Table.Tr><Table.Td colSpan={6}><Text c="dimmed" size="sm">No covenant matches.</Text></Table.Td></Table.Tr>
                )}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          {canApply && (
            <Group justify="space-between" p="md" px="lg" className="action-bar">
              <Text size="sm" c="dimmed">
                {selectedCount ? `${selectedCount} ticked. They are checked from the next due date.` : 'Tick covenants to apply them.'}
              </Text>
              <Group gap="sm">
                <Button variant="light" onClick={() => suggest.mutate()} loading={suggest.isPending}
                  disabled={selectedCount === 0}>Suggest thresholds</Button>
                <Button onClick={() => apply.mutate()} loading={apply.isPending} disabled={selectedCount === 0}>
                  Apply {selectedCount} covenant(s)
                </Button>
              </Group>
            </Group>
          )}
        </Card>
      )}
    </Stack>
  );
}
