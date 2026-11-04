import { Button, Card, Group, Stack, Table, Text, Title } from '@mantine/core';
import { IconChevronRight } from '@tabler/icons-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { DpsDocument } from '../api/types';
import type { CpCard } from '../api/types2';
import { Kpi, KpiStrip } from '../components/Board';
import { MAGNITUDE, STATUS } from '../components/charts';
import { CpSearch, Dot, humanize, useCpDocuments, useCpSearch, useMyCps } from '../components/CpPicker';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';
import { fmtDate } from '../format';

const READING = new Set(['QUEUED', 'RUNNING', 'PROCESSING']);
const TO_CHECK = new Set(['NEEDS_REVIEW', 'FAILED']);
const live = (docs: DpsDocument[] | undefined) => (docs ?? []).filter((d) => d.status !== 'REMOVED');
const enc = encodeURIComponent;

function CpCell({ cp, onOpen }: { cp: CpCard; onOpen: () => void }) {
  return (
    <button type="button" className="row-open" aria-label={`Open ${cp.cpId}`} onClick={(e) => { e.stopPropagation(); onOpen(); }}>
      <Text size="sm" fw={550} lh={1.3}>{cp.name ?? cp.cpId}</Text>
      <Text size="xs" c="dimmed">{[cp.cpId, ...new Set([humanize(cp.businessSegment), humanize(cp.sector)])].filter(Boolean).join(' · ')}</Text>
    </button>
  );
}

/** Where a CP is in onboarding, from its loan agreements and what is already applied. */
type Stage = 'ready' | 'check' | 'upload' | 'reading' | 'done';

const STAGE: Record<Stage, { order: number; label: string; color: string; next: string; action: string }> = {
  ready: { order: 0, label: 'Ready to onboard', color: MAGNITUDE, next: 'Choose the covenants to monitor', action: 'Onboard' },
  check: { order: 1, label: 'Agreement to check', color: STATUS.warn, next: 'Check what was read from the agreement', action: 'Check agreement' },
  upload: { order: 2, label: 'No agreement yet', color: STATUS.neutral, next: 'Upload the loan agreement', action: 'Upload' },
  reading: { order: 3, label: 'Agreement being read', color: STATUS.neutral, next: 'Wait a few minutes', action: 'View' },
  done: { order: 4, label: 'Onboarded', color: STATUS.good, next: 'Nothing to do', action: 'Review' },
};

export function stageOf(cp: CpCard, docs: DpsDocument[] | undefined): Stage {
  const agreements = live(docs).filter((d) => d.type === 'LOAN_AGREEMENT');
  if (cp.applied > 0) {
    return 'done';
  }
  if (agreements.some((d) => TO_CHECK.has(d.status))) {
    return 'check';
  }
  if (agreements.some((d) => d.status === 'PUBLISHED')) {
    return 'ready';
  }
  return agreements.some((d) => READING.has(d.status)) ? 'reading' : 'upload';
}

/**
 * Onboarding before a CP is chosen: a queue of the user's CPs by onboarding stage, the ones ready for
 * covenants first, each with its next step.
 */
export function OnboardingQueue({ onOpen }: { onOpen: (cpId: string) => void }) {
  const { data, isLoading, error } = useMyCps();
  const docs = useCpDocuments((data ?? []).map((c) => c.cpId));
  const search = useCpSearch(data);
  const [stage, setStage] = useState<Stage | null>(null);
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const all = (data ?? []).map((cp) => ({ cp, stage: stageOf(cp, docs.byCp.get(cp.cpId)) }));
  const count = (s: Stage) => all.filter((r) => r.stage === s).length;
  const rows = all.filter((r) => (!stage || r.stage === stage) && search.match(r.cp))
    .sort((a, b) => STAGE[a.stage].order - STAGE[b.stage].order || (a.cp.name ?? a.cp.cpId).localeCompare(b.cp.name ?? b.cp.cpId));
  const pick = (s: Stage) => setStage((v) => (v === s ? null : s));
  const go = (cp: CpCard, s: Stage) => (s === 'check' || s === 'upload' || s === 'reading'
    ? `/documents/${enc(cp.cpId)}` : `/onboarding/${enc(cp.cpId)}`);
  return (
    <Stack gap="md">
      <KpiStrip ariaLabel="Onboarding stages">
        <Kpi label="Ready to onboard" value={count('ready')} lead tone={count('ready') ? 'info' : 'neutral'} onClick={() => pick('ready')}
          selected={stage === 'ready'} ariaLabel="Show CPs: Ready to onboard" note="Agreement read, no covenants yet" />
        <Kpi label="Agreement to check" value={count('check')} tone={count('check') ? 'warn' : 'neutral'} onClick={() => pick('check')}
          selected={stage === 'check'} ariaLabel="Show CPs: Agreement to check" note="Reading needs a person" />
        <Kpi label="No agreement yet" value={count('upload') + count('reading')} onClick={() => pick('upload')}
          selected={stage === 'upload'} ariaLabel="Show CPs: No agreement yet" note={count('reading') ? `${count('reading')} being read` : 'Upload it on Documents'} />
        <Kpi label="Onboarded" value={count('done')} onClick={() => pick('done')} selected={stage === 'done'}
          ariaLabel="Show CPs: Onboarded" note="Covenants are monitored" />
      </KpiStrip>
      <Card padding={0}>
        <Group justify="space-between" p="md" wrap="wrap" gap="sm">
          <Stack gap={2}>
            <Title order={4}>Onboarding queue</Title>
            <Text size="sm" c="dimmed">{stage ? STAGE[stage].label : 'Every CP'}, next step first.</Text>
          </Stack>
          <CpSearch {...search} onOpen={onOpen} />
        </Group>
        <Table.ScrollContainer minWidth={820}>
          <Table>
            <Table.Thead>
              <Table.Tr><Table.Th>Credit proposal</Table.Th><Table.Th>Stage</Table.Th><Table.Th>Loan agreement</Table.Th>
                <Table.Th ta="right">Covenants</Table.Th><Table.Th>Next step</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map(({ cp, stage: s }) => {
                const agreements = live(docs.byCp.get(cp.cpId)).filter((d) => d.type === 'LOAN_AGREEMENT');
                const latest = agreements[0];
                return (
                  <Table.Tr key={cp.cpId} className="row-link" onClick={() => onOpen(cp.cpId)}>
                    <Table.Td><CpCell cp={cp} onOpen={() => onOpen(cp.cpId)} /></Table.Td>
                    <Table.Td><Dot color={STAGE[s].color}>{STAGE[s].label}</Dot></Table.Td>
                    <Table.Td>
                      {latest ? (
                        <>
                          <Text size="sm" lineClamp={1} maw={240}>{latest.fileName}</Text>
                          <Text size="xs" c="dimmed">{fmtDate(latest.uploadedAt)}{agreements.length > 1 ? ` · ${agreements.length} files` : ''}</Text>
                        </>
                      ) : <Text size="sm" c="dimmed">{docs.loading ? '…' : 'None'}</Text>}
                    </Table.Td>
                    <Table.Td ta="right">{cp.applied}</Table.Td>
                    <Table.Td c="dimmed">{STAGE[s].next}</Table.Td>
                    <Table.Td ta="right" onClick={(e) => e.stopPropagation()}>
                      <Button component={Link} to={go(cp, s)} size="xs" variant={s === 'ready' ? 'filled' : 'light'}>
                        {STAGE[s].action}
                      </Button>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
              {rows.length === 0 && (
                <Table.Tr><Table.Td colSpan={6}><Text size="sm" c="dimmed">No CP matches.</Text></Table.Td></Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </Card>
    </Stack>
  );
}

/**
 * Documents before a CP is chosen: an inbox of the files across the user's CPs that need a person,
 * and every CP with its paperwork at a glance.
 */
export function DocumentInbox({ onOpen }: { onOpen: (cpId: string) => void }) {
  const { data, isLoading, error } = useMyCps();
  const cps = data ?? [];
  const docs = useCpDocuments(cps.map((c) => c.cpId));
  const search = useCpSearch(data);
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const name = new Map(cps.map((c) => [c.cpId, c.name ?? c.cpId]));
  const seen = new Set<string>();
  const files: (DpsDocument & { cpId: string })[] = [];
  cps.forEach((c) => live(docs.byCp.get(c.cpId)).forEach((d) => {
    if (!seen.has(d.id)) {
      seen.add(d.id);
      files.push({ ...d, cpId: c.cpId });
    }
  }));
  const toCheck = files.filter((d) => TO_CHECK.has(d.status)).sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt));
  const reading = files.filter((d) => READING.has(d.status)).length;
  const weekAgo = new Date(Date.now() - 7 * 864e5).toISOString();
  const recent = files.filter((d) => d.uploadedAt >= weekAgo).length;
  const noAgreement = cps.filter((c) => !live(docs.byCp.get(c.cpId)).some((d) => d.type === 'LOAN_AGREEMENT')).length;
  const rows = cps.filter(search.match).map((c) => {
    const mine = live(docs.byCp.get(c.cpId));
    const statements = mine.filter((d) => d.type === 'FINANCIAL_STATEMENT');
    return {
      cp: c, mine, agreements: mine.filter((d) => d.type === 'LOAN_AGREEMENT').length, statements: statements.length,
      period: statements.map((d) => d.asOfDate).filter((d): d is string => !!d).sort().pop(),
      check: mine.filter((d) => TO_CHECK.has(d.status)).length, last: mine[0]?.uploadedAt,
    };
  }).sort((a, b) => b.check - a.check || (a.cp.name ?? a.cp.cpId).localeCompare(b.cp.name ?? b.cp.cpId));
  return (
    <Stack gap="md">
      <KpiStrip ariaLabel="Documents across your CPs">
        <Kpi label="Waiting for review" value={toCheck.length} lead tone={toCheck.length ? 'warn' : 'neutral'}
          note={toCheck.length ? 'Listed below, newest first' : 'Nothing to check'} />
        <Kpi label="Being read" value={reading} note="Usually a few minutes" />
        <Kpi label="Uploaded this week" value={recent} note={`${files.length} documents in use`} />
        <Kpi label="CPs with no agreement" value={noAgreement} tone={noAgreement ? 'warn' : 'neutral'} note="Covenants cannot be read yet" />
      </KpiStrip>
      {toCheck.length > 0 && (
        <Card padding={0}>
          <Stack gap={2} p="md">
            <Title order={4}>Waiting for your review</Title>
            <Text size="sm" c="dimmed">Open the CP's documents to check what was read and approve it.</Text>
          </Stack>
          <Table>
            <Table.Thead>
              <Table.Tr><Table.Th>File</Table.Th><Table.Th>Credit proposal</Table.Th><Table.Th>Status</Table.Th>
                <Table.Th>Uploaded</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {toCheck.slice(0, 8).map((d) => (
                <Table.Tr key={d.id}>
                  <Table.Td fw={550}>{d.fileName}</Table.Td>
                  <Table.Td><Text size="sm">{name.get(d.cpId)}</Text><Text size="xs" c="dimmed">{d.cpId}</Text></Table.Td>
                  <Table.Td><StatusBadge value={d.status} /></Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{fmtDate(d.uploadedAt)}<Text size="xs" c="dimmed">{d.uploadedBy}</Text></Table.Td>
                  <Table.Td ta="right">
                    <Button component={Link} to={`/documents/${enc(d.cpId)}`} size="xs" variant="light">Check</Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {toCheck.length > 8 && <Text size="xs" c="dimmed" p="md">{toCheck.length - 8} more on the CPs below.</Text>}
        </Card>
      )}
      <Card padding={0}>
        <Group justify="space-between" p="md" wrap="wrap" gap="sm">
          <Stack gap={2}>
            <Title order={4}>By credit proposal</Title>
            <Text size="sm" c="dimmed">Open a CP to upload or check its documents.</Text>
          </Stack>
          <CpSearch {...search} onOpen={onOpen} />
        </Group>
        <Table.ScrollContainer minWidth={820}>
          <Table>
            <Table.Thead>
              <Table.Tr><Table.Th>Credit proposal</Table.Th><Table.Th ta="right">Agreements</Table.Th>
                <Table.Th ta="right">Statements</Table.Th><Table.Th>Latest period</Table.Th><Table.Th>Last upload</Table.Th>
                <Table.Th>Needs</Table.Th><Table.Th w={28} /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.cp.cpId} className="row-link" onClick={() => onOpen(r.cp.cpId)}>
                  <Table.Td><CpCell cp={r.cp} onOpen={() => onOpen(r.cp.cpId)} /></Table.Td>
                  <Table.Td ta="right">{r.agreements}</Table.Td>
                  <Table.Td ta="right">{r.statements}</Table.Td>
                  <Table.Td>{fmtDate(r.period)}</Table.Td>
                  <Table.Td>{fmtDate(r.last)}</Table.Td>
                  <Table.Td>
                    {r.check > 0 ? <Dot color={STATUS.warn}>{r.check} to review</Dot>
                      : r.agreements === 0 ? <Dot color={STATUS.neutral}>Upload the agreement</Dot>
                        : <Dot color={STATUS.good}>Up to date</Dot>}
                  </Table.Td>
                  <Table.Td><IconChevronRight size={14} color="var(--mantine-color-dimmed)" /></Table.Td>
                </Table.Tr>
              ))}
              {rows.length === 0 && (
                <Table.Tr><Table.Td colSpan={7}><Text size="sm" c="dimmed">No CP matches.</Text></Table.Td></Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        {docs.loading && <Text size="xs" c="dimmed" px="md" pb="md">Loading documents…</Text>}
      </Card>
    </Stack>
  );
}
