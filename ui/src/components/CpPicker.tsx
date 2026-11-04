import {
  Autocomplete, Button, Card, Group, SegmentedControl, Stack, Table, Text, TextInput, type ComboboxItem,
} from '@mantine/core';
import { IconChevronRight, IconSearch } from '@tabler/icons-react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';
import { dps } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import type { DpsDocument } from '../api/types';
import type { CpCard } from '../api/types2';
import { Chip } from './Board';
import { MAGNITUDE, STATUS, SplitBar } from './charts';
import { ErrorNote, Loading } from './Status';

/** The signed-in user's CPs; shared by the lists, the switcher and the CP headers. */
export function useMyCps() {
  return useQuery({
    queryKey: ['my-cps'], queryFn: r2.cps, staleTime: 60_000,
    select: (d: CpCard[]) => (Array.isArray(d) ? d.filter((c) => typeof c?.cpId === 'string') : []),
  });
}

/** Each CP's documents, for the pages that list CPs by their paperwork. Capped so a bank-wide list stays cheap. */
export function useCpDocuments(cpIds: string[]) {
  const ids = cpIds.slice(0, 60);
  const results = useQueries({
    queries: ids.map((cpId) => ({ queryKey: ['documents', cpId], queryFn: () => dps.documents(cpId), staleTime: 30_000 })),
  });
  const byCp = new Map<string, DpsDocument[]>();
  ids.forEach((cpId, i) => {
    const d = results[i]?.data;
    if (Array.isArray(d)) {
      byCp.set(cpId, d);
    }
  });
  return { byCp, loading: results.some((r) => r.isLoading) };
}

/** Go to another CP from a CP page: type a borrower or CP id, pick it, or type any id you can open. */
export function CpPicker({ initial = '', onOpen, label = 'Credit proposal (CP id)' }: {
  initial?: string; onOpen: (cpId: string) => void; label?: string;
}) {
  const cps = useMyCps().data ?? [];
  const names = useMemo(() => new Map(cps.map((c) => [c.cpId, c.name ?? ''])), [cps]);
  const [value, setValue] = useState(initial);
  const submit = (v = value) => {
    const cp = v.trim().toUpperCase();
    if (cp) {
      onOpen(cp);
    }
  };
  return (
    <Group gap={6} wrap="nowrap">
      <Autocomplete aria-label={label} placeholder="Go to a CP: borrower or id" value={value} onChange={setValue} w={260}
        leftSection={<IconSearch size={14} />} data={cps.map((c) => c.cpId)} limit={8}
        filter={({ options, search }) => {
          const needle = search.trim().toLowerCase();
          return (options as ComboboxItem[]).filter((o) => !needle || o.value.toLowerCase().includes(needle)
            || (names.get(o.value) ?? '').toLowerCase().includes(needle));
        }}
        renderOption={({ option }) => (
          <Stack gap={0}>
            <Text size="sm">{names.get(option.value) || option.value}</Text>
            <Text size="xs" c="dimmed">{option.value}</Text>
          </Stack>
        )}
        onOptionSubmit={(v) => submit(v)} onKeyDown={(e) => e.key === 'Enter' && submit()} />
      <Button variant="light" onClick={() => submit()} disabled={!value.trim()}>Open</Button>
    </Group>
  );
}

/** OIL_GAS -> Oil gas. */
export function humanize(v: string | null | undefined): string {
  if (!v) {
    return '';
  }
  const s = v.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** The CP's context (borrower, segment, sector, RM) as chips next to a page title. */
export function CpChips({ cpId }: { cpId: string }) {
  const card = useMyCps().data?.find((c) => c.cpId === cpId);
  return (
    <>
      {card?.name && <Chip>{card.name}</Chip>}
      {card?.businessSegment && <Chip>{humanize(card.businessSegment)}</Chip>}
      {card?.sector && <Chip>{humanize(card.sector)}</Chip>}
      {card?.rmUserId && <Chip>RM {card.rmUserId}</Chip>}
    </>
  );
}

/** A short status word after a coloured dot. */
export function Dot({ color, children }: { color: string; children: ReactNode }) {
  return <Text size="xs" span style={{ whiteSpace: 'nowrap' }}><span className="dot" style={{ background: color }} />{children}</Text>;
}

/** What a CP's covenants need, worst first, as dot-and-word lines. */
export function CpState({ cp }: { cp: CpCard }) {
  if (cp.applied === 0) {
    return <Dot color={STATUS.neutral}>No covenants yet</Dot>;
  }
  const parts = [
    cp.breached > 0 && <Dot key="b" color={STATUS.bad}>{cp.breached} breached</Dot>,
    cp.error > 0 && <Dot key="e" color={STATUS.warn}>{cp.error} data error{cp.error === 1 ? '' : 's'}</Dot>,
    cp.pendingReview > 0 && <Dot key="p" color={MAGNITUDE}>{cp.pendingReview} awaiting review</Dot>,
  ].filter(Boolean);
  return parts.length ? <Group gap={12}>{parts}</Group> : <Dot color={STATUS.good}>All in order</Dot>;
}

function Health({ cp }: { cp: CpCard }) {
  const other = Math.max(0, cp.applied - cp.compliant - cp.breached - cp.error);
  return cp.applied === 0 ? <Text size="xs" c="dimmed">—</Text> : (
    <SplitBar height={6} ariaLabel={`${cp.compliant} kept, ${cp.breached} breached, ${cp.error} could not be checked`} parts={[
      { key: 'kept', label: 'Kept', value: cp.compliant, color: MAGNITUDE },
      { key: 'breached', label: 'Breached', value: cp.breached, color: STATUS.bad },
      { key: 'error', label: 'Could not be checked', value: cp.error, color: STATUS.warn },
      { key: 'other', label: 'Not checked yet', value: other, color: STATUS.neutral }]} />
  );
}

type Show = 'all' | 'attention' | 'new';

const SHOW: Record<Show, (c: CpCard) => boolean> = {
  all: () => true,
  attention: (c) => c.breached > 0 || c.error > 0 || c.pendingReview > 0,
  new: (c) => c.applied === 0,
};

/** Worst first: breaches, then data errors, then waiting reviews; then by borrower. */
export const worstFirst = (a: CpCard, b: CpCard) => b.breached - a.breached || b.error - a.error
  || b.pendingReview - a.pendingReview || (a.name ?? a.cpId).localeCompare(b.name ?? b.cpId) || a.cpId.localeCompare(b.cpId);

/** Search box over the user's CPs that also opens any typed CP id they can reach but do not own. */
export function useCpSearch(data: CpCard[] | undefined) {
  const [q, setQ] = useState('');
  const needle = q.trim().toLowerCase();
  const match = (c: CpCard) => !needle
    || [c.cpId, c.name, c.businessSegment, c.sector, c.rmUserId].some((v) => v?.toLowerCase().includes(needle));
  const typedId = /^cp-\S+$/i.test(q.trim()) && !(data ?? []).some((c) => c.cpId.toLowerCase() === needle)
    ? q.trim().toUpperCase() : null;
  return { q, setQ, match, typedId };
}

export function CpSearch({ q, setQ, typedId, onOpen, placeholder = 'Search borrower or CP id' }: {
  q: string; setQ: (v: string) => void; typedId: string | null; onOpen: (cpId: string) => void; placeholder?: string;
}) {
  return (
    <Group gap="sm">
      <TextInput placeholder={placeholder} aria-label="Search your CPs" leftSection={<IconSearch size={14} />}
        value={q} onChange={(e) => setQ(e.currentTarget.value)} w={320} />
      {typedId && <Button variant="light" onClick={() => onOpen(typedId)}>Open {typedId}</Button>}
    </Group>
  );
}

/**
 * The CP workspace before a CP is chosen: every CP the user looks after in one sortable list, worst
 * first, with what each needs. A typed CP id the user does not own can be opened from the search box.
 */
export function CpChooser({ onOpen }: { hint?: string; onOpen: (cpId: string) => void }) {
  const { data, isLoading, error } = useMyCps();
  const search = useCpSearch(data);
  const [show, setShow] = useState<Show>('all');
  const rows = (data ?? []).filter(SHOW[show]).filter(search.match).sort(worstFirst);

  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const all = data ?? [];
  return (
    <Card padding={0}>
      <Group justify="space-between" p="md" wrap="wrap" gap="sm">
        <CpSearch {...search} onOpen={onOpen} />
        <Group gap="sm">
          <SegmentedControl aria-label="Which CPs" value={show} onChange={(v) => setShow(v as Show)} data={[
            { value: 'all', label: `All (${all.length})` },
            { value: 'attention', label: `Needs attention (${all.filter(SHOW.attention).length})` },
            { value: 'new', label: `No covenants yet (${all.filter(SHOW.new).length})` }]} />
        </Group>
      </Group>
      <Table.ScrollContainer minWidth={820}>
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Credit proposal</Table.Th><Table.Th>Segment</Table.Th><Table.Th>Sector</Table.Th>
              <Table.Th ta="right">Covenants</Table.Th><Table.Th w={180}>Results</Table.Th><Table.Th>Needs</Table.Th>
              <Table.Th w={28} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((c) => (
              <Table.Tr key={c.cpId} className="row-link" onClick={() => onOpen(c.cpId)}>
                <Table.Td>
                  <button type="button" className="row-open" aria-label={`Open ${c.cpId}`}
                    onClick={(e) => { e.stopPropagation(); onOpen(c.cpId); }}>
                    <Text size="sm" fw={550} lh={1.3}>{c.name ?? c.cpId}</Text>
                    <Text size="xs" c="dimmed">{c.cpId}{c.rmUserId ? ` · RM ${c.rmUserId}` : ''}</Text>
                  </button>
                </Table.Td>
                <Table.Td c="dimmed">{humanize(c.businessSegment)}</Table.Td>
                <Table.Td c="dimmed">{humanize(c.sector)}</Table.Td>
                <Table.Td ta="right">{c.applied}</Table.Td>
                <Table.Td><Health cp={c} /></Table.Td>
                <Table.Td><CpState cp={c} /></Table.Td>
                <Table.Td><IconChevronRight size={14} color="var(--mantine-color-dimmed)" /></Table.Td>
              </Table.Tr>
            ))}
            {rows.length === 0 && (
              <Table.Tr><Table.Td colSpan={7}>
                <Text size="sm" c="dimmed">
                  {all.length ? 'No CP matches.' : 'You are not the RM or WRM on any CP. Type a CP id in the search box to open one you have access to.'}
                </Text>
              </Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </Card>
  );
}
