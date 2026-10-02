import { describe, expect, it } from 'vitest';
import { availableActions, parseTask } from './api';
const task = { id: 'synthetic-task', cassetteId: 'synthetic-box', kind: 'PROCESSING', state: 'QUEUED', ownerId: null, predecessorId: null, reworkOf: null, version: 0, createdBy: 'a', createdAt: '2026-01-01T00:00:00Z' };
describe('technical UI boundary', () => {
  it('rejects unknown real-completion states instead of treating them as success', () => {
    for (const state of ['DONE', 'PRINTED', 'unknown', null]) expect(() => parseTask({ ...task, state })).toThrow();
    expect(() => parseTask({ ...task, version: -1 })).toThrow();
    expect(() => parseTask({ ...task, kind: 'INVENTED' })).toThrow();
    expect(parseTask(task).state).toBe('QUEUED');
  });
  it('separates pending handoff, current ownership and terminal rework', () => {
    expect(availableActions(parseTask({ ...task, state: 'ACTIVE', ownerId: 'a' }), 'b')).toEqual([]);
    expect(availableActions(parseTask({ ...task, state: 'HANDOFF_PENDING', ownerId: 'a' }), 'a')).toEqual(['withdraw', 'abort']);
    expect(availableActions(parseTask({ ...task, state: 'HANDOFF_PENDING', ownerId: 'a' }), 'b')).toEqual(['accept']);
    for (const state of ['SIMULATED_DONE', 'ABORTED']) expect(availableActions(parseTask({ ...task, state, ownerId: 'a' }), 'a')).toEqual(['rework']);
  });
});
