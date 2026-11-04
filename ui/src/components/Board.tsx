import { Card, Group, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';

/** Context next to a page title: a period, "as of", a scope. */
export function Chip({ children, icon }: { children: ReactNode; icon?: ReactNode }) {
  return <span className="chip">{icon}{children}</span>;
}

/**
 * The top of a page: the title (a noun), one grey line with the screen's finding, context chips, and
 * the page's own actions on the right.
 */
export function PageHeader({ title, finding, chips, actions, children }: {
  title: ReactNode; finding?: ReactNode; chips?: ReactNode; actions?: ReactNode; children?: ReactNode;
}) {
  return (
    <Group justify="space-between" align="center" wrap="wrap" gap="md">
      <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
        <Group gap={10} align="center" wrap="wrap">
          <Title order={1}>{title}</Title>
          {chips && <Group gap={6}>{chips}</Group>}
        </Group>
        {children}
        {finding && <Text size="sm" c="dimmed" maw={900}>{finding}</Text>}
      </Stack>
      {actions && <Group gap="sm">{actions}</Group>}
    </Group>
  );
}

export type Tone = 'neutral' | 'good' | 'bad' | 'warn' | 'info';

const VALUE: Record<Tone, string | undefined> = {
  neutral: undefined, good: 'var(--text-good)', bad: 'var(--text-bad)', warn: 'var(--text-warn)', info: 'var(--data-hue)',
};

/** One container split by hairlines into equal columns of KPIs. */
export function KpiStrip({ children, ariaLabel }: { children: ReactNode; ariaLabel?: string }) {
  return <div className="kpi-strip" role="group" aria-label={ariaLabel}>{children}</div>;
}

/**
 * One KPI: a grey sentence-case label, the value in ink (the answer figure larger), and one short line
 * under it. As a button it filters the list below and shows a 2px rule when selected.
 */
export function Kpi({ label, value, unit, tone = 'neutral', note, lead, onClick, selected, ariaLabel }: {
  label: string; value: ReactNode; unit?: string; tone?: Tone; note?: ReactNode; lead?: boolean;
  onClick?: () => void; selected?: boolean; ariaLabel?: string;
}) {
  const body = (
    <>
      <Text size="xs" c="dimmed" lh={1.3} fw={500}>{label}</Text>
      <Group gap={8} align="baseline" mt={2} wrap="nowrap">
        <Text className="num" fz={20} fw={lead ? 650 : 600} lh={1.2} c={VALUE[tone]}>{value}</Text>
        {unit && <Text size="sm" c="dimmed">{unit}</Text>}
        {note && <Text size="xs" c="dimmed" lineClamp={1}>{note}</Text>}
      </Group>
    </>
  );
  return onClick ? (
    <button type="button" className="kpi-cell" onClick={onClick} aria-pressed={selected} aria-label={ariaLabel ?? label}>
      {body}
    </button>
  ) : <div className="kpi-cell">{body}</div>;
}

/** A white card with a title row (title, grey period or note, actions on the right) holding one answer. */
export function ChartCard({ title, subtitle, actions, children }: {
  title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; children: ReactNode;
}) {
  return (
    <Card h="100%">
      <Group justify="space-between" align="flex-start" mb="sm" wrap="wrap" gap="sm">
        <Stack gap={2}>
          <Title order={4}>{title}</Title>
          {subtitle && <Text size="sm" c="dimmed">{subtitle}</Text>}
        </Stack>
        {actions && <Group gap="sm">{actions}</Group>}
      </Group>
      {children}
    </Card>
  );
}
