import {
  Button, Card, FileInput, Grid, Group, Modal, Select, Stack, Table, TagsInput, Text, TextInput, Textarea,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { dps } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import { r3 } from '../api/endpoints3';
import { AskAgreement, SpreadReview } from './Phase3';
import type { DocumentType, DpsDocument, ExtractedTerm } from '../api/types';
import { authHeaders, hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { Kpi, KpiStrip, PageHeader } from '../components/Board';
import { DocumentInbox } from './CpQueues';
import { CpChips, CpPicker } from '../components/CpPicker';
import { fmtDate } from '../format';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';
import { confidenceWords, explain, ruleWords, termName, unitWords } from '../explain';

const TYPES: { value: DocumentType; label: string }[] = [
  { value: 'LOAN_AGREEMENT', label: 'Loan agreement' },
  { value: 'FINANCIAL_STATEMENT', label: 'Financial statement' },
  { value: 'EVIDENCE', label: 'Evidence' },
];

function Upload({ cpId, agreements }: { cpId: string; agreements: DpsDocument[] }) {
  const qc = useQueryClient();
  const [amends, setAmends] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [type, setType] = useState<DocumentType>('LOAN_AGREEMENT');
  const [cpIds, setCpIds] = useState<string[]>([cpId]);
  const [asOf, setAsOf] = useState('');
  const upload = useMutation({
    mutationFn: () => dps.upload(file!, type, cpIds, type === 'FINANCIAL_STATEMENT' ? asOf : undefined,
      type === 'LOAN_AGREEMENT' ? amends : null),
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Uploaded. Processing has started.' });
      setFile(null);
      qc.invalidateQueries({ queryKey: ['documents', cpId] });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const needsDate = type === 'FINANCIAL_STATEMENT' && !asOf;
  return (
    <Card>
      <Stack gap="md">
        <Stack gap={2}>
          <Title order={4}>Upload to {cpIds.join(', ') || 'no CP'}</Title>
          <Text size="sm" c="dimmed">The file is read in the background; it shows in the list at once.</Text>
        </Stack>
        {cpIds.some((c) => c !== cpId) && (
          <Text size="sm" c="var(--text-warn)">This file will also be linked to: {cpIds.filter((c) => c !== cpId).join(', ')}</Text>
        )}
        <Stack gap="sm">
          <FileInput label="File (PDF, XLSX, XLS, CSV)" value={file} onChange={(f) => {
            setFile(f);
            // A spreadsheet can only be a financial statement; a loan agreement is always a PDF.
            if (f && /\.(csv|xlsx|xls)$/i.test(f.name)) {
              setType('FINANCIAL_STATEMENT');
            }
          }} accept=".pdf,.xlsx,.xls,.csv" placeholder="Choose a file" />
          <Select label="Type" data={TYPES} value={type} allowDeselect={false}
            onChange={(v) => v && setType(v as DocumentType)} />
          {type === 'FINANCIAL_STATEMENT' && (
            <TextInput type="date" label="Period end" value={asOf} onChange={(e) => setAsOf(e.currentTarget.value)} />
          )}
          {type === 'LOAN_AGREEMENT' && agreements.length > 0 && (
            <Select label="Amendment of (optional)" clearable value={amends} onChange={setAmends}
              placeholder="A new agreement" data={agreements.map((a) => ({ value: a.id, label: a.fileName }))} />
          )}
        </Stack>
        <TagsInput label="Linked credit proposals" value={cpIds} onChange={setCpIds}
          description="One agreement can cover several CPs." />
        <Button onClick={() => upload.mutate()} loading={upload.isPending}
          disabled={!file || cpIds.length === 0 || needsDate}>Upload</Button>
      </Stack>
    </Card>
  );
}

/** FR-05: the agreement opened at the cited page (browser PDF viewer), with the quote to look for. */
export function SourcePage({ documentId, page, quote, onClose }: {
  documentId: string; page: number; quote: string | null; onClose: () => void;
}) {
  const file = useQuery({
    queryKey: ['file', documentId],
    queryFn: async () => {
      const res = await fetch(`/dps/v1/documents/${encodeURIComponent(documentId)}/file`, { headers: authHeaders() });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      return URL.createObjectURL(await res.blob());
    },
    staleTime: Infinity,
  });
  return (
    <Modal opened onClose={onClose} size="xl" title={`Page ${page}`}>
      <Stack>
        {quote && <Text size="sm" bg="yellow.1" p="xs">“{quote}”</Text>}
        {file.isLoading && <Loading />}
        {file.error && <ErrorNote error={file.error} />}
        {file.data && <iframe title="Source page" src={`${file.data}#page=${page}`} style={{ width: '100%', height: '70vh', border: 0 }} />}
      </Stack>
    </Modal>
  );
}

const KIND: Record<string, string> = {
  FINANCIAL: 'Financial covenant',
  NON_FINANCIAL: 'Non-financial covenant',
  INFO: 'Agreement fact (not a covenant)',
};

function TermRow({ term, canReview }: { term: ExtractedTerm; canReview: boolean }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [viewing, setViewing] = useState(false);
  const [expression, setExpression] = useState(term.expression ?? '');
  const [note, setNote] = useState('');
  const review = useMutation({
    mutationFn: (action: 'APPROVE' | 'REJECT' | 'EDIT') =>
      dps.reviewTerm(term.id, { action, note: note || undefined, ...(action === 'EDIT' ? { expression } : {}) }),
    onSuccess: () => {
      setEditing(false);
      qc.invalidateQueries({ queryKey: ['terms', term.documentId] });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const rule = ruleWords(term);
  const confidence = confidenceWords(term.confidence);
  const manual = term.category === 'FINANCIAL' && !term.expression;
  return (
    <Table.Tr>
      <Table.Td>
        <Text fw={550} size="sm">{termName(term)}</Text>
        <Text size="xs" c="dimmed">{KIND[term.category] ?? term.category}</Text>
      </Table.Td>
      <Table.Td>
        {term.category === 'INFO' ? <Text size="sm">{term.expression ?? '—'}</Text> : (
          <Text size="sm" fw={rule ? 550 : 400}>{rule ?? '—'}</Text>
        )}
        {term.frequency && <Text size="xs" c="dimmed">tested {term.frequency.toLowerCase()}</Text>}
      </Table.Td>
      <Table.Td>
        {term.category !== 'FINANCIAL' ? <Text size="xs" c="dimmed">—</Text> : manual ? (
          <Text size="xs">A person enters the figure each period (no statement line gives it).</Text>
        ) : (
          <>
            <Text size="xs">Worked out from the statements</Text>
            <Text ff="monospace" size="xs" c="dimmed" style={{ wordBreak: 'break-word' }}>{term.expression}</Text>
          </>
        )}
      </Table.Td>
      <Table.Td>
        {term.source.page ? (
          <Button size="compact-xs" variant="subtle" onClick={() => setViewing(true)}>Open page {term.source.page}</Button>
        ) : '—'}
        {viewing && term.source.page && (
          <SourcePage documentId={term.documentId} page={term.source.page} quote={term.source.quote}
            onClose={() => setViewing(false)} />
        )}
        <Text size="xs" maw={300} lineClamp={3} title={term.source.quote ?? undefined}>{term.source.quote}</Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm" c={confidence.tone === 'good' ? undefined : `var(--text-${confidence.tone})`}>{confidence.label}</Text>
      </Table.Td>
      <Table.Td maw={340}>
        <StatusBadge value={term.status} />
        {term.issues.map((i) => {
          const e = explain(i);
          return (
            <Stack key={i} gap={0} mt={4}>
              <Text size="xs" c="var(--text-warn)">{e.what}</Text>
              <Text size="xs" c="dimmed">{e.todo}</Text>
            </Stack>
          );
        })}
      </Table.Td>
      <Table.Td>
        {canReview && term.status !== 'REJECTED' && (
          <Group gap={4}>
            {term.status !== 'PUBLISHED' && (
              <Button size="xs" color="green" onClick={() => review.mutate('APPROVE')}>Approve</Button>
            )}
            <Button size="xs" variant="light" onClick={() => setEditing(true)}>Edit</Button>
            <Button size="xs" color="red" variant="subtle" onClick={() => review.mutate('REJECT')}>Reject</Button>
          </Group>
        )}
        <Modal opened={editing} onClose={() => setEditing(false)} title={`Edit ${termName(term)}`}>
          <Stack>
            {term.source.quote && <Text size="sm" bg="yellow.1" p="xs">“{term.source.quote}”</Text>}
            <TextInput label="Rule (Core formula)" value={expression} ff="monospace"
              description="Statement figures are DPS_ names, for example DPS_TOTAL_DEBT / DPS_EBITDA <= 3.5"
              onChange={(e) => setExpression(e.currentTarget.value)} />
            <Textarea label="Note" value={note} onChange={(e) => setNote(e.currentTarget.value)} />
            <Button onClick={() => review.mutate('EDIT')} loading={review.isPending}>Save</Button>
          </Stack>
        </Modal>
      </Table.Td>
    </Table.Tr>
  );
}

const reviewFirst = (t: ExtractedTerm) => (t.status === 'NEEDS_REVIEW' ? 0 : t.status === 'REJECTED' ? 2 : 1);

/** One line above the covenants: how many were found, how many wait for a person, how many are manual. */
function TermSummary({ terms }: { terms: ExtractedTerm[] }) {
  const covenants = terms.filter((t) => t.category !== 'INFO' && t.status !== 'REJECTED');
  if (terms.length === 0) {
    return <Text size="sm" c="dimmed">No covenants were found. Check the agreement and add them on the CP if it has any.</Text>;
  }
  const waiting = covenants.filter((t) => t.status === 'NEEDS_REVIEW').length;
  const manual = covenants.filter((t) => t.category === 'FINANCIAL' && !t.expression).length;
  return (
    <Text size="sm">
      {covenants.length} covenant{covenants.length === 1 ? '' : 's'} proposed
      {waiting ? `, ${waiting} need${waiting === 1 ? 's' : ''} your check (reasons on each row)` : ', all ready'}
      {manual ? `; ${manual} will be entered by a person each period` : ''}. Approved covenants go to the CP's
      onboarding, where they are applied.
    </Text>
  );
}

/** Flow B: why a statement waits for a person, and the unit it was printed in. */
function StatementReview({ doc }: { doc: DpsDocument }) {
  const unit = unitWords(doc.review?.unitScale);
  const issues = doc.review?.issues ?? [];
  if (!unit && issues.length === 0) {
    return null;
  }
  return (
    <Card withBorder>
      <Stack gap={6}>
        {unit && (
          <Text size="sm">Printed in <b>{unit}</b>; every figure below is shown in INR crore.</Text>
        )}
        {issues.length > 0 && (
          <>
            <Text fw={600} size="sm">{doc.status === 'NEEDS_REVIEW' ? 'Why this statement waits for your check'
              : 'Checked by a person; what was flagged'}</Text>
            {issues.map((i) => {
              const e = explain(i);
              return (
                <Stack key={i} gap={0}>
                  <Text size="sm" c="var(--text-warn)">{e.what}</Text>
                  <Text size="xs" c="dimmed">{e.todo}</Text>
                </Stack>
              );
            })}
            {doc.status === 'NEEDS_REVIEW' && (
              <Text size="xs" c="dimmed">Correct any figure below and save, then press Review done to report the figures.</Text>
            )}
          </>
        )}
      </Stack>
    </Card>
  );
}

/** A10: which CPs this document applies to; one group agreement can cover several loans. */
function DocumentLinks({ doc, canEdit }: { doc: DpsDocument; canEdit: boolean }) {
  const qc = useQueryClient();
  const [saved, setSaved] = useState(doc.cpIds);
  const [cps, setCps] = useState(doc.cpIds);
  const added = cps.filter((c) => !saved.includes(c));
  const removed = saved.filter((c) => !cps.includes(c));
  const save = useMutation({
    mutationFn: async () => {
      if (added.length > 0) {
        await r3.linkDocument(doc.id, added);
      }
      if (removed.length > 0) {
        await r3.unlinkDocument(doc.id, removed);
      }
    },
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Linked CPs saved.' });
      qc.invalidateQueries({ queryKey: ['documents'] });
      setSaved(cps);
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Group align="end">
      <TagsInput label="Applies to CPs" value={cps} onChange={(v) => setCps(v.map((c) => c.trim().toUpperCase()))}
        disabled={!canEdit} w={420} description="Type a CP number and press Enter to add it." />
      {canEdit && (
        <Button variant="light" onClick={() => save.mutate()} loading={save.isPending}
          disabled={cps.length === 0 || (added.length === 0 && removed.length === 0)}>Save links</Button>
      )}
    </Group>
  );
}

/** B3: take a wrong upload out of use (it is kept, marked REMOVED), or swap it for the right file. */
function RemoveOrReplace({ doc, onDone }: { doc: DpsDocument; onDone: () => void }) {
  const qc = useQueryClient();
  const [reason, setReason] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [open, setOpen] = useState(false);
  const run = useMutation({
    mutationFn: async () => {
      const why = reason.trim() || (file ? `Replaced by ${file.name}` : '');
      await r3.removeDocument(doc.id, why);
      if (file) {
        await dps.upload(file, doc.type, doc.cpIds, doc.asOfDate ?? undefined, doc.amendsDocumentId ?? null);
      }
    },
    onSuccess: () => {
      notifications.show({ color: 'green',
        message: file ? 'Replaced. The new file is being read.' : 'Removed. Its figures no longer count.' });
      qc.invalidateQueries({ queryKey: ['documents'] });
      onDone();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  if (doc.status === 'REMOVED') {
    return null;
  }
  return (
    <>
      <Button size="xs" variant="light" color="red" onClick={() => setOpen(true)}>Remove or replace</Button>
      <Modal opened={open} onClose={() => setOpen(false)} title={`Remove or replace ${doc.fileName}`}>
        <Stack>
          <Text size="sm">
            The file is kept for the record but marked REMOVED. Figures read from it stop counting (an earlier
            upload for the same period counts again), and the covenants that used them are checked again.
          </Text>
          <FileInput label="Replace with (optional)" value={file} onChange={setFile} accept=".pdf,.xlsx,.xls,.csv"
            placeholder="Choose the right file" clearable />
          <Textarea label="Why" value={reason} onChange={(e) => setReason(e.currentTarget.value)}
            placeholder="e.g. Uploaded to the wrong CP" required={!file} />
          <Group justify="end">
            <Button variant="default" onClick={() => setOpen(false)}>Cancel</Button>
            <Button color="red" loading={run.isPending} disabled={!reason.trim() && !file}
              onClick={() => run.mutate()}>{file ? 'Replace' : 'Remove'}</Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

/** C7: how an amendment changes the covenants of the agreement it amends, matched by covenant. */
function AmendmentChanges({ doc, terms }: { doc: DpsDocument; terms: ExtractedTerm[] }) {
  const original = useQuery({
    queryKey: ['terms', doc.amendsDocumentId], queryFn: () => dps.terms(doc.amendsDocumentId!),
    enabled: !!doc.amendsDocumentId,
  });
  if (!doc.amendsDocumentId || !original.data) {
    return null;
  }
  const key = (t: ExtractedTerm) => t.covenantMasterId ?? t.metricRaw.toLowerCase();
  const before = new Map(original.data.filter((t) => t.status !== 'REJECTED').map((t) => [key(t), t]));
  const rows = terms.filter((t) => t.status !== 'REJECTED').map((t) => ({ name: termName(t), old: before.get(key(t))?.expression ?? null,
    now: t.expression })).filter((r) => r.old !== r.now);
  return (
    <Card withBorder>
      <Text fw={600} mb="xs">Changes made by this amendment</Text>
      {rows.length === 0 ? <Text size="sm" c="dimmed">No covenant terms changed.</Text> : (
        <Table>
          <Table.Tbody>
            {rows.map((r) => (
              <Table.Tr key={r.name}>
                <Table.Td>{r.name}</Table.Td>
                <Table.Td><Text ff="monospace" size="sm">{r.old ?? 'new'} → {r.now ?? '—'}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Text size="xs" c="dimmed" mt="xs">
        To use a changed rule, open the covenant on the CP workspace, Schedule and changes, and save the new rule.
      </Text>
    </Card>
  );
}

function DocumentDetail({ doc, onClose }: { doc: DpsDocument; onClose: () => void }) {
  const [cited, setCited] = useState<{ page: number; quote: string } | null>(null);
  const identity = useIdentity();
  const canReview = hasRole(identity, 'RM', 'APPROVER', 'ADMIN');
  const qc = useQueryClient();
  const terms = useQuery({
    queryKey: ['terms', doc.id], queryFn: () => dps.terms(doc.id), enabled: doc.type === 'LOAN_AGREEMENT',
  });
  const narrative = useQuery({
    queryKey: ['narrative', doc.id], queryFn: () => r2.narrative(doc.id),
    enabled: doc.type === 'FINANCIAL_STATEMENT' && doc.status === 'PUBLISHED',
  });
  const job = useQuery({
    queryKey: ['job', doc.latestJobId], queryFn: () => dps.job(doc.latestJobId!), enabled: !!doc.latestJobId,
    refetchInterval: (q) => (['QUEUED', 'RUNNING'].includes(q.state.data?.status ?? '') ? 3000 : false),
  });
  const resume = useMutation({
    mutationFn: () => dps.resumeJob(doc.latestJobId!),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['job', doc.latestJobId] }),
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Stack>
      <Group>
        <Text fw={600}>{doc.fileName}</Text>
        <StatusBadge value={doc.status} />
        {job.data && <Text size="sm" c="dimmed">Job {job.data.status} · step {job.data.currentStep ?? '—'}</Text>}
        {job.data?.status === 'NEEDS_REVIEW' && canReview && (
          <Button size="xs" onClick={() => resume.mutate()} loading={resume.isPending}>Review done</Button>
        )}
        {canReview && <RemoveOrReplace doc={doc} onDone={onClose} />}
      </Group>
      {doc.amendsDocumentId && <Text size="sm" c="dimmed">This is an amendment of an earlier agreement.</Text>}
      {terms.data && <AmendmentChanges doc={doc} terms={terms.data} />}
      {doc.type === 'LOAN_AGREEMENT' && doc.status !== 'REMOVED' && (
        <AskAgreement documentId={doc.id} onPage={(page, quote) => setCited({ page, quote })} />
      )}
      {cited && <SourcePage documentId={doc.id} page={cited.page} quote={cited.quote} onClose={() => setCited(null)} />}
      {job.data?.error && <Text c="var(--text-bad)" size="sm">{job.data.error}</Text>}
      {doc.failureReason && !job.data?.error && <Text c="var(--text-bad)" size="sm">{doc.failureReason}</Text>}
      <DocumentLinks doc={doc} canEdit={canReview} />
      {doc.type === 'LOAN_AGREEMENT' && (terms.isLoading ? <Loading /> : (
        <Stack gap="xs">
          <TermSummary terms={terms.data ?? []} />
          <Table.ScrollContainer minWidth={900}>
            <Table withTableBorder verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Covenant</Table.Th><Table.Th>Limit</Table.Th><Table.Th>How it is tested</Table.Th>
                  <Table.Th>Where in the agreement</Table.Th><Table.Th>Confidence</Table.Th>
                  <Table.Th>Status and what to check</Table.Th><Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {[...(terms.data ?? [])].sort((a, b) => reviewFirst(a) - reviewFirst(b))
                  .map((t) => <TermRow key={t.id} term={t} canReview={canReview} />)}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Stack>
      ))}
      {doc.type === 'FINANCIAL_STATEMENT' && <StatementReview doc={doc} />}
      {narrative.data && (
        <Card withBorder bg="var(--mantine-color-gray-light)">
          <Text size="sm">{narrative.data.text}</Text>
          <Text size="xs" c="dimmed">{narrative.data.advisory}</Text>
        </Card>
      )}
      {doc.type === 'FINANCIAL_STATEMENT' && doc.status !== 'QUEUED' && (
        <SpreadReview documentId={doc.id} canEdit={canReview && doc.status !== 'REMOVED'}
          flagged={new Set((doc.review?.issues ?? []).map((i) => explain(i).key).filter((k): k is string => !!k))}
          onPage={(page, label) => setCited({ page, quote: label })} />
      )}
    </Stack>
  );
}

const WAITING = new Set(['QUEUED', 'RUNNING', 'PROCESSING']);

/** FR-01/FR-05/FR-21: upload, processing status and extraction review for one CP's documents. */
export function Documents() {
  const { cpId } = useParams();
  const navigate = useNavigate();
  const [open, setOpen] = useState<DpsDocument | null>(null);
  const docs = useQuery({ queryKey: ['documents', cpId], queryFn: () => dps.documents(cpId!), enabled: !!cpId });
  const canUpload = hasRole(useIdentity(), 'RM', 'APPROVER', 'ADMIN');
  const [q, setQ] = useState('');
  const [type, setType] = useState<'ALL' | 'REVIEW' | DocumentType>('ALL');
  const [showRemoved, setShowRemoved] = useState(false);
  const all = docs.data ?? [];
  const live = all.filter((d) => d.status !== 'REMOVED');
  const removed = all.length - live.length;
  const ofType = (t: DocumentType) => live.filter((d) => d.type === t);
  const review = live.filter((d) => d.status === 'NEEDS_REVIEW' || d.status === 'FAILED');
  const reading = live.filter((d) => WAITING.has(d.status)).length;
  const latest = ofType('FINANCIAL_STATEMENT').map((d) => d.asOfDate).filter((d): d is string => !!d).sort().pop();
  const pool = showRemoved ? all : live;
  // Files to review first, then the newest upload.
  const rank = (d: DpsDocument) => (review.includes(d) ? 0 : d.status === 'REMOVED' ? 2 : 1);
  const shown = pool.filter((d) => (type === 'ALL' || (type === 'REVIEW' ? review.includes(d) : d.type === type))
    && (!q.trim() || d.fileName.toLowerCase().includes(q.trim().toLowerCase())))
    .sort((a, b) => rank(a) - rank(b) || b.uploadedAt.localeCompare(a.uploadedAt));
  const pick = (t: typeof type) => setType((v) => (v === t ? 'ALL' : t));
  const go = (cp: string) => navigate(`/documents/${encodeURIComponent(cp)}`);
  let finding: string | undefined;
  if (!cpId) {
    finding = 'Loan agreements, financial statements and evidence across your credit proposals. Files that need a person come first.';
  } else if (docs.data) {
    finding = live.length === 0 ? 'No documents yet. Upload the loan agreement first; its covenants are read for onboarding.'
      : `${live.length} document${live.length === 1 ? '' : 's'} in use`
        + (review.length ? `, ${review.length} waiting for your review` : ', none waiting for review')
        + (reading ? `, ${reading} still being read` : '')
        + (latest ? `. Latest statement period ends ${fmtDate(latest)}.` : '.');
  }
  return (
    <Stack gap="lg">
      <PageHeader title={cpId ? `Documents for ${cpId}` : 'Documents'} finding={finding}
        chips={cpId ? <CpChips cpId={cpId} /> : undefined}
        actions={cpId ? <CpPicker key={cpId} initial={cpId} onOpen={go} /> : undefined} />
      {!cpId && <DocumentInbox onOpen={go} />}
      {cpId && docs.isLoading && <Loading />}
      {docs.error && <ErrorNote error={docs.error} />}
      {cpId && docs.data && live.length > 0 && (
        <KpiStrip ariaLabel="Documents by type">
          <Kpi label="Documents in use" value={live.length} onClick={() => setType('ALL')} selected={type === 'ALL'}
            ariaLabel="Show documents: All" note={removed ? `${removed} removed` : 'None removed'} />
          <Kpi label="Loan agreements" value={ofType('LOAN_AGREEMENT').length} onClick={() => pick('LOAN_AGREEMENT')}
            selected={type === 'LOAN_AGREEMENT'} ariaLabel="Show documents: Loan agreements" note="Covenants are read from these" />
          <Kpi label="Financial statements" value={ofType('FINANCIAL_STATEMENT').length} onClick={() => pick('FINANCIAL_STATEMENT')}
            selected={type === 'FINANCIAL_STATEMENT'} ariaLabel="Show documents: Financial statements"
            note={latest ? `Latest period ${fmtDate(latest)}` : 'No period yet'} />
          <Kpi label="Evidence" value={ofType('EVIDENCE').length} onClick={() => pick('EVIDENCE')} selected={type === 'EVIDENCE'}
            ariaLabel="Show documents: Evidence" note="Certificates and other proof" />
          <Kpi label="Waiting for review" value={review.length} lead tone={review.length ? 'warn' : 'neutral'}
            onClick={() => pick('REVIEW')} selected={type === 'REVIEW'} ariaLabel="Show documents: Waiting for review"
            note={review.length ? 'Check what was read, then approve' : 'Nothing to check'} />
        </KpiStrip>
      )}
      {cpId && docs.data && (
        <Grid gutter="lg" align="flex-start">
          <Grid.Col span={{ base: 12, lg: canUpload ? 8 : 12 }}>
            <Card padding={0}>
              <Group justify="space-between" align="flex-end" p="lg" pb="md" wrap="wrap">
                <Stack gap={2}>
                  <Title order={4}>Files</Title>
                  <Text size="sm" c="dimmed">Click a file to see what was read from it.</Text>
                </Stack>
                {all.length > 0 && (
                  <Group gap="sm">
                    <TextInput placeholder="Search file name" aria-label="Search documents" value={q} w={220}
                      onChange={(e) => setQ(e.currentTarget.value)} />
                    {removed > 0 && (
                      <Button variant="light" size="xs" onClick={() => setShowRemoved((v) => !v)}>
                        {showRemoved ? 'Hide removed' : `Show removed (${removed})`}
                      </Button>
                    )}
                  </Group>
                )}
              </Group>
              <Table.ScrollContainer minWidth={600}>
                <Table highlightOnHover>
                  <Table.Thead>
                    <Table.Tr><Table.Th>File</Table.Th><Table.Th>Type</Table.Th><Table.Th>Period</Table.Th>
                      <Table.Th>Status</Table.Th><Table.Th>Uploaded</Table.Th></Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {shown.map((d) => (
                      <Table.Tr key={d.id} onClick={() => setOpen(d)} style={{ cursor: 'pointer' }}>
                        <Table.Td fw={550} style={{ wordBreak: 'break-word' }}>{d.fileName}
                          {d.cpIds.some((x) => x !== cpId) && (
                            <Text size="xs" c="dimmed" fw={400}>Also {d.cpIds.filter((x) => x !== cpId).join(', ')}</Text>
                          )}</Table.Td>
                        <Table.Td c="dimmed" style={{ whiteSpace: 'nowrap' }}>{TYPES.find((t) => t.value === d.type)?.label}</Table.Td>
                        <Table.Td style={{ whiteSpace: 'nowrap' }}>{fmtDate(d.asOfDate)}</Table.Td>
                        <Table.Td style={{ whiteSpace: 'nowrap' }}><StatusBadge value={d.status} /></Table.Td>
                        <Table.Td style={{ whiteSpace: 'nowrap' }}>{fmtDate(d.uploadedAt)}
                          <Text size="xs" c="dimmed">{d.uploadedBy}</Text></Table.Td>
                      </Table.Tr>
                    ))}
                    {all.length === 0 && (
                      <Table.Tr><Table.Td colSpan={5}><Text c="dimmed" size="sm">No documents yet.</Text></Table.Td></Table.Tr>
                    )}
                    {all.length > 0 && shown.length === 0 && (
                      <Table.Tr><Table.Td colSpan={5}><Text c="dimmed" size="sm">No document matches.</Text></Table.Td></Table.Tr>
                    )}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </Card>
          </Grid.Col>
          {/* Keyed by CP: switching CP must reset the card, or the old CP stays in "Linked credit proposals". */}
          {canUpload && (
            <Grid.Col span={{ base: 12, lg: 4 }}>
              <Upload key={cpId} cpId={cpId} agreements={live.filter((d) => d.type === 'LOAN_AGREEMENT')} />
            </Grid.Col>
          )}
        </Grid>
      )}
      <Modal opened={!!open} onClose={() => setOpen(null)} size="90%" title="Document">
        {open && <DocumentDetail doc={open} onClose={() => setOpen(null)} />}
      </Modal>
    </Stack>
  );
}
