import type { Metric, View } from '../../src/features/statistics/api';
export const statisticsId = '99999999-9999-4999-8999-999999999999';
export function statisticsView(scope: string, metric: Metric | null = null): View {
  const today = new Date().toISOString().slice(0, 10);
  return { id: statisticsId, scopeId: scope, definition: 'SYN-STATS-1', from: today, to: today, zone: 'UTC', cutoff: '2026-10-03T12:00:00Z', factsHash: 'a'.repeat(64), canDrill: true, metric, sort: 'ENTITY', page: 1, total: metric === 'RECEPTION' ? 1 : 0,
    summaries: ['RECEPTION', 'TECHNICAL', 'REPORT', 'QC'].map(m => ({ metric: m as Metric, availability: m === 'RECEPTION' ? 'DATA' : 'NO_DATA', cohort: m === 'RECEPTION' ? 1 : 0, completed: 0, open: m === 'RECEPTION' ? 1 : 0, unknown: 0, excluded: 0, numerator: 0, denominator: m === 'RECEPTION' ? 1 : 0, ratio: m === 'RECEPTION' ? 0 : null, medianSeconds: null })),
    facts: metric === 'RECEPTION' ? [{ metric, entityId: 'synthetic-request', requestId: 'synthetic-request', startEvent: 'synthetic-submit', endEvent: null, startAt: '2026-10-03T11:00:00Z', endAt: null, status: 'OPEN', durationSeconds: 3600, sourceBasis: '{}' }] : [],
  };
}
