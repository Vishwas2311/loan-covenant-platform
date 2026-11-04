import {
  Badge, Button, Card, Group, Progress, SegmentedControl, SimpleGrid, Stack, Text, TextInput, Title, UnstyledButton,
} from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { r2 } from '../api/endpoints2';
import type { CpCard } from '../api/types2';
import { ErrorNote, Loading } from './Status';

/** Free-text CP id entry used by pages reached without a CP in the URL. */
export function CpPicker({ initial = '', onOpen, label = 'Credit proposal (CP id)' }: {
  initial?: string; onOpen: (cpId: string) => void; label?: string;
}) {
  const [value, setValue] = useState(initial);
  const submit = () => {
    const cp = value.trim();
    if (cp) {
      onOpen(cp);
    }
  };
  return (
    <Group align="end">
      <TextInput label={label} value={value} onChange={(e) => setValue(e.currentTarget.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()} w={260} />
      <Button onClick={submit} disabled={!value.trim()}>Open</Button>
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

/** The signed-in user's CPs; shared by the chooser and the CP headers. */
export function useMyCps() {
  return useQuery({
    queryKey: ['my-cps'], queryFn: r2.cps, staleTime: 60_000,
    select: (d: CpCard[]) => (Array.isArray(d) ? d : []),
  });
}

/** Borrower name and where the CP sits, under a page title that names a CP. */
export function CpHeader({ cpId }: { cpId: string }) {
  const { data } = useMyCps();
  const cp = data?.find((c) => c.cpId === cpId);
  if (!cp) {
    return null;
  }
  const parts = [humanize(cp.businessSegment), humanize(cp.sector), cp.rmUserId && `RM ${cp.rmUserId}`]
    .filter(Boolean);
  return (
    <Group gap="xs">
      {cp.name && <Text fw={600} size="lg">{cp.name}</Text>}
      {parts.length > 0 && <Text c="dimmed" size="sm">{parts.join(' · ')}</Text>}
    </Group>
  );
}

type Show = 'all' | 'attention' | 'new';

const SHOW: Record<Show, (c: CpCard) => boolean> = {
  all: () => true,
  attention: (c) => c.breached > 0 || c.error > 0 || c.pendingReview > 0,
  new: (c) => c.applied === 0,
};

function Health({ cp }: { cp: CpCard }) {
  if (cp.applied === 0) {
    return <Badge color="gray" variant="light">No covenants yet</Badge>;
  }
  const pct = (n: number) => (n * 100) / cp.applied;
  const other = cp.applied - cp.compliant - cp.breached - cp.error;
  return (
    <Stack gap={6}>
      <Progress.Root size="sm" aria-hidden>
        <Progress.Section value={pct(cp.compliant)} color="green" />
        <Progress.Section value={pct(cp.breached)} color="red" />
        <Progress.Section value={pct(cp.error)} color="orange" />
        <Progress.Section value={pct(other)} color="gray.3" />
      </Progress.Root>
      <Group gap={6}>
        <Text size="xs" c="dimmed">{cp.applied} covenant{cp.applied === 1 ? '' : 's'}</Text>
        {cp.breached > 0 && <Badge size="xs" color="red" variant="light">{cp.breached} breached</Badge>}
        {cp.error > 0 && <Badge size="xs" color="orange" variant="light">{cp.error} data error{cp.error === 1 ? '' : 's'}</Badge>}
        {cp.pendingReview > 0 && <Badge size="xs" color="yellow" variant="light">{cp.pendingReview} awaiting review</Badge>}
        {cp.breached === 0 && cp.error === 0 && cp.pendingReview === 0 && (
          <Badge size="xs" color="green" variant="light">All in order</Badge>
        )}
      </Group>
    </Stack>
  );
}

/**
 * What a CP page shows before a CP is chosen: the user's CPs as cards they can search and filter,
 * with the free-text id box (in the page header) for CPs they do not own.
 */
export function CpChooser({ hint, onOpen }: { hint: string; onOpen: (cpId: string) => void }) {
  const { data, isLoading, error } = useMyCps();
  const [q, setQ] = useState('');
  const [show, setShow] = useState<Show>('all');
  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (data ?? []).filter(SHOW[show]).filter((c) => !needle
      || [c.cpId, c.name, c.businessSegment, c.sector].some((v) => v?.toLowerCase().includes(needle)));
  }, [data, q, show]);

  if (isLoading) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  if (!data?.length) {
    return (
      <Card withBorder padding="xl">
        <Stack align="center" gap="xs">
          <Title order={4}>No credit proposals of your own</Title>
          <Text c="dimmed" ta="center" maw={520}>
            You are not the RM or WRM on any CP. Type a CP id in the box above to open one you have access to.
          </Text>
        </Stack>
      </Card>
    );
  }
  return (
    <Stack>
      <Text c="dimmed">{hint}</Text>
      <Group>
        <TextInput placeholder="Search by borrower, CP id, segment or sector" aria-label="Search your CPs"
          leftSection={<IconSearch size={16} />} value={q} onChange={(e) => setQ(e.currentTarget.value)} w={360} />
        <SegmentedControl aria-label="Which CPs" value={show} onChange={(v) => setShow(v as Show)} data={[
          { value: 'all', label: `All (${data.length})` },
          { value: 'attention', label: `Needs attention (${data.filter(SHOW.attention).length})` },
          { value: 'new', label: `No covenants yet (${data.filter(SHOW.new).length})` }]} />
      </Group>
      {rows.length === 0 && <Text c="dimmed">No CP matches.</Text>}
      <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }}>
        {rows.map((c) => (
          <UnstyledButton key={c.cpId} onClick={() => onOpen(c.cpId)} aria-label={`Open ${c.cpId}`}>
            <Card withBorder padding="md" h="100%" className="cp-card">
              <Stack gap="xs">
                <Group justify="space-between" wrap="nowrap" align="start">
                  <div>
                    <Text fw={600} lineClamp={1}>{c.name ?? c.cpId}</Text>
                    <Text size="xs" c="dimmed">{c.cpId}{c.rmUserId ? ` · RM ${c.rmUserId}` : ''}</Text>
                  </div>
                </Group>
                <Group gap={6}>
                  {c.businessSegment && <Badge variant="outline" size="sm">{humanize(c.businessSegment)}</Badge>}
                  {c.sector && <Badge variant="outline" color="gray" size="sm">{humanize(c.sector)}</Badge>}
                </Group>
                <Health cp={c} />
              </Stack>
            </Card>
          </UnstyledButton>
        ))}
      </SimpleGrid>
    </Stack>
  );
}
