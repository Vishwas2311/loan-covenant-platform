import {
  Anchor, Badge, Button, Checkbox, Drawer, Group, Modal, SegmentedControl, Select, Stack, Table, TagsInput, Text,
  Textarea,
  TextInput, Title,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { notifications } from '@mantine/notifications';
import { errorMessage } from '../api/client';
import { core } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import { r3 } from '../api/endpoints3';
import type { Frequency, NewCovenant } from '../api/types';
import type { CovenantDetail } from '../api/types3';
import { canOpen, pageFor } from '../auth/access';
import { hasRole } from '../auth/identity';
import { useIdentity } from '../auth/useIdentity';
import { ErrorNote, Loading } from '../components/Status';
import { humanize } from '../components/CpPicker';
import { useMeasures } from '../components/useMeasures';
import { describeRule } from '../format';

const FREQUENCIES: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY', 'CUSTOM'];

type Draft = NewCovenant & { defaultCron: string; policyReference: string };

/** Adds a covenant to the shared library, or edits one (AUTHOR / ADMIN; Core checks the role and the rule). */
function CovenantForm({ existing, onClose }: { existing?: CovenantDetail; onClose: () => void }) {
  const qc = useQueryClient();
  const [draft, setDraft] = useState<Draft>(existing ? {
    name: existing.name, category: existing.category === 'NON_FINANCIAL' ? 'NON_FINANCIAL' : 'FINANCIAL',
    description: existing.description ?? '', conditionExpression: existing.conditionExpression ?? '',
    assessmentType: existing.assessmentType, defaultFrequency: existing.defaultFrequency,
    businessSegments: existing.businessSegments.filter((s) => s !== 'ALL'), defaultCron: existing.defaultCron ?? '',
    policyReference: existing.policyReference ?? '',
  } : {
    name: '', category: 'FINANCIAL', description: '', conditionExpression: '',
    assessmentType: 'AUTO', defaultFrequency: 'QUARTERLY', businessSegments: [], defaultCron: '', policyReference: '',
  });
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));
  const manual = draft.assessmentType === 'MANUAL';
  const custom = draft.defaultFrequency === 'CUSTOM';
  const measures = useQuery({ queryKey: ['measures'], queryFn: core.measures, enabled: !manual });
  const save = useMutation({
    mutationFn: async () => {
      const cron = custom ? draft.defaultCron.trim() : '';
      if (custom) {
        const check = await r3.validateCron(cron);
        if (!check.valid) {
          throw new Error(`Custom schedule: ${check.error ?? 'not a valid cron expression'}`);
        }
      }
      const body: NewCovenant & { defaultCron: string | null } = {
        name: draft.name.trim(), category: draft.category, description: draft.description.trim(),
        conditionExpression: draft.conditionExpression.trim(), assessmentType: draft.assessmentType,
        defaultFrequency: draft.defaultFrequency, businessSegments: draft.businessSegments,
        defaultCron: cron || null, policyReference: draft.policyReference.trim() || null,
      };
      return existing ? r3.updateCovenant(existing.id, existing, body) : core.createCovenant(body);
    },
    onSuccess: (c) => {
      notifications.show({ color: 'green', message: existing ? `${c.name} saved.` : `${c.name} added to the library.` });
      const rc = (c as { recheck?: { queued: number } }).recheck;
      if (rc && rc.queued > 0) {
        notifications.show({
          color: 'blue', autoClose: 10_000,
          message: `Checking the new rule on ${rc.queued} CP covenant(s) now. Each CP page shows its result as it`
            + ' finishes, and new breaches appear in the RM tasks. CPs with their own threshold keep it.',
        });
      }
      qc.invalidateQueries({ queryKey: ['covenants'] });
      qc.invalidateQueries({ queryKey: ['covenant'] });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const ready = draft.name.trim() && draft.description.trim() && draft.conditionExpression.trim()
    && (!custom || draft.defaultCron.trim());

  return (
    <Modal opened onClose={onClose} title={existing ? `Edit ${existing.name}` : 'New covenant'} size="lg">
      <Stack>
        <TextInput label="Name" required value={draft.name} onChange={(e) => set({ name: e.currentTarget.value })}
          placeholder="Minimum Current Ratio" />
        <SegmentedControl value={draft.category} data={[
          { value: 'FINANCIAL', label: 'Financial' }, { value: 'NON_FINANCIAL', label: 'Non-financial' }]}
          onChange={(v) => {
            // A non-financial covenant has no figures to test, so a person checks it.
            set({ category: v as NewCovenant['category'], assessmentType: v === 'NON_FINANCIAL' ? 'MANUAL' : 'AUTO' });
          }} />
        <Textarea label="Description" required minRows={2} value={draft.description}
          onChange={(e) => set({ description: e.currentTarget.value })}
          placeholder="What the borrower must do, in plain words" />
        <SegmentedControl value={draft.assessmentType} data={[
          { value: 'AUTO', label: 'Checked by the app' }, { value: 'MANUAL', label: 'Checked by a person' }]}
          onChange={(v) => set({ assessmentType: v as NewCovenant['assessmentType'] })} />
        <TextInput label={manual ? 'What to check' : 'Rule'} required value={draft.conditionExpression}
          onChange={(e) => set({ conditionExpression: e.currentTarget.value })}
          styles={manual ? undefined : { input: { fontFamily: 'monospace' } }}
          placeholder={manual ? 'Quarterly MIS submitted within 45 days' : 'CURRENT_RATIO >= 1.20'}
          description={manual ? 'Plain words for the person who checks it.'
            : 'A measure, a comparison and a number, e.g. DSCR >= 1.30 or DEBT_EBITDA <= 3.5.'} />
        {!manual && measures.data && (
          <Text size="xs" c="dimmed">
            Measures: {measures.data.filter((m) => m.active).map((m) => m.code).sort().join(', ')}
          </Text>
        )}
        <Select label="How often" data={FREQUENCIES} value={draft.defaultFrequency} allowDeselect={false}
          onChange={(v) => v && set({ defaultFrequency: v as Frequency })} />
        {custom && (
          <TextInput label="Custom schedule (cron)" required value={draft.defaultCron}
            onChange={(e) => set({ defaultCron: e.currentTarget.value })} placeholder="0 0 9 15 * ?"
            description="Quartz cron: seconds minutes hours day month weekday. 0 0 9 15 * ? = 9:00 on the 15th of every month." />
        )}
        <TextInput label="Credit policy reference" value={draft.policyReference}
          onChange={(e) => set({ policyReference: e.currentTarget.value })}
          placeholder="e.g. Credit Policy 2026, section 4.2, or a link"
          description="Shown to the RM when they ask for a waiver." />
        <TagsInput label="Business segments" value={draft.businessSegments}
          onChange={(v) => set({ businessSegments: v.map((s) => s.toUpperCase()) })}
          placeholder="Leave empty for all, or type e.g. CORPORATE and press Enter" />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!ready}>
            {existing ? 'Save changes' : 'Add to library'}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** A2: one library covenant: its details, where it is used, its change history, and edit / switch off. */
function CovenantDetails({ id, canAuthor, onClose }: { id: string; canAuthor: boolean; onClose: () => void }) {
  const opensCps = canOpen(useIdentity(), pageFor('/cp')!);
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const detail = useQuery({ queryKey: ['covenant', id], queryFn: () => r3.covenant(id) });
  const usage = useQuery({ queryKey: ['covenant', id, 'usage'], queryFn: () => r3.covenantUsage(id) });
  const changes = useQuery({ queryKey: ['covenant', id, 'changes'], queryFn: () => r3.covenantChanges(id) });
  const switchOff = useMutation({
    mutationFn: () => r3.deactivateCovenant(id),
    onSuccess: (c) => {
      notifications.show({ color: 'green', message: `${c.name} switched off. Loans already using it keep it.` });
      qc.invalidateQueries({ queryKey: ['covenants'] });
      qc.invalidateQueries({ queryKey: ['covenant', id] });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  const c = detail.data;
  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={c?.name ?? 'Covenant'}>
      {detail.isLoading && <Loading />}
      {detail.error && <ErrorNote error={detail.error} />}
      {c && (
        <Stack>
          <Group gap="xs">
            <Badge variant="light">{c.category}</Badge>
            <Badge variant="light" color="gray">{c.assessmentType === 'MANUAL' ? 'checked by a person' : 'checked by the app'}</Badge>
            <Badge variant="light" color="gray">{c.defaultFrequency}{c.defaultCron ? ` (${c.defaultCron})` : ''}</Badge>
            {!c.active && <Badge color="gray">inactive</Badge>}
          </Group>
          <Text>{c.description}</Text>
          <Text ff="monospace" size="sm">{c.conditionExpression}</Text>
          <Text size="sm" c="dimmed">Business segments: {c.businessSegments.join(', ') || 'ALL'}</Text>
          {c.policyReference && (
            <Text size="sm">Credit policy: {/^https?:\/\//.test(c.policyReference)
              ? <Anchor href={c.policyReference} target="_blank" rel="noreferrer">{c.policyReference}</Anchor>
              : c.policyReference}</Text>
          )}
          {canAuthor && (
            <Group>
              <Button variant="light" onClick={() => setEditing(true)}>Edit</Button>
              {c.active && (
                <Button variant="light" color="red" loading={switchOff.isPending}
                  onClick={() => window.confirm(`Switch off ${c.name}? It can no longer be chosen for new loans; `
                    + `loans already using it keep it.`) && switchOff.mutate()}>Switch off</Button>
              )}
            </Group>
          )}
          <Title order={5}>Where it is used</Title>
          {usage.data && (
            <Text size="sm">
              On {usage.data.totalApplied} loan(s), {usage.data.activelyApplied} still monitored
              {usage.data.sampleCpIds.length > 0 && ': '}
              {usage.data.sampleCpIds.map((cp, i) => (
                <span key={cp}>{i > 0 && ', '}{opensCps
                  ? <Anchor component={Link} to={`/cp/${encodeURIComponent(cp)}`}>{cp}</Anchor> : cp}</span>
              ))}
            </Text>
          )}
          <Title order={5}>Changes to the rule</Title>
          <Table withTableBorder>
            <Table.Thead><Table.Tr><Table.Th>When</Table.Th><Table.Th>From → to</Table.Th><Table.Th>By</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {(changes.data ?? []).map((x) => (
                <Table.Tr key={x.id}>
                  <Table.Td>{new Date(x.changedDate).toLocaleString()}</Table.Td>
                  <Table.Td><Text ff="monospace" size="xs">{x.oldExpression ?? '—'} → {x.newExpression}</Text></Table.Td>
                  <Table.Td>{x.changedBy}</Table.Td>
                </Table.Tr>
              ))}
              {(changes.data ?? []).length === 0 && (
                <Table.Tr><Table.Td colSpan={3}><Text c="dimmed">No changes since it was created.</Text></Table.Td></Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        </Stack>
      )}
      {editing && c && <CovenantForm existing={c} onClose={() => setEditing(false)} />}
    </Drawer>
  );
}

/** Covenant library. Everyone can read it; authors and admins can add to it and change it. */
export function Library() {
  const identity = useIdentity();
  const canAuthor = hasRole(identity, 'AUTHOR', 'ADMIN');
  const [creating, setCreating] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [category, setCategory] = useState('');
  const [withOff, setWithOff] = useState(false);
  const measures = useMeasures();
  const filter = { category: category || undefined, active: withOff ? undefined : true };
  const { data, isLoading, error } = useQuery({
    queryKey: ['covenants', search, page, category, withOff], queryFn: () => core.covenants(search, page, filter),
  });
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Covenant library</Title>
        <Group>
          {canAuthor && <Button onClick={() => setCreating(true)}>New covenant</Button>}
          <Button variant="light" onClick={() => r2.libraryXlsx().catch((e) =>
            notifications.show({ color: 'red', message: errorMessage(e) }))}>Download Excel</Button>
        </Group>
      </Group>
      {creating && <CovenantForm onClose={() => setCreating(false)} />}
      {openId && <CovenantDetails id={openId} canAuthor={canAuthor} onClose={() => setOpenId(null)} />}
      <Group>
        <TextInput placeholder="Search name or description" aria-label="Search covenants" value={search}
          onChange={(e) => { setSearch(e.currentTarget.value); setPage(0); }} w={320} />
        <SegmentedControl aria-label="Category" value={category} onChange={(v) => { setCategory(v); setPage(0); }}
          data={[{ value: '', label: 'All' }, { value: 'FINANCIAL', label: 'Financial' },
            { value: 'NON_FINANCIAL', label: 'Non-financial' }]} />
        <Checkbox label="Include switched-off covenants" checked={withOff}
          onChange={(e) => { setWithOff(e.currentTarget.checked); setPage(0); }} />
      </Group>
      {isLoading && <Loading />}
      {error && <ErrorNote error={error} />}
      {data && (
        <>
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr><Table.Th>Name</Table.Th><Table.Th>Category</Table.Th><Table.Th>Rule</Table.Th>
                <Table.Th>Type</Table.Th><Table.Th>Frequency</Table.Th><Table.Th /></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.content.map((c) => (
                <Table.Tr key={c.id}>
                  <Table.Td>
                    <Anchor component="button" ta="left" onClick={() => setOpenId(c.id)}>{c.name}</Anchor>
                    <Text size="xs" c="dimmed">{c.description}</Text>
                  </Table.Td>
                  <Table.Td>{humanize(c.category)}</Table.Td>
                  <Table.Td>
                    {describeRule(c.conditionExpression, measures)
                      && <Text size="sm">{describeRule(c.conditionExpression, measures)!.text}</Text>}
                    <Text ff="monospace" size="xs" c="dimmed">{c.conditionExpression}</Text>
                  </Table.Td>
                  <Table.Td>{c.assessmentType === 'MANUAL' ? 'Manual check' : 'Automatic'}</Table.Td>
                  <Table.Td>{humanize(c.defaultFrequency)}</Table.Td>
                  <Table.Td>{!c.active && <Badge color="gray">inactive</Badge>}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Group>
            <Button variant="default" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
            <Text size="sm">Page {data.number + 1} · {data.totalElements} covenants</Text>
            <Button variant="default" disabled={data.last} onClick={() => setPage(page + 1)}>Next</Button>
          </Group>
        </>
      )}
    </Stack>
  );
}
