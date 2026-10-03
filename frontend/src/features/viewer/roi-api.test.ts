import { describe, expect, it } from 'vitest';
import { parse } from './roi-api';
import type { Manifest } from './api';
const m: Manifest = { content: { hospitalId: 'h', requestId: 'r', scanId: 's', slideId: 'slide', objectId: 'o', objectHash: 'a'.repeat(64), scanVersion: 2, provider: 'SYN-RGB-PYRAMID-1', width: 512, height: 384, tileSize: 128, maxLevel: 9, manifestHash: 'b'.repeat(64), tiles: [] }, publicationVersion: 1, capability: 'SYNTHETIC_RGB_ONLY', calibration: 'UNAVAILABLE_NO_PHYSICAL_SCALE' };
const row = { roiId: 'roi', revision: 0, collectionVersion: 0, authorId: 'a', actorId: 'a', kind: 'POINT', points: [{ x: 1, y: 2 }], deleted: false, reason: 'Synthetic', calibrationVersion: null, measurement: { length: 0, area: 0, unit: 'px', calibrationVersion: null }, createdAt: '2026-10-03T00:00:00Z' };
const v = { requestId: 'r', scanId: 's', manifestHash: 'b'.repeat(64), version: 0, actorId: 'a', calibration: null, items: [row], historyOnly: false };
describe('ROI exact resource response boundary', () => {
 it('accepts unknown calibration only as pixels', () => { expect(parse(v, m).items[0].measurement.unit).toBe('px'); });
 it('rejects late foreign scan/hash/history payloads', () => { for (const patch of [{ requestId: 'other' }, { scanId: 'other' }, { manifestHash: 'c'.repeat(64) }, { historyOnly: true }]) expect(() => parse({ ...v, ...patch }, m)).toThrow(); });
 it('rejects duplicate ROI, excess counts, fake physical units and non-finite coordinates', () => { for (const items of [[row, row], Array(51).fill(row), [{ ...row, measurement: { ...row.measurement, unit: 'SYNTHETIC_um' } }], [{ ...row, points: [{ x: NaN, y: 2 }] }]]) expect(() => parse({ ...v, items }, m)).toThrow(); });
});
