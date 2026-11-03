import {
  Badge, Button, Card, FileInput, Group, Modal, Select, Stack, Table, TagsInput, Text, TextInput, Textarea, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { dps } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import { r3 } from '../api/endpoints3';
import type { DocumentType, DpsDocument, ExtractedTerm } from '../api/types';
import { authHeaders, hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { CpPicker } from '../components/CpPicker';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';

const TYPES: { value: DocumentType; label: string }[] = [
  { value: 'LOAN_AGREEMENT', label: 'Loan agreement' },
  { value: 'FINANCIAL_STATEMENT', label: 'Financial statement' },
  { value: 'EVIDENCE', label: 'Evidence' },
];

function Upload({ cpId }: { cpId: string }) {
  const qc = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [type, setType] = useState<DocumentType>('LOAN_AGREEMENT');
  const [cpIds, setCpIds] = useState<string[]>([cpId]);
  const [asOf, setAsOf] = useState('');
  const upload = useMutation({
    mutationFn: () => dps.upload(file!, type, cpIds, type === 'FINANCIAL_STATEMENT' ? asOf : undefined),
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Uploaded. Processing has started.' });
      setFile(null);
      qc.invalidateQueries({ queryKey: ['documents', cpId] });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const needsDate = type === 'FINANCIAL_STATEMENT' && !asOf;
  return (
    <Card withBorder>
      <Stack>
        <Title order={4}>Upload to {cpIds.join(', ') || 'no CP'}</Title>
        {cpIds.some((c) => c !== cpId) && (
          <Text size="sm" c="orange">This file will also be linked to: {cpIds.filter((c) => c !== cpId).join(', ')}</Text>
        )}
        <Group grow align="end">
          <FileInput label="File (PDF, XLSX, CSV)" value={file} onChange={(f) => {
            setFile(f);
            // A spreadsheet can only be a financial statement; a loan agreement is always a PDF.
            if (f && /\.(csv|xlsx|xls)$/i.test(f.name)) {
              setType('FINANCIAL_STATEMENT');
            }
          }} accept=".pdf,.xlsx,.csv" placeholder="Choose a file" />
          <Select label="Type" data={TYPES} value={type} allowDeselect={false}
            onChange={(v) => v && setType(v as DocumentType)} />
          {type === 'FINANCIAL_STATEMENT' && (
            <TextInput type="date" label="Period end" value={asOf} onChange={(e) => setAsOf(e.currentTarget.value)} />
          )}
        </Group>
        <TagsInput label="Linked credit proposals" value={cpIds} onChange={setCpIds}
          description="One agreement can cover several CPs." />
        <Group justify="end">
          <Button onClick={() => upload.mutate()} loading={upload.isPending}
            disabled={!file || cpIds.length === 0 || needsDate}>Upload</Button>
        </Group>
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
  return (
    <Table.Tr>
      <Table.Td>{term.metricRaw}<Text size="xs" c="dimmed">{term.category}</Text></Table.Td>
      <Table.Td><Text ff="monospace" size="sm">{term.expression ?? '—'}</Text></Table.Td>
      <Table.Td>
        {term.source.page ? (
          <Button size="compact-xs" variant="subtle" onClick={() => setViewing(true)}>p. {term.source.page}</Button>
        ) : '—'}
        {viewing && term.source.page && (
          <SourcePage documentId={term.documentId} page={term.source.page} quote={term.source.quote}
            onClose={() => setViewing(false)} />
        )}
      </Table.Td>
      <Table.Td><Text size="xs" maw={320}>{term.source.quote}</Text></Table.Td>
      <Table.Td>{Math.round(term.confidence * 100)}%</Table.Td>
      <Table.Td>
        <StatusBadge value={term.status} />
        {term.issues.map((i) => <Badge key={i} size="xs" color="orange" variant="outline">{i}</Badge>)}
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
        <Modal opened={editing} onClose={() => setEditing(false)} title={`Edit ${term.metricRaw}`}>
          <Stack>
            <TextInput label="Rule (Core formula)" value={expression} ff="monospace"
              onChange={(e) => setExpression(e.currentTarget.value)} />
            <Textarea label="Note" value={note} onChange={(e) => setNote(e.currentTarget.value)} />
            <Button onClick={() => review.mutate('EDIT')} loading={review.isPending}>Save</Button>
          </Stack>
        </Modal>
      </Table.Td>
    </Table.Tr>
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

function DocumentDetail({ doc }: { doc: DpsDocument }) {
  const identity = useIdentity();
  const canReview = hasRole(identity, 'RM', 'APPROVER', 'ADMIN');
  const qc = useQueryClient();
  const terms = useQuery({
    queryKey: ['terms', doc.id], queryFn: () => dps.terms(doc.id), enabled: doc.type === 'LOAN_AGREEMENT',
  });
  const values = useQuery({
    queryKey: ['values', doc.id], queryFn: () => dps.values(doc.id), enabled: doc.type === 'FINANCIAL_STATEMENT',
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
      </Group>
      {job.data?.error && <Text c="red" size="sm">{job.data.error}</Text>}
      <DocumentLinks doc={doc} canEdit={canReview} />
      {doc.type === 'LOAN_AGREEMENT' && (terms.isLoading ? <Loading /> : (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Term</Table.Th><Table.Th>Rule</Table.Th><Table.Th>Page</Table.Th><Table.Th>Quote</Table.Th>
              <Table.Th>Confidence</Table.Th><Table.Th>Status</Table.Th><Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(terms.data ?? []).map((t) => <TermRow key={t.id} term={t} canReview={canReview} />)}
          </Table.Tbody>
        </Table>
      ))}
      {narrative.data && (
        <Card withBorder bg="var(--mantine-color-gray-light)">
          <Text size="sm">{narrative.data.text}</Text>
          <Text size="xs" c="dimmed">{narrative.data.advisory}</Text>
        </Card>
      )}
      {doc.type === 'FINANCIAL_STATEMENT' && (values.isLoading ? <Loading /> : (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr><Table.Th>Line item</Table.Th><Table.Th>Value</Table.Th><Table.Th>Unit</Table.Th><Table.Th>Derived</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(values.data ?? []).map((v) => (
              <Table.Tr key={`${v.key}-${v.asOfDate}`}>
                <Table.Td>{v.key}</Table.Td><Table.Td>{v.value}</Table.Td><Table.Td>{v.unit}</Table.Td>
                <Table.Td>{v.derived ? 'yes' : ''}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      ))}
    </Stack>
  );
}

/** FR-01/FR-05/FR-21: upload, processing status and extraction review for one CP's documents. */
export function Documents() {
  const { cpId } = useParams();
  const navigate = useNavigate();
  const [open, setOpen] = useState<DpsDocument | null>(null);
  const docs = useQuery({ queryKey: ['documents', cpId], queryFn: () => dps.documents(cpId!), enabled: !!cpId });
  return (
    <Stack>
      <Group justify="space-between" align="end">
        <Title order={2}>{cpId ? `Documents for ${cpId}` : 'Documents'}</Title>
        <CpPicker key={cpId} initial={cpId} onOpen={(cp) => navigate(`/documents/${encodeURIComponent(cp)}`)} />
      </Group>
      {/* Keyed by CP: switching CP must reset the card, or the old CP stays in "Linked credit proposals". */}
      {cpId && <Upload key={cpId} cpId={cpId} />}
      {docs.isLoading && cpId && <Loading />}
      {docs.error && <ErrorNote error={docs.error} />}
      {docs.data && (
        <Table withTableBorder highlightOnHover>
          <Table.Thead>
            <Table.Tr><Table.Th>File</Table.Th><Table.Th>Type</Table.Th><Table.Th>Period</Table.Th>
              <Table.Th>Status</Table.Th><Table.Th>Uploaded</Table.Th><Table.Th>CPs</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {docs.data.map((d) => (
              <Table.Tr key={d.id} onClick={() => setOpen(d)} style={{ cursor: 'pointer' }}>
                <Table.Td>{d.fileName}</Table.Td>
                <Table.Td>{TYPES.find((t) => t.value === d.type)?.label}</Table.Td>
                <Table.Td>{d.asOfDate ?? '—'}</Table.Td>
                <Table.Td><StatusBadge value={d.status} /></Table.Td>
                <Table.Td>{new Date(d.uploadedAt).toLocaleDateString()} · {d.uploadedBy}</Table.Td>
                <Table.Td>{d.cpIds.join(', ')}</Table.Td>
              </Table.Tr>
            ))}
            {docs.data.length === 0 && (
              <Table.Tr><Table.Td colSpan={6}><Text c="dimmed">No documents yet.</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      )}
      <Modal opened={!!open} onClose={() => setOpen(null)} size="90%" title="Document">
        {open && <DocumentDetail doc={open} />}
      </Modal>
    </Stack>
  );
}
