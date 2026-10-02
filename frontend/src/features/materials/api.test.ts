import { describe, expect, it } from 'vitest';
import { modes, parseMaterial } from './api';
const direct = { id: 'synthetic-slide', requestId: 'synthetic-request', patientId: 'synthetic-patient', caseId: 'synthetic-case', kind: 'SLIDE', route: 'DIRECT_CYTOLOGY', operation: 'ORIGINAL', number: 'DEV-S-SYNTHETIC', barcode: 'synthetic-only', recordId: null, cassetteId: null, containerId: 'synthetic-container', blockId: null, sourceSlideId: null, technicalTaskId: null, state: 'ACTIVE', version: 0 };
describe('explicit material lineage boundary', () => {
  it('accepts direct cytology without a block and does not offer recut as a fallback', () => {
    const material = parseMaterial(direct); expect(material.blockId).toBeNull(); expect(modes(material)).toEqual(['void', 'labels']);
    expect(modes({ ...material, state: 'VOID' })).toEqual(['labels']);
  });
  it('rejects unknown routes and fabricated direct-source blocks', () => {
    for (const patch of [{ route: 'UNKNOWN' }, { state: 'PRINTED' }, { blockId: 'invented-block' }, { operation: 'RECUT' }, { cassetteId: 'invented-cassette' }, { version: -1 }]) expect(() => parseMaterial({ ...direct, ...patch })).toThrow();
    expect(() => parseMaterial({ ...direct, route: 'BLOCK_BASED' })).toThrow();
  });
});
