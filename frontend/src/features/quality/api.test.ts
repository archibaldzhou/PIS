import { describe, it, expect } from 'vitest';
import { parseItem } from './api';
const source = { id: 'material', requestId: 'request', patientId: 'patient', caseId: 'case', number: 'DEV-S-SYN', kind: 'SLIDE', route: 'DIRECT_CYTOLOGY', state: 'ACTIVE', version: 0, taskId: null, taskVersion: null, blockId: null };
describe('quality boundary never interprets an unknown or pending state as pass', () => {
  it('keeps direct-cytology absence and explicit pending/failed states', () => {
    for (const state of ['NOT_ASSESSED', 'PENDING', 'FAIL', 'IDENTITY_MISMATCH', 'REVOKED', 'REWORK_REQUIRED', 'INVALIDATED', 'SOURCE_QUARANTINED']) expect(parseItem({ subject: source, head: null, effectiveState: state }).effectiveState).toBe(state);
    expect(parseItem({ subject: source, head: null, effectiveState: 'NOT_ASSESSED' }).subject.taskId).toBeNull();
  });
  it('rejects unknown states, mismatched material and inconsistent task snapshots', () => {
    expect(() => parseItem({ subject: source, head: null, effectiveState: 'APPROVED_BY_ADMIN' })).toThrow();
    expect(() => parseItem({ subject: { ...source, taskId: 'task' }, head: null, effectiveState: 'PASS' })).toThrow();
    expect(() => parseItem({ subject: source, head: { materialId: 'foreign', state: 'PASS', version: 0, assessmentId: 'a', repairTaskId: null }, effectiveState: 'PASS' })).toThrow();
  });
});

it('keeps cytology source quarantine visible without presenting an empty QC list', () => { expect(parseItem({ subject: { ...source, state: 'SOURCE_QUARANTINED', route: 'CYTOLOGY_SLIDE' }, head: null, effectiveState: 'SOURCE_QUARANTINED' }).effectiveState).toBe('SOURCE_QUARANTINED'); });
