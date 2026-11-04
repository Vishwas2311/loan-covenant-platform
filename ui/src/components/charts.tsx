import { Group, Text, Tooltip } from '@mantine/core';
import { useState, type ReactNode } from 'react';

/**
 * The app's chart kit. Status colours (kept, could not be checked, broken) are fixed and always come
 * with a word; magnitude uses one blue; marks are thin with 4px rounded ends and 2px gaps; the grid is
 * recessive; every mark has a hover read-out.
 */
export const STATUS = {
  good: 'var(--status-good)',
  warn: 'var(--status-warn)',
  bad: 'var(--status-bad)',
  neutral: 'var(--status-neutral)',
} as const;
export const MAGNITUDE = 'var(--data-hue)';

const NUMBER = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 1 });
export const fmt = (n: number) => NUMBER.format(n);

/** Round axis steps (1, 2, 2.5, 5 x 10^n) covering [min, max], including 0. */
export function niceTicks(min: number, max: number, target = 5): number[] {
  const lo = Math.min(0, min);
  const hi = Math.max(0, max);
  if (hi === lo) {
    return [0, 1];
  }
  const raw = (hi - lo) / target;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * pow).find((s) => s >= raw) ?? 10 * pow;
  const ticks: number[] = [];
  for (let v = Math.floor(lo / step) * step; v <= hi + step * 1e-9; v += step) {
    ticks.push(Number(v.toFixed(10)));
  }
  if (ticks[ticks.length - 1] < hi) {
    ticks.push(ticks[ticks.length - 1] + step);
  }
  return ticks;
}

/** Dot legend: a small round swatch beside each series name (names stay in text ink). */
export function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <Group gap="md" role="list" aria-label="Legend">
      {items.map((s) => (
        <Group key={s.label} gap={6} role="listitem" wrap="nowrap">
          <span aria-hidden style={{ width: 9, height: 9, borderRadius: 999, background: s.color, display: 'inline-block' }} />
          <Text size="xs" c="dimmed">{s.label}</Text>
        </Group>
      ))}
    </Group>
  );
}

export interface BarRow {
  key: string;
  label: ReactNode;
  value: number;
  /** Colour for this bar; default one blue (magnitude) or green/red by sign when the chart diverges. */
  color?: string;
  /** Extra line in the hover read-out. */
  detail?: string;
}

/**
 * Horizontal bars with labels on the left and the value at the end of each bar. Negative values
 * grow left of a zero line (green up, red down).
 */
export function HBars({ rows, format = fmt, axisLabel, ariaLabel, labelWidth = 120 }: {
  rows: BarRow[]; format?: (n: number) => string; axisLabel?: string; ariaLabel: string; labelWidth?: number;
}) {
  if (rows.length === 0) {
    return <Text size="sm" c="dimmed">Nothing to show yet.</Text>;
  }
  const values = rows.map((r) => r.value);
  const ticks = niceTicks(Math.min(...values), Math.max(...values), 5);
  const lo = ticks[0];
  const hi = ticks[ticks.length - 1];
  const pos = (v: number) => ((v - lo) / (hi - lo)) * 100;
  const zero = pos(0);
  const diverging = lo < 0;
  return (
    <div className="chart" role="img" aria-label={ariaLabel}>
      <div style={{ display: 'grid', gridTemplateColumns: `${labelWidth}px 1fr 52px`, rowGap: 6, columnGap: 12 }}>
        {rows.map((r) => {
          const left = Math.min(pos(r.value), zero);
          const width = Math.abs(pos(r.value) - zero);
          const color = r.color ?? (diverging ? (r.value < 0 ? STATUS.bad : STATUS.good) : MAGNITUDE);
          const end = r.value < 0 ? 'left' : 'right';
          return (
            <div key={r.key} style={{ display: 'contents' }}>
              <Text size="sm" ta="right" truncate="end" style={{ alignSelf: 'center' }}>{r.label}</Text>
              <Tooltip withArrow openDelay={60} label={<>{r.label}: <b>{format(r.value)}</b>{r.detail ? ` · ${r.detail}` : ''}</>}>
                <div className="mark" style={{ position: 'relative', height: 24 }}>
                  {ticks.map((t) => (
                    <span key={t} aria-hidden style={{ position: 'absolute', left: `${pos(t)}%`, top: -3, bottom: -3,
                      borderLeft: `1px ${t === 0 ? 'solid' : 'dashed'} var(--app-chart-grid)` }} />
                  ))}
                  <span style={{ position: 'absolute', top: 4, height: 16, left: `${left}%`, width: `max(${width}%, 2px)`,
                    background: color, borderRadius: end === 'right' ? '0 4px 4px 0' : '4px 0 0 4px' }} />
                  <Text component="span" size="xs" className="num" style={{ position: 'absolute', top: 3, whiteSpace: 'nowrap',
                    ...(end === 'right' ? { left: `calc(${left + width}% + 6px)` } : { right: `calc(${100 - left}% + 6px)` }) }}>
                    {format(r.value)}
                  </Text>
                </div>
              </Tooltip>
              <span />
            </div>
          );
        })}
        <span />
        <div style={{ position: 'relative', height: 18 }} aria-hidden>
          {ticks.map((t) => (
            <Text key={t} component="span" size="xs" c="var(--app-chart-axis)" className="num"
              style={{ position: 'absolute', left: `${pos(t)}%`, transform: 'translateX(-50%)' }}>{format(t)}</Text>
          ))}
        </div>
        <span />
      </div>
      {axisLabel && <Text size="xs" c="dimmed" ta="center" mt={4} pl={labelWidth}>{axisLabel}</Text>}
    </div>
  );
}

export interface Segment {
  key: string;
  label: string;
  value: number;
  color: string;
}

/** One horizontal bar split into parts (kept / broken / could not check), with 2px gaps between parts. */
export function SplitBar({ parts, height = 8, ariaLabel }: { parts: Segment[]; height?: number; ariaLabel: string }) {
  const total = parts.reduce((n, p) => n + p.value, 0);
  if (total === 0) {
    return <div aria-label={ariaLabel} role="img" style={{ height, borderRadius: 999, background: 'var(--app-chart-grid)' }} />;
  }
  const shown = parts.filter((p) => p.value > 0);
  return (
    <div role="img" aria-label={ariaLabel} style={{ display: 'flex', gap: 2, height }}>
      {shown.map((p, i) => (
        <Tooltip key={p.key} withArrow label={`${p.label}: ${p.value}`}>
          <span style={{ flex: p.value, minWidth: 3, background: p.color,
            borderRadius: `${i === 0 ? 999 : 2}px ${i === shown.length - 1 ? 999 : 2}px ${i === shown.length - 1 ? 999 : 2}px ${i === 0 ? 999 : 2}px` }} />
        </Tooltip>
      ))}
    </div>
  );
}

export interface ColumnPoint {
  key: string;
  label: string;
  parts: Segment[];
}

/**
 * Stacked columns over time (checks per month by result): recessive dashed grid with round ticks,
 * 2px gaps between segments, rounded tops, and a read-out of the hovered month.
 */
export function StackedColumns({ points, ariaLabel, height = 220 }: { points: ColumnPoint[]; ariaLabel: string; height?: number }) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 720;
  const H = height;
  const left = 40;
  const bottom = 24;
  const top = 10;
  const totals = points.map((p) => p.parts.reduce((n, s) => n + s.value, 0));
  const ticks = niceTicks(0, Math.max(1, ...totals), 4);
  const hi = ticks[ticks.length - 1];
  const y = (v: number) => H - bottom - (v / hi) * (H - bottom - top);
  const slot = (W - left - 8) / Math.max(1, points.length);
  const bar = Math.min(34, slot * 0.62);
  const every = Math.ceil(points.length / 8);
  const shown = hover === null ? null : points[hover];
  return (
    <div style={{ position: 'relative' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={ariaLabel} className="chart"
        onMouseLeave={() => setHover(null)}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={left} x2={W - 4} y1={y(t)} y2={y(t)} stroke="var(--app-chart-grid)" strokeDasharray={t === 0 ? undefined : '3 4'} />
            <text x={left - 8} y={y(t) + 3} fontSize="9.5" textAnchor="end" fill="var(--app-chart-axis)">{fmt(t)}</text>
          </g>
        ))}
        {points.map((p, i) => {
          const x = left + i * slot + (slot - bar) / 2;
          let base = 0;
          const segs = p.parts.filter((s) => s.value > 0);
          return (
            <g key={p.key} className="mark" onMouseEnter={() => setHover(i)}>
              <rect x={left + i * slot} y={top} width={slot} height={H - bottom - top} fill="transparent" />
              {segs.map((s, j) => {
                const y0 = y(base);
                const y1 = y(base + s.value);
                base += s.value;
                const h = Math.max(y0 - y1 - (j < segs.length - 1 ? 2 : 0), 1);
                const isTop = j === segs.length - 1;
                const r = Math.min(4, h, bar / 2);
                const d = isTop
                  ? `M${x},${y1 + h} V${y1 + r} Q${x},${y1} ${x + r},${y1} H${x + bar - r} Q${x + bar},${y1} ${x + bar},${y1 + r} V${y1 + h} Z`
                  : `M${x},${y1} H${x + bar} V${y1 + h} H${x} Z`;
                return <path key={s.key} d={d} fill={s.color} />;
              })}
              {(i % every === 0 || i === points.length - 1) && (
                <text x={x + bar / 2} y={H - 7} fontSize="9.5" textAnchor="middle" fill="var(--app-chart-axis)">{p.label}</text>
              )}
            </g>
          );
        })}
      </svg>
      {shown && hover !== null && (
        <div style={{ position: 'absolute', top: 0, left: `${((left + hover * slot + slot / 2) / W) * 100}%`,
          transform: 'translateX(-50%)', pointerEvents: 'none', background: 'var(--app-surface)',
          border: '1px solid var(--app-border)', borderRadius: 8, padding: '6px 10px', boxShadow: 'var(--mantine-shadow-md)',
          whiteSpace: 'nowrap', zIndex: 2 }}>
          <Text size="xs" fw={650}>{shown.label}</Text>
          {shown.parts.map((s) => (
            <Group key={s.key} gap={6} wrap="nowrap">
              <span style={{ width: 8, height: 8, borderRadius: 999, background: s.color, display: 'inline-block' }} />
              <Text size="xs">{s.label}: <b className="num">{s.value}</b></Text>
            </Group>
          ))}
        </div>
      )}
    </div>
  );
}
