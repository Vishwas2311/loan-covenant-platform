import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { r3 } from '../api/endpoints3';
import type { MeasureInfo } from '../format';

/** Measure names and units by code, for reading rules out in words. Empty until loaded (or if it fails). */
export function useMeasures(): Map<string, MeasureInfo> {
  const { data } = useQuery({ queryKey: ['measures', 'units'], queryFn: r3.measures, staleTime: 300_000 });
  return useMemo(() => new Map((Array.isArray(data) ? data : []).map((m) => [m.code, { name: m.name, unit: m.unit }])),
    [data]);
}
