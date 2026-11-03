import { Button, Group, NumberInput, Stack, Switch, Table, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { core } from '../api/endpoints';
import { r2 } from '../api/endpoints2';
import type { ReportingSettings } from '../api/types';
import { ErrorNote, Loading } from '../components/Status';

/** Organisation settings (ADMIN): reporting calendar, schedule windows, disposition maker-checker. */
export function Settings() {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['settings'], queryFn: core.settings });
  const [form, setForm] = useState<ReportingSettings | null>(null);
  useEffect(() => { if (data) setForm(data); }, [data]);
  const save = useMutation({
    mutationFn: () => core.saveSettings(form!),
    onSuccess: (s) => {
      qc.setQueryData(['settings'], s);
      notifications.show({ color: 'green', message: 'Settings saved.' });
    },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  if (isLoading || (!form && !error)) {
    return <Loading />;
  }
  if (error) {
    return <ErrorNote error={error} />;
  }
  const set = (patch: Partial<ReportingSettings>) => setForm({ ...form!, ...patch });
  return (
    <Stack maw={420}>
      <Title order={2}>Settings</Title>
      <NumberInput label="Fiscal year starts in month" min={1} max={12} value={form!.fiscalYearStartMonth}
        onChange={(v) => set({ fiscalYearStartMonth: Number(v) })} />
      <Switch label="Periodic windows include the current period" checked={form!.periodicIncludesCurrentPeriod}
        onChange={(e) => set({ periodicIncludesCurrentPeriod: e.currentTarget.checked })} />
      <NumberInput label="'Due soon' window (days)" min={1} max={365} value={form!.dueSoonDays}
        onChange={(v) => set({ dueSoonDays: Number(v) })} />
      <NumberInput label="Upcoming window (days)" min={1} max={365} value={form!.upcomingWindowDays}
        onChange={(v) => set({ upcomingWindowDays: Number(v) })} />
      <Switch label="Waivers and extensions need an approver (maker-checker)"
        checked={form!.dispositionApprovalRequired}
        onChange={(e) => set({ dispositionApprovalRequired: e.currentTarget.checked })} />
      <TextInput label="Reporting currency (ISO code)" maxLength={3} value={form!.reportingCurrency}
        onChange={(e) => set({ reportingCurrency: e.currentTarget.value.toUpperCase() })} />
      <Button onClick={() => save.mutate()} loading={save.isPending}>Save</Button>
      <FxRates />
    </Stack>
  );
}

function FxRates() {
  const qc = useQueryClient();
  const rates = useQuery({ queryKey: ['fx'], queryFn: r2.fxRates });
  const [from, setFrom] = useState('USD');
  const [to, setTo] = useState('INR');
  const [date, setDate] = useState('');
  const [rate, setRate] = useState<number | string>('');
  const add = useMutation({
    mutationFn: () => r2.saveFxRate({ fromCurrency: from, toCurrency: to, rateDate: date, rate: Number(rate) }),
    onSuccess: () => { setRate(''); qc.invalidateQueries({ queryKey: ['fx'] }); },
    onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
  });
  return (
    <Stack mt="lg">
      <Title order={4}>FX rates</Title>
      <Table withTableBorder>
        <Table.Thead><Table.Tr><Table.Th>Pair</Table.Th><Table.Th>Date</Table.Th><Table.Th>Rate</Table.Th></Table.Tr></Table.Thead>
        <Table.Tbody>
          {(rates.data ?? []).map((r) => (
            <Table.Tr key={r.id}><Table.Td>{r.fromCurrency}/{r.toCurrency}</Table.Td><Table.Td>{r.rateDate}</Table.Td>
              <Table.Td>{r.rate}</Table.Td></Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Group align="end">
        <TextInput label="From" w={70} maxLength={3} value={from} onChange={(e) => setFrom(e.currentTarget.value.toUpperCase())} />
        <TextInput label="To" w={70} maxLength={3} value={to} onChange={(e) => setTo(e.currentTarget.value.toUpperCase())} />
        <TextInput type="date" label="Date" value={date} onChange={(e) => setDate(e.currentTarget.value)} />
        <NumberInput label="Rate" w={120} value={rate} onChange={setRate} decimalScale={8} />
        <Button onClick={() => add.mutate()} disabled={!date || rate === '' || from.length !== 3 || to.length !== 3}
          loading={add.isPending}>Add</Button>
      </Group>
    </Stack>
  );
}
