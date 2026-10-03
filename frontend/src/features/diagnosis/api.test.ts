import { describe, it, expect } from 'vitest';
import { parseItem, parseDetail } from './api';
const item = { caseId: 'case', requestId: 'request', patientId: 'patient', number: 'SYN', state: 'UNASSIGNED', version: -1, ownerId: null, ready: false };
describe('diagnosis boundary', () => {
  it('keeps unassigned and blocked explicit', () => { expect(parseItem(item)).toEqual(item); expect(() => parseItem({ ...item, ready: null })).toThrow(); });
  it('rejects invented states and missing ownership', () => { expect(() => parseItem({ ...item, state: 'SIGNED' })).toThrow(); expect(() => parseItem({ ...item, state: 'ACTIVE', version: 0 })).toThrow(); expect(() => parseItem({ ...item, version: 0 })).toThrow(); });
  it('binds detail to selected stable case and exact events', () => { const d = { item, actorId: 'actor', canAssign: false, canDiagnose: true, candidates: [], events: [] }; expect(parseDetail(d, 'case').item.ready).toBe(false); expect(() => parseDetail(d, 'other')).toThrow(); expect(() => parseDetail({ ...d, events: [{ action: 'SIGN' }] }, 'case')).toThrow(); });
});
