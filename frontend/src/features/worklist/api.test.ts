import { describe, expect, it } from 'vitest';
import { claimable, parseBatch, parsePage, type Batch } from './api';
const task = { kind: 'TECHNICAL', id: 'task', requestId: 'request', patientId: 'patient', requestNumber: 'DEV-SYN', state: 'QUEUED', version: 0, createdAt: '2026-01-01T00:00:00Z', cassetteId: 'box', blocked: false, active: true, dueAt: '2026-01-01T04:00:00Z', overdue: false };
const page = { total: 1, page: 1, pageSize: 10, asOf: '2026-01-01T04:00:00Z', syntheticDueMinutes: 240, items: [task] };
describe('worklist boundaries', () => {
  it('only selects explicit queued unblocked technical work', () => {
    expect(claimable(parsePage(page).items[0])).toBe(true);
    for (const change of [{ blocked: true }, { state: 'ACTIVE' }, { kind: 'QUALITY', state: 'PASS' }]) expect(claimable(parsePage({ ...page, items: [{ ...task, ...change }] }).items[0])).toBe(false);
  });
  it('rejects unknown states, duplicate identity and inconsistent deadlines', () => {
    expect(() => parsePage({ ...page, items: [{ ...task, state: 'AUTO_APPROVED' }] })).toThrow();
    expect(() => parsePage({ ...page, items: [task, task] })).toThrow();
    expect(() => parsePage({ ...page, items: [{ ...task, active: false, dueAt: null, overdue: true }] })).toThrow();
  });
  it('keeps partial/unknown results and rejects wrong task or batch receipts', () => {
    const batch: Batch = { batchId: 'batch', items: [{ taskId: 'one', expectedVersion: 0, confirmedCassetteId: 'box' }, { taskId: 'two', expectedVersion: 0, confirmedCassetteId: 'box' }], reason: 'Synthetic' };
    const result = { batchId: 'batch', items: [{ taskId: 'one', outcome: 'SUCCESS', status: 200, code: 'CLAIMED', version: 1, replayed: false }, { taskId: 'two', outcome: 'UNKNOWN', status: 503, code: 'WORKLIST_RESULT_UNCONFIRMED', version: null, replayed: false }] };
    expect(parseBatch(result, batch).items.map(i => i.outcome)).toEqual(['SUCCESS', 'UNKNOWN']);
    expect(() => parseBatch({ ...result, batchId: 'foreign' }, batch)).toThrow();
    expect(() => parseBatch({ ...result, items: [...result.items].reverse() }, batch)).toThrow();
    expect(() => parseBatch({ ...result, items: [result.items[0], { ...result.items[1], outcome: 'SUCCESS' }] }, batch)).toThrow();
  });
});
