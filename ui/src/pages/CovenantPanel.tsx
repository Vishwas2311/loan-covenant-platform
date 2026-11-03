import {
  Anchor, Badge, Button, Drawer, FileButton, Group, Modal, SegmentedControl, Select, Stack, Table, Tabs, Text,
  Textarea, TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { r3 } from '../api/endpoints3';
import type { Frequency, ReviewRow } from '../api/types';
import type { Assessment } from '../api/types3';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';
import { formatValue } from '../format';

const FREQUENCIES: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY', 'CUSTOM'];

/** The number a simple rule compares against ("DSCR >= 1.50" → 1.5), for the limit line on the chart. */
export function ruleLimit(expression: string | null | undefined): number | null {
  const match = /(>=|<=|>|<|=)\s*(-?\d+(?:\.\d+)?)\s*$/.exec(expression ?? '');
  return match ? Number(match[2]) : null;
}

function when(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : '—';
}

/** A1: a person records the outcome of a covenant the app cannot check by itself. */
export function RecordResultDialog({ cpId, row, onClose }: { cpId: string; row: ReviewRow; onClose: () => void }) {
  const qc = useQueryClient();
  const [result, setResult] = useState<'COMPLIANT' | 'BREACHED' | 'NA'>('COMPLIANT');
  const [note, setNote] = useState('');
  const save = useMutation({
    mutationFn: async () => {
      // A person-checked covenant has nothing to fill in until a check is opened, so open one first.
      const id = row.latestResult === 'PENDING_REVIEW' && row.latestAssessmentId
        ? row.latestAssessmentId
        : (await r3.checkNow(row.appliedCovenantId)).id;
      return r3.completeManual(id, result, note.trim());
    },
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Result recorded.' });
      qc.invalidateQueries({ queryKey: ['review', cpId] });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Modal opened onClose={onClose} title={`Record result: ${row.covenantName}`}>
      <Stack>
        <Text size="sm" c="dimmed">{row.conditionExpression}</Text>
        <SegmentedControl value={result} onChange={(v) => setResult(v as typeof result)} data={[
          { value: 'COMPLIANT', label: 'Met' }, { value: 'BREACHED', label: 'Not met' },
          { value: 'NA', label: 'Not applicable' }]} />
        <Textarea label="What you checked" required minRows={3} value={note}
          onChange={(e) => setNote(e.currentTarget.value)}
          placeholder="e.g. Insurance policy 123 renewed until 31 March 2027, copy attached" />
        <Group justify="end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!note.trim()}>Save result</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** A4: past results as a line against the rule's limit, newest last. */
export function TrendChart({ points, limit }: { points: { label: string; value: number }[]; limit: number | null }) {
  if (points.length < 2) {
    return <Text size="sm" c="dimmed">A chart appears once there are at least two numeric results.</Text>;
  }
  const W = 560;
  const H = 180;
  const pad = 28;
  const values = points.map((p) => p.value).concat(limit === null ? [] : [limit]);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const x = (i: number) => pad + (i * (W - 2 * pad)) / (points.length - 1);
  const y = (v: number) => H - pad - ((v - min) * (H - 2 * pad)) / span;
  const line = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join(' ');
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img"
      aria-label={`Trend of ${points.length} results${limit === null ? '' : `, limit ${limit}`}`}>
      {limit !== null && (
        <>
          <line x1={pad} x2={W - pad} y1={y(limit)} y2={y(limit)} stroke="var(--mantine-color-red-6)"
            strokeDasharray="5 4" />
          <text x={W - pad} y={y(limit) - 4} textAnchor="end" fontSize="11" fill="var(--mantine-color-red-7)">
            limit {limit}
          </text>
        </>
      )}
      <path d={line} fill="none" stroke="var(--mantine-color-blue-6)" strokeWidth="2" />
      {points.map((p, i) => (
        <g key={`${p.label}-${i}`}>
          <circle cx={x(i)} cy={y(p.value)} r="3.5" fill="var(--mantine-color-blue-6)" />
          <title>{`${p.label}: ${p.value}`}</title>
        </g>
      ))}
      <text x={pad} y={H - 6} fontSize="11" fill="var(--mantine-color-dimmed)">{points[0].label}</text>
      <text x={W - pad} y={H - 6} fontSize="11" textAnchor="end" fill="var(--mantine-color-dimmed)">
        {points[points.length - 1].label}
      </text>
    </svg>
  );
}

function Trend({ row }: { row: ReviewRow }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['history', row.appliedCovenantId], queryFn: () => r3.history(row.appliedCovenantId),
  });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const rows: Assessment[] = [...(data ?? [])].sort((a, b) =>
    (a.executedAt ?? a.scheduledAt ?? '').localeCompare(b.executedAt ?? b.scheduledAt ?? ''));
  const points = rows
    .filter((a) => a.computedValue !== null && Number.isFinite(Number(a.computedValue)))
    .map((a) => ({ label: (a.executedAt ?? a.scheduledAt ?? '').slice(0, 10), value: Number(a.computedValue) }));
  return (
    <Stack>
      <TrendChart points={points} limit={ruleLimit(row.conditionExpression)} />
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr><Table.Th>When</Table.Th><Table.Th>Value</Table.Th><Table.Th>Result</Table.Th>
            <Table.Th>By</Table.Th><Table.Th>Note</Table.Th></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {[...rows].reverse().map((a) => (
            <Table.Tr key={a.id}>
              <Table.Td>{when(a.executedAt ?? a.scheduledAt)}</Table.Td>
              <Table.Td>{formatValue(a.computedValue)}</Table.Td>
              <Table.Td><StatusBadge value={a.result} /></Table.Td>
              <Table.Td>{a.triggeredByUser ?? a.triggeredBy}</Table.Td>
              <Table.Td>{a.assessmentNote ?? a.errorMessage}</Table.Td>
            </Table.Tr>
          ))}
          {rows.length === 0 && <Table.Tr><Table.Td colSpan={5}><Text c="dimmed">No results yet.</Text></Table.Td></Table.Tr>}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function Evidence({ cpId, row, canAct }: { cpId: string; row: ReviewRow; canAct: boolean }) {
  const qc = useQueryClient();
  const [list, setList] = useState(row.attachments ?? []);
  const done = (next: typeof list) => {
    setList(next);
    qc.invalidateQueries({ queryKey: ['review', cpId] });
  };
  const add = useMutation({
    mutationFn: (files: File[]) => r3.addAttachments(cpId, row.appliedCovenantId, files),
    onSuccess: (next) => {
      done(next);
      notifications.show({ color: 'green', message: 'Evidence added.' });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const remove = useMutation({
    mutationFn: (id: string) => r3.removeAttachment(cpId, row.appliedCovenantId, id),
    onSuccess: done,
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Stack>
      {canAct && (
        <FileButton multiple onChange={(files) => files.length > 0 && add.mutate(files)}>
          {(props) => <Button {...props} variant="light" loading={add.isPending} w="fit-content">Add evidence</Button>}
        </FileButton>
      )}
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr><Table.Th>File</Table.Th><Table.Th>Added by</Table.Th><Table.Th>When</Table.Th><Table.Th /></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {list.map((a) => (
            <Table.Tr key={a.id}>
              <Table.Td>
                <Anchor component="button" onClick={() => r3.downloadAttachment(cpId, row.appliedCovenantId, a)
                  .catch((e) => notifications.show({ color: 'red', message: errorMessage(e) }))}>{a.fileName}</Anchor>
              </Table.Td>
              <Table.Td>{a.uploadedBy}</Table.Td>
              <Table.Td>{when(a.uploadedAt)}</Table.Td>
              <Table.Td>
                {canAct && (
                  <Button size="xs" variant="subtle" color="red" loading={remove.isPending}
                    onClick={() => window.confirm(`Remove ${a.fileName}?`) && remove.mutate(a.id)}>Remove</Button>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
          {list.length === 0 && <Table.Tr><Table.Td colSpan={4}><Text c="dimmed">No evidence yet.</Text></Table.Td></Table.Tr>}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

/** A3: this CP's own rule for the covenant, for example after an amendment; empty = the library rule. */
function RuleForCp({ cpId, row, canAct, override }: {
  cpId: string; row: ReviewRow; canAct: boolean; override: string | null;
}) {
  const qc = useQueryClient();
  const [rule, setRule] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const shown = rule ?? row.conditionExpression ?? '';
  const save = useMutation({
    mutationFn: (expression: string) =>
      r3.updateApplied(row.appliedCovenantId, { conditionExpression: expression, reason: reason.trim() }),
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Rule saved. It applies from the next check.' });
      ['applied', 'review'].forEach((k) => qc.invalidateQueries({ queryKey: [k, cpId] }));
      qc.invalidateQueries({ queryKey: ['definition-history', row.appliedCovenantId] });
      setRule(null);
      setReason('');
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  if (row.assessmentType === 'MANUAL') {
    return null;
  }
  return (
    <Stack gap="xs">
      <Group align="end">
        <TextInput label="Rule for this CP" value={shown} disabled={!canAct} w={280}
          styles={{ input: { fontFamily: 'monospace' } }} onChange={(e) => setRule(e.currentTarget.value)}
          description={override ? 'Differs from the library rule' : 'Same as the library rule'} />
        {canAct && (
          <TextInput label="Why" value={reason} onChange={(e) => setReason(e.currentTarget.value)} w={260}
            placeholder="e.g. Amendment 2 signed 1 Nov 2026" />
        )}
      </Group>
      {canAct && (
        <Group>
          <Button size="xs" loading={save.isPending} disabled={rule === null || !shown.trim() || !reason.trim()}
            onClick={() => save.mutate(shown.trim())}>Save rule</Button>
          {override && (
            <Button size="xs" variant="subtle" loading={save.isPending} disabled={!reason.trim()}
              onClick={() => save.mutate('')}>Use the library rule</Button>
          )}
        </Group>
      )}
    </Stack>
  );
}

/** A3: how often this CP's covenant is checked, pausing it, and every change made to it. */
function Monitoring({ cpId, row, canAct }: { cpId: string; row: ReviewRow; canAct: boolean }) {
  const qc = useQueryClient();
  const applied = useQuery({ queryKey: ['applied', cpId], queryFn: () => r3.applied(cpId) });
  const changes = useQuery({
    queryKey: ['applied-changes', row.appliedCovenantId], queryFn: () => r3.appliedChanges(row.appliedCovenantId),
  });
  const definitions = useQuery({
    queryKey: ['definition-history', row.appliedCovenantId],
    queryFn: () => r3.definitionHistory(row.appliedCovenantId),
  });
  const current = applied.data?.find((a) => a.id === row.appliedCovenantId);
  const [frequency, setFrequency] = useState<Frequency | null>(null);
  const [cron, setCron] = useState<string | null>(null);
  const shownFrequency = frequency ?? current?.frequency ?? null;
  const shownCron = cron ?? current?.cronExpression ?? '';
  const update = useMutation({
    mutationFn: (body: Parameters<typeof r3.updateApplied>[1]) => r3.updateApplied(row.appliedCovenantId, body),
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Saved.' });
      ['applied', 'review'].forEach((k) => qc.invalidateQueries({ queryKey: [k, cpId] }));
      qc.invalidateQueries({ queryKey: ['applied-changes', row.appliedCovenantId] });
      setFrequency(null);
      setCron(null);
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  if (applied.isLoading) {
    return <Loading />;
  }
  if (applied.error) {
    return <ErrorNote error={applied.error} />;
  }
  const changed = (frequency !== null && frequency !== current?.frequency)
    || (cron !== null && cron !== (current?.cronExpression ?? ''));
  return (
    <Stack>
      <RuleForCp cpId={cpId} row={row} canAct={canAct} override={current?.conditionOverride ?? null} />
      <Group align="end">
        <Select label="How often" data={FREQUENCIES} value={shownFrequency} allowDeselect={false} disabled={!canAct}
          onChange={(v) => v && setFrequency(v as Frequency)} w={160} />
        {shownFrequency === 'CUSTOM' && (
          <TextInput label="Custom schedule (cron)" value={shownCron} disabled={!canAct} w={220}
            onChange={(e) => setCron(e.currentTarget.value)} placeholder="0 0 9 15 * ?"
            description="Quartz cron, e.g. 9:00 on the 15th of every month" />
        )}
        {canAct && (
          <Button disabled={!changed || (shownFrequency === 'CUSTOM' && !shownCron.trim())} loading={update.isPending}
            onClick={() => update.mutate({ frequency: shownFrequency!,
              cronExpression: shownFrequency === 'CUSTOM' ? shownCron.trim() : null })}>Save schedule</Button>
        )}
      </Group>
      <Group>
        <Text size="sm">Monitoring:</Text>
        <StatusBadge value={current?.status} />
        {canAct && current?.status === 'ACTIVE' && (
          <Button size="xs" variant="light" color="gray" loading={update.isPending}
            onClick={() => window.confirm('Pause monitoring this covenant? It will not be checked until resumed.')
              && update.mutate({ status: 'SUSPENDED' })}>Pause monitoring</Button>
        )}
        {canAct && current?.status === 'SUSPENDED' && (
          <Button size="xs" variant="light" loading={update.isPending}
            onClick={() => update.mutate({ status: 'ACTIVE' })}>Resume monitoring</Button>
        )}
      </Group>
      <Text fw={600} size="sm">Changes to this covenant</Text>
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr><Table.Th>When</Table.Th><Table.Th>What</Table.Th><Table.Th>From → to</Table.Th><Table.Th>By</Table.Th></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(definitions.data ?? []).map((d, i) => (
            <Table.Tr key={`d${i}`}>
              <Table.Td>{when(d.changedAt)}</Table.Td><Table.Td>Rule</Table.Td>
              <Table.Td>
                <Text ff="monospace" size="xs">{d.oldExpression ?? '—'} → {d.newExpression ?? '—'}</Text>
                {d.reason && <Text size="xs" c="dimmed">{d.reason}</Text>}
              </Table.Td>
              <Table.Td>{d.changedBy}</Table.Td>
            </Table.Tr>
          ))}
          {(changes.data ?? []).map((c) => (
            <Table.Tr key={c.id}>
              <Table.Td>{when(c.changedDate)}</Table.Td>
              <Table.Td>{c.changeType === 'STATUS' ? 'Monitoring' : c.changeType === 'CRON' ? 'Custom schedule' : 'Frequency'}</Table.Td>
              <Table.Td>{c.oldValue ?? '—'} → {c.newValue ?? '—'}</Table.Td>
              <Table.Td>{c.changedBy}</Table.Td>
            </Table.Tr>
          ))}
          {(definitions.data ?? []).length + (changes.data ?? []).length === 0 && (
            <Table.Tr><Table.Td colSpan={4}><Text c="dimmed">No changes since it was applied.</Text></Table.Td></Table.Tr>
          )}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

/** A3–A6: everything about one covenant on one CP, opened from the covenant grid. */
export function CovenantDrawer({ cpId, row, canAct, onClose }: {
  cpId: string; row: ReviewRow; canAct: boolean; onClose: () => void;
}) {
  const qc = useQueryClient();
  const check = useMutation({
    mutationFn: () => r3.checkNow(row.appliedCovenantId),
    onSuccess: (a) => {
      notifications.show({ color: a.result === 'BREACHED' ? 'red' : 'green',
        message: `Checked: ${(a.result ?? 'no result').replace(/_/g, ' ')}${a.computedValue ? ` (${formatValue(a.computedValue)})` : ''}` });
      qc.invalidateQueries({ queryKey: ['review', cpId] });
      qc.invalidateQueries({ queryKey: ['history', row.appliedCovenantId] });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Drawer opened onClose={onClose} position="right" size="xl" title={row.covenantName}>
      <Stack>
        <Group justify="space-between">
          <Group gap="xs">
            <Text ff="monospace" size="sm">{row.conditionExpression}</Text>
            <StatusBadge value={row.latestResult} />
            {row.assessmentType === 'MANUAL' && <Badge variant="outline" size="sm">checked by a person</Badge>}
          </Group>
          {canAct && <Button size="xs" onClick={() => check.mutate()} loading={check.isPending}>Check now</Button>}
        </Group>
        <Tabs defaultValue="trend" keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="trend">Trend</Tabs.Tab>
            <Tabs.Tab value="evidence">Evidence ({(row.attachments ?? []).length})</Tabs.Tab>
            <Tabs.Tab value="monitoring">Schedule and changes</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="trend" pt="md"><Trend row={row} /></Tabs.Panel>
          <Tabs.Panel value="evidence" pt="md"><Evidence cpId={cpId} row={row} canAct={canAct} /></Tabs.Panel>
          <Tabs.Panel value="monitoring" pt="md"><Monitoring cpId={cpId} row={row} canAct={canAct} /></Tabs.Panel>
        </Tabs>
      </Stack>
    </Drawer>
  );
}
