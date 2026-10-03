import { describe, it, expect } from 'vitest';
import { parseView } from './consultationApi';
const revision = { id: 'r', caseId: 'c', version: 0, templateCode: 'SYN', templateVersion: 1, fields: { gross: '', microscopy: '', diagnosis: 'Synthetic', notes: '' }, assignmentVersion: 0, authorId: 'owner', reason: 'Synthetic', createdAt: '2026-10-03T00:00:00Z' };
const head = { id: 'h', caseId: 'c', revisionId: 'r', assignmentVersion: 0, materialBasis: '[{"id":"m","version":0,"qcVersion":0,"state":"PASS"}]', kind: 'REREAD', purpose: 'Synthetic', expiresAt: '2026-10-04T00:00:00Z', createdBy: 'owner', state: 'OPEN', version: 3, summaryId: null };
const member = { userId: 'u', state: 'ACCEPTED', opinionId: 'e', confirmedSummaryId: null };
const event = { id: 'e', version: 2, action: 'OPINION', actorId: 'u', targetId: null, disposition: 'UNKNOWN', content: 'Synthetic uncertain', reason: 'Synthetic', summaryId: null, adoptedRevisionId: null, recordedAt: '2026-10-03T00:00:00Z' };
const view = { caseId: 'c', patientId: 'p', number: 'SYN', actorId: 'owner', owner: true, consultations: [head], selected: head, basis: revision, members: [member], events: [event], opinions: [event], summary: null, candidates: [], invalidReason: null, ready: false, page: 1 };
describe('consultation exact version and consensus boundaries', () => {
 it('retains unknown opinions without manufacturing consensus', () => { const d = parseView(view, 'c', 'h', 1); expect(d.ready).toBe(false); expect(d.opinions[0].disposition).toBe('UNKNOWN'); });
 it('rejects late case, consultation and page responses', () => { expect(() => parseView(view, 'other', 'h', 1)).toThrow(); expect(() => parseView(view, 'c', 'other', 1)).toThrow(); expect(() => parseView(view, 'c', 'h', 2)).toThrow(); });
 it('requires exact bound report revision', () => expect(() => parseView({ ...view, basis: { ...revision, id: 'other' } }, 'c', 'h', 1)).toThrow());
 it('does not allow ready with missing confirmation or expired state', () => { for (const patch of [{ ready: true }, { ready: true, invalidReason: 'EXPIRED' }]) expect(() => parseView({ ...view, ...patch }, 'c', 'h', 1)).toThrow(); });
 it('rejects duplicate participants and invalid opinion states', () => { expect(() => parseView({ ...view, members: [member, member] }, 'c', 'h', 1)).toThrow(); expect(() => parseView({ ...view, events: [{ ...event, disposition: 'MAJORITY_DIAGNOSIS' }] }, 'c', 'h', 1)).toThrow(); });
 it('rejects excessive or reversed history and malformed material snapshots', () => { for (const events of [Array.from({ length: 21 }, () => event), [event, { ...event, version: 3 }]]) expect(() => parseView({ ...view, events }, 'c', 'h', 1)).toThrow(); expect(() => parseView({ ...view, selected: { ...head, materialBasis: '{}' } }, 'c', 'h', 1)).toThrow(); });
});
