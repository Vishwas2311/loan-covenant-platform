import { ActionIcon, Anchor, Badge, Indicator, Popover, ScrollArea, Stack, Text } from '@mantine/core';
import { IconBell } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { r3 } from '../api/endpoints3';
import type { Alert } from '../api/types3';

const LABEL: Record<Alert['kind'], { text: string; color: string }> = {
  APPROVAL: { text: 'to approve', color: 'yellow' },
  BREACH: { text: 'breach', color: 'red' },
  OVERDUE: { text: 'overdue', color: 'red' },
  REVIEW: { text: 'to record', color: 'yellow' },
  AT_RISK: { text: 'at risk', color: 'orange' },
};

/** B6: what is waiting on the signed-in user, refreshed every minute. */
export function AlertBell() {
  const [opened, setOpened] = useState(false);
  const { data } = useQuery({ queryKey: ['alerts'], queryFn: r3.alerts, refetchInterval: 60_000 });
  const alerts = Array.isArray(data) ? data : [];
  const urgent = alerts.filter((a) => a.kind !== 'AT_RISK').length;
  return (
    <Popover opened={opened} onChange={setOpened} position="bottom-end" width={380} shadow="md">
      <Popover.Target>
        <Indicator label={urgent} size={16} disabled={urgent === 0} color="red">
          <ActionIcon variant="subtle" aria-label={`Alerts (${alerts.length})`} onClick={() => setOpened((o) => !o)}>
            <IconBell size={20} />
          </ActionIcon>
        </Indicator>
      </Popover.Target>
      <Popover.Dropdown>
        {alerts.length === 0 ? <Text size="sm" c="dimmed">Nothing needs you right now.</Text> : (
          <ScrollArea.Autosize mah={420}>
            <Stack gap="xs">
              {alerts.map((a, i) => (
                <div key={`${a.kind}-${a.appliedCovenantId}-${i}`}>
                  <Badge size="xs" variant="light" color={LABEL[a.kind].color}>{LABEL[a.kind].text}</Badge>{' '}
                  <Anchor component={Link} to={`/cp/${encodeURIComponent(a.cpId)}`} size="sm"
                    onClick={() => setOpened(false)}>{a.cpId}</Anchor>
                  <Text size="sm">{a.message}</Text>
                </div>
              ))}
            </Stack>
          </ScrollArea.Autosize>
        )}
      </Popover.Dropdown>
    </Popover>
  );
}
