import {
  Anchor, Badge, Button, Card, Checkbox, Group, MultiSelect, NumberInput, Select, Stack, Switch, Table, Text,
  TextInput, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { r4 } from '../api/endpoints4';
import type { ReviewRow } from '../api/types';
import type { LetterKind, ReportDefinition, SavedReport, SpreadLine } from '../api/types4';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';

const fail = (e: unknown) => notifications.show({ color: 'red', message: errorMessage(e) });

// ---------------------------------------------------------------- C2 borrower reminders

/** C2: who at the borrower is reminded about compliance certificates, and every reminder sent. */
export function BorrowerReminders({ cpId, canEdit }: { cpId: string; canEdit: boolean }) {
  const qc = useQueryClient();
  const contact = useQuery({ queryKey: ['borrower-contact', cpId], queryFn: () => r4.contact(cpId) });
  const sentQuery = useQuery({ queryKey: ['reminders', cpId], queryFn: () => r4.reminders(cpId) });
  const sent = { data: Array.isArray(sentQuery.data) ? sentQuery.data : [] };
  const [draft, setDraft] = useState<{ contactName: string; email: string; remindersOn: boolean; daysBefore: number } | null>(null);
  const shown = draft ?? {
    contactName: contact.data?.contactName ?? '', email: contact.data?.email ?? '',
    remindersOn: contact.data?.remindersOn ?? true, daysBefore: contact.data?.daysBefore ?? 7,
  };
  const set = (patch: Partial<typeof shown>) => setDraft({ ...shown, ...patch });
  const save = useMutation({
    mutationFn: () => r4.saveContact(cpId, { ...shown, contactName: shown.contactName.trim(), email: shown.email.trim() }),
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Borrower contact saved.' });
      qc.invalidateQueries({ queryKey: ['borrower-contact', cpId] });
      setDraft(null);
    },
    onError: fail,
  });
  if (contact.isLoading) {
    return <Loading />;
  }
  return (
    <Card withBorder>
      <Stack gap="xs">
        <Title order={5}>Reminders to the borrower</Title>
        <Text size="xs" c="dimmed">
          The app e-mails this person before a certificate is due, then once a week while it is overdue.
        </Text>
        <Group align="end">
          <TextInput label="Contact name" value={shown.contactName} disabled={!canEdit}
            onChange={(e) => set({ contactName: e.currentTarget.value })} placeholder="Ms Rao, CFO" />
          <TextInput label="E-mail" value={shown.email} disabled={!canEdit} w={240}
            onChange={(e) => set({ email: e.currentTarget.value })} placeholder="cfo@borrower.com" />
          <NumberInput label="Days before due" min={1} max={60} w={130} value={shown.daysBefore} disabled={!canEdit}
            onChange={(v) => set({ daysBefore: Number(v) || 7 })} />
          <Switch label="Reminders on" checked={shown.remindersOn} disabled={!canEdit}
            onChange={(e) => set({ remindersOn: e.currentTarget.checked })} />
          {canEdit && (
            <Button onClick={() => save.mutate()} loading={save.isPending}
              disabled={!draft || !shown.contactName.trim() || !/^\S+@\S+\.\S+$/.test(shown.email.trim())}>Save</Button>
          )}
        </Group>
        {(sent.data ?? []).length > 0 && (
          <Table>
            <Table.Thead><Table.Tr><Table.Th>Sent</Table.Th><Table.Th>Kind</Table.Th><Table.Th>To</Table.Th>
              <Table.Th>Outcome</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {(sent.data ?? []).map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>{r.sentOn}</Table.Td>
                  <Table.Td>{r.kind === 'DUE_SOON' ? 'Due soon' : 'Overdue'}</Table.Td>
                  <Table.Td>{r.sentTo}</Table.Td>
                  <Table.Td><StatusBadge value={r.outcome === 'SENT' ? 'DONE' : 'FAILED'} />
                    {r.detail && <Text size="xs" c="red">{r.detail}</Text>}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
    </Card>
  );
}

// ---------------------------------------------------------------- C10 ask about one agreement

/** C10: a question about this agreement, answered from its own pages. */
export function AskAgreement({ documentId, onPage }: { documentId: string; onPage: (page: number, quote: string) => void }) {
  const [question, setQuestion] = useState('');
  const ask = useMutation({ mutationFn: () => r4.askAgreement(documentId, question.trim()) });
  return (
    <Card withBorder>
      <Stack gap="xs">
        <Title order={5}>Ask about this agreement</Title>
        <Group align="end">
          <TextInput aria-label="Question about this agreement" style={{ flex: 1 }} value={question}
            onChange={(e) => setQuestion(e.currentTarget.value)}
            placeholder="e.g. What does it say about paying dividends?" />
          <Button onClick={() => ask.mutate()} loading={ask.isPending} disabled={question.trim().length < 3}>Ask</Button>
        </Group>
        {ask.error && <ErrorNote error={ask.error} />}
        {ask.data && (
          <>
            <Text size="sm">{ask.data.answer}</Text>
            {ask.data.citations.length > 0 && (
              <Group gap="xs">
                <Text size="xs" c="dimmed">Pages:</Text>
                {ask.data.citations.map((c) => (
                  <Button key={c.page} size="compact-xs" variant="subtle" onClick={() => onPage(c.page, c.quote)}>
                    p. {c.page}
                  </Button>
                ))}
              </Group>
            )}
            <Text size="xs" c="dimmed">{ask.data.advisory}</Text>
          </>
        )}
      </Stack>
    </Card>
  );
}

// ---------------------------------------------------------------- C12 borrower letters

const LETTERS: { kind: LetterKind; label: string; fits: (r: ReviewRow) => boolean }[] = [
  { kind: 'BREACH_NOTICE', label: 'Breach notice', fits: (r) => r.latestResult === 'BREACHED' },
  { kind: 'WAIVER_GRANTED', label: 'Waiver letter', fits: (r) => r.dispositionStatus === 'WAIVER' },
  { kind: 'EXTENSION_GRANTED', label: 'Extension letter', fits: (r) => r.dispositionStatus === 'EXTENSION' },
];

/** C12: the letters that fit this covenant's state, downloaded as Word to edit and send. */
export function LetterButtons({ cpId, row }: { cpId: string; row: ReviewRow }) {
  const fitting = LETTERS.filter((l) => l.fits(row));
  if (fitting.length === 0) {
    return null;
  }
  return (
    <Group gap="xs">
      {fitting.map((l) => (
        <Button key={l.kind} size="xs" variant="default"
          onClick={() => r4.letter(cpId, row.appliedCovenantId, l.kind).catch(fail)}>{l.label}</Button>
      ))}
    </Group>
  );
}

// ---------------------------------------------------------------- C11 custom reports

const COLUMNS: { value: string; label: string }[] = [
  { value: 'cpId', label: 'CP' }, { value: 'segment', label: 'Segment' }, { value: 'covenantName', label: 'Covenant' },
  { value: 'rule', label: 'Rule' }, { value: 'value', label: 'Actual' }, { value: 'headroom', label: 'Headroom' },
  { value: 'result', label: 'Result' }, { value: 'atRisk', label: 'At risk' }, { value: 'status', label: 'Status' },
  { value: 'approval', label: 'Approval' }, { value: 'nextDueDate', label: 'Next due' },
  { value: 'overdueDays', label: 'Days overdue' },
];
const RESULTS = ['COMPLIANT', 'BREACHED', 'ERROR', 'PENDING_REVIEW', 'AT_RISK'];
const EMPTY: ReportDefinition = { columns: ['cpId', 'covenantName', 'result', 'value'], results: [], segment: null, cpFilter: null };

/** C11: build a report, run it, save it, download it as Excel or have it e-mailed on a schedule. */
export function CustomReports() {
  const qc = useQueryClient();
  const [def, setDef] = useState<ReportDefinition>(EMPTY);
  const [name, setName] = useState('');
  const [schedule, setSchedule] = useState<SavedReport['schedule']>('NONE');
  const [emailTo, setEmailTo] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const savedQuery = useQuery({ queryKey: ['saved-reports'], queryFn: r4.savedReports });
  const saved = { data: (Array.isArray(savedQuery.data) ? savedQuery.data : []).filter((r) => typeof r.schedule === 'string') };
  const run = useMutation({ mutationFn: () => r4.runReport(def) });
  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), definition: def, schedule, emailTo: emailTo.trim() || null };
      return editing ? r4.updateReport(editing, body) : r4.saveReport(body);
    },
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: `${r.name} saved.` });
      qc.invalidateQueries({ queryKey: ['saved-reports'] });
      setEditing(r.id);
    },
    onError: fail,
  });
  const remove = useMutation({
    mutationFn: (id: string) => r4.deleteReport(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['saved-reports'] }),
    onError: fail,
  });
  const load = (r: SavedReport) => {
    setDef(r.definition);
    setName(r.name);
    setSchedule(r.schedule);
    setEmailTo(r.emailTo ?? '');
    setEditing(r.id);
  };
  const columns = (def.columns.length ? COLUMNS.filter((c) => def.columns.includes(c.value)) : COLUMNS);
  return (
    <Card withBorder>
      <Stack>
        <Title order={4}>Build your own report</Title>
        <Group align="end" grow>
          <MultiSelect label="Columns" data={COLUMNS} value={def.columns} onChange={(v) => setDef({ ...def, columns: v })}
            placeholder="All columns" />
          <MultiSelect label="Only these results" data={RESULTS} value={def.results}
            onChange={(v) => setDef({ ...def, results: v })} placeholder="All results" />
        </Group>
        <Group align="end">
          <TextInput label="Business segment" value={def.segment ?? ''} placeholder="Any"
            onChange={(e) => setDef({ ...def, segment: e.currentTarget.value.toUpperCase() || null })} />
          <TextInput label="CP contains" value={def.cpFilter ?? ''} placeholder="Any"
            onChange={(e) => setDef({ ...def, cpFilter: e.currentTarget.value || null })} />
          <Button variant="light" onClick={() => run.mutate()} loading={run.isPending}>Run</Button>
        </Group>
        {run.error && <ErrorNote error={run.error} />}
        {run.data && (
          <Table withTableBorder striped>
            <Table.Thead><Table.Tr>{columns.map((c) => <Table.Th key={c.value}>{c.label}</Table.Th>)}</Table.Tr></Table.Thead>
            <Table.Tbody>
              {run.data.slice(0, 200).map((row, i) => (
                <Table.Tr key={i}>{columns.map((c) => <Table.Td key={c.value}>{String(row[c.value] ?? '')}</Table.Td>)}</Table.Tr>
              ))}
              {run.data.length === 0 && <Table.Tr><Table.Td colSpan={columns.length}><Text c="dimmed">No rows.</Text></Table.Td></Table.Tr>}
            </Table.Tbody>
          </Table>
        )}
        <Group align="end">
          <TextInput label="Report name" value={name} onChange={(e) => setName(e.currentTarget.value)} placeholder="Weekly breaches" />
          <Select label="E-mail it" w={140} value={schedule} allowDeselect={false}
            onChange={(v) => v && setSchedule(v as SavedReport['schedule'])}
            data={[{ value: 'NONE', label: 'Never' }, { value: 'DAILY', label: 'Daily' }, { value: 'WEEKLY', label: 'Weekly' },
              { value: 'MONTHLY', label: 'Monthly' }]} />
          {schedule !== 'NONE' && (
            <TextInput label="To (comma-separated)" w={260} value={emailTo} onChange={(e) => setEmailTo(e.currentTarget.value)}
              placeholder="head.credit@bank.com" />
          )}
          <Button onClick={() => save.mutate()} loading={save.isPending}
            disabled={!name.trim() || (schedule !== 'NONE' && !emailTo.trim())}>{editing ? 'Save changes' : 'Save report'}</Button>
          {editing && <Button variant="subtle" onClick={() => { setEditing(null); setName(''); setDef(EMPTY); setSchedule('NONE'); setEmailTo(''); }}>New</Button>}
        </Group>
        {(saved.data ?? []).length > 0 && (
          <Table>
            <Table.Thead><Table.Tr><Table.Th>Saved report</Table.Th><Table.Th>E-mailed</Table.Th><Table.Th>Last sent</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {(saved.data ?? []).map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td><Anchor component="button" onClick={() => load(r)}>{r.name}</Anchor></Table.Td>
                  <Table.Td>{r.schedule === 'NONE' ? 'never' : `${r.schedule.toLowerCase()} to ${r.emailTo}`}</Table.Td>
                  <Table.Td>{r.lastSentOn ?? '—'}</Table.Td>
                  <Table.Td>
                    <Group gap="xs">
                      <Button size="xs" variant="light" onClick={() => r4.reportXlsx(r.id, r.name).catch(fail)}>Excel</Button>
                      <Button size="xs" variant="subtle" color="red"
                        onClick={() => window.confirm(`Delete ${r.name}?`) && remove.mutate(r.id)}>Delete</Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
    </Card>
  );
}

// ---------------------------------------------------------------- C6 statement spread

const SECTION_LABEL: Record<SpreadLine['section'], string> = {
  PROFIT_AND_LOSS: 'Profit and loss', BALANCE_SHEET: 'Balance sheet', CASH_FLOW: 'Cash flow',
};

/** C6: the full spread of a statement; a reviewer corrects or fills lines and saves them for Core. */
export function SpreadReview({ documentId, canEdit }: { documentId: string; canEdit: boolean }) {
  const qc = useQueryClient();
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [showEmpty, setShowEmpty] = useState(false);
  const { data, isLoading, error } = useQuery({ queryKey: ['spread', documentId], queryFn: () => r4.spread(documentId) });
  const save = useMutation({
    mutationFn: () => r4.confirmSpread(documentId, Object.entries(edits)
      .filter(([, v]) => v.trim() !== '' && Number.isFinite(Number(v)))
      .map(([standardKey, v]) => ({ standardKey, value: Number(v) }))),
    onSuccess: (lines) => {
      qc.setQueryData(['spread', documentId], lines);
      qc.invalidateQueries({ queryKey: ['values', documentId] });
      notifications.show({ color: 'green', message: 'Corrections saved. Covenants using them are checked again.' });
      setEdits({});
    },
    onError: fail,
  });
  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const lines = Array.isArray(data) ? data : [];
  const found = lines.filter((l) => l.status !== 'UNMAPPED').length;
  const changed = Object.values(edits).some((v) => v.trim() !== '' && Number.isFinite(Number(v)));
  return (
    <Stack>
      <Group justify="space-between">
        <Text size="sm">{found} of {lines.length} standard lines found. Amounts in INR crore.</Text>
        <Group>
          <Checkbox label="Show lines not found" checked={showEmpty} onChange={(e) => setShowEmpty(e.currentTarget.checked)} />
          {canEdit && <Button size="xs" disabled={!changed} loading={save.isPending} onClick={() => save.mutate()}>Save corrections</Button>}
        </Group>
      </Group>
      {(['PROFIT_AND_LOSS', 'BALANCE_SHEET', 'CASH_FLOW'] as const).map((section) => {
        const rows = lines.filter((l) => l.section === section && (showEmpty || l.status !== 'UNMAPPED'));
        if (rows.length === 0) {
          return null;
        }
        return (
          <Table key={section} withTableBorder>
            <Table.Thead>
              <Table.Tr><Table.Th w="40%">{SECTION_LABEL[section]}</Table.Th><Table.Th>Value</Table.Th>
                <Table.Th>From</Table.Th><Table.Th>State</Table.Th></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((l) => (
                <Table.Tr key={l.standardKey}>
                  <Table.Td>{l.standardLabel}</Table.Td>
                  <Table.Td>
                    {canEdit ? (
                      <TextInput size="xs" w={140} aria-label={`Value for ${l.standardLabel}`}
                        value={edits[l.standardKey] ?? (l.value === null ? '' : String(l.value))}
                        onChange={(e) => setEdits({ ...edits, [l.standardKey]: e.currentTarget.value })} />
                    ) : (l.value ?? '—')}
                  </Table.Td>
                  <Table.Td><Text size="xs">{l.cell ?? (l.page ? `p. ${l.page}` : l.sourceLabel ?? '')}</Text></Table.Td>
                  <Table.Td>
                    <Badge size="xs" variant="light"
                      color={l.status === 'CONFIRMED' ? 'green' : l.status === 'MAPPED' ? (l.confidence < 1 ? 'yellow' : 'blue') : 'gray'}>
                      {l.status === 'CONFIRMED' ? 'checked by a person' : l.status === 'MAPPED'
                        ? (l.confidence < 1 ? 'worked out' : 'read') : 'not found'}
                    </Badge>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        );
      })}
    </Stack>
  );
}
