import {
  Badge, Button, FileInput, Group, NumberInput, Select, Stack, Table, Text, TextInput, Textarea, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { dps } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import { r3 } from '../api/endpoints3';
import { hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { ErrorNote, Loading, StatusBadge } from '../components/Status';
import { formatValue } from '../format';

const fail = (e: unknown) => notifications.show({ color: 'red', message: errorMessage(e) });

/** FR-22: certificate requirements per period, with upload against a period. */
export function Certificates({ cpId }: { cpId: string }) {
  const qc = useQueryClient();
  const reqs = useQuery({ queryKey: ['requirements', cpId], queryFn: () => r2.requirements(cpId) });
  const [periodEnd, setPeriodEnd] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [uploadPeriod, setUploadPeriod] = useState<string | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['requirements', cpId] });
  const add = useMutation({
    mutationFn: () => r2.saveRequirement(cpId, { periodEnd, dueDate }),
    onSuccess: () => { setPeriodEnd(''); setDueDate(''); refresh(); },
    onError: fail,
  });
  const removeRequirement = useMutation({ mutationFn: (id: string) => r3.deleteRequirement(cpId, id),
    onSuccess: refresh, onError: fail });
  const removeCertificate = useMutation({ mutationFn: (id: string) => r3.deleteCertificate(cpId, id),
    onSuccess: refresh, onError: fail });
  const upload = useMutation({
    mutationFn: () => r2.uploadCertificate(cpId, file!, uploadPeriod ?? undefined),
    onSuccess: () => { setFile(null); refresh(); notifications.show({ color: 'green', message: 'Certificate uploaded.' }); },
    onError: fail,
  });
  if (reqs.isLoading) {
    return <Loading />;
  }
  if (reqs.error) {
    return <ErrorNote error={reqs.error} />;
  }
  return (
    <Stack>
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr><Table.Th>Period end</Table.Th><Table.Th>Due</Table.Th><Table.Th>State</Table.Th>
            <Table.Th>Files</Table.Th><Table.Th /></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(reqs.data ?? []).map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>{r.periodEnd}</Table.Td>
              <Table.Td>{r.dueDate}{r.overdueDays > 0 && <Text size="xs" c="red">{r.overdueDays} d overdue</Text>}</Table.Td>
              <Table.Td><StatusBadge value={r.state === 'RECEIVED' ? 'COMPLIED' : r.state === 'OVERDUE' ? 'BREACHED' : 'QUEUED'} />
                <Text size="xs">{r.state}</Text></Table.Td>
              <Table.Td>
                {r.certificates.length === 0 && '—'}
                {r.certificates.map((c) => (
                  <Group key={c.id} gap={4} wrap="nowrap">
                    <Text size="sm">{c.fileName}</Text>
                    <Button size="compact-xs" variant="subtle" color="red" aria-label={`Delete ${c.fileName}`}
                      onClick={() => window.confirm(`Delete certificate ${c.fileName}?`) && removeCertificate.mutate(c.id)}>
                      Delete</Button>
                  </Group>
                ))}
              </Table.Td>
              <Table.Td>
                <Button size="xs" variant="subtle" color="red" loading={removeRequirement.isPending}
                  onClick={() => window.confirm(`Delete the requirement for period ${r.periodEnd}?`)
                    && removeRequirement.mutate(r.id)}>Delete</Button>
              </Table.Td>
            </Table.Tr>
          ))}
          {(reqs.data ?? []).length === 0 && (
            <Table.Tr><Table.Td colSpan={5}><Text c="dimmed">No certificate requirements.</Text></Table.Td></Table.Tr>
          )}
        </Table.Tbody>
      </Table>
      <Group align="end">
        <TextInput type="date" label="Require period end" value={periodEnd} onChange={(e) => setPeriodEnd(e.currentTarget.value)} />
        <TextInput type="date" label="Due by" value={dueDate} onChange={(e) => setDueDate(e.currentTarget.value)} />
        <Button onClick={() => add.mutate()} disabled={!periodEnd || !dueDate} loading={add.isPending}>Add requirement</Button>
      </Group>
      <Group align="end">
        <FileInput label="Certificate file" value={file} onChange={setFile} w={260} />
        <Select label="For period" placeholder="General" clearable value={uploadPeriod} onChange={setUploadPeriod}
          data={(reqs.data ?? []).map((r) => r.periodEnd)} />
        <Button onClick={() => upload.mutate()} disabled={!file} loading={upload.isPending}>Upload</Button>
      </Group>
    </Stack>
  );
}

const TREND_COLOR = { IMPROVING: 'green', STABLE: 'gray', WORSENING: 'red' } as const;

/** FR-24: covenants across the last assessment dates, with Excel export. */
export function Comparison({ cpId }: { cpId: string }) {
  const [periods, setPeriods] = useState(4);
  const q = useQuery({ queryKey: ['comparison', cpId, periods], queryFn: () => r2.comparison(cpId, periods) });
  if (q.isLoading) {
    return <Loading />;
  }
  if (q.error) {
    return <ErrorNote error={q.error} />;
  }
  const data = q.data!;
  return (
    <Stack>
      <Group align="end">
        <NumberInput label="Periods" min={1} max={24} value={periods} onChange={(v) => setPeriods(Number(v) || 1)} w={120} />
        <Button variant="light" onClick={() => r2.comparisonXlsx(cpId).catch(fail)}>Download Excel</Button>
      </Group>
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr><Table.Th>Covenant</Table.Th><Table.Th>Trend (advisory)</Table.Th>{data.periods.map((p) => <Table.Th key={p}>{p}</Table.Th>)}</Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {data.rows.map((r) => (
            <Table.Tr key={r.appliedCovenantId}>
              <Table.Td>{r.covenantName}</Table.Td>
              <Table.Td>
                {r.trend && <Badge variant="light" color={TREND_COLOR[r.trend]}>{r.trend.toLowerCase()}</Badge>}
              </Table.Td>
              {r.cells.map((c, i) => (
                <Table.Td key={data.periods[i]}>
                  {c ? <><StatusBadge value={c.result} /><Text size="xs">{formatValue(c.computedValue)}</Text></> : '—'}
                </Table.Td>
              ))}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

const KEYS = ['EBITDA', 'INTEREST_PAID', 'PRINCIPAL_PAID', 'EMI_AMOUNT', 'TOTAL_DEBT', 'NET_WORTH', 'CASH',
  'CURRENT_ASSETS', 'CURRENT_LIABILITIES', 'REVENUE', 'PAT'];

/** FR-20: an approver enters a value by hand (with a note); DPS notifies Core to re-assess. */
export function ManualValue({ cpId }: { cpId: string }) {
  const identity = useIdentity();
  const [key, setKey] = useState<string | null>('EBITDA');
  const [value, setValue] = useState<number | string>('');
  const [asOf, setAsOf] = useState('');
  const [note, setNote] = useState('');
  const save = useMutation({
    mutationFn: () => dps.manualValue(cpId, { asOfDate: asOf, key: key!, value: Number(value), unit: 'INR_CR', note }),
    onSuccess: () => {
      notifications.show({ color: 'green', message: 'Value saved; covenants using it will be re-assessed.' });
      setValue(''); setNote('');
    },
    onError: fail,
  });
  if (!hasRole(identity, 'APPROVER', 'ADMIN')) {
    return <Text c="dimmed">Only an approver can enter values by hand.</Text>;
  }
  return (
    <Stack maw={420}>
      <Title order={5}>Enter a value (INR crore)</Title>
      <Select label="Line item" data={KEYS} value={key} onChange={setKey} allowDeselect={false} />
      <NumberInput label="Value" value={value} onChange={setValue} />
      <TextInput type="date" label="Period end" value={asOf} onChange={(e) => setAsOf(e.currentTarget.value)} />
      <Textarea label="Why (required)" value={note} onChange={(e) => setNote(e.currentTarget.value)} />
      <Button onClick={() => save.mutate()} loading={save.isPending}
        disabled={!key || value === '' || !asOf || note.trim().length < 3}>Save value</Button>
    </Stack>
  );
}
