import { describe, expect, it } from 'vitest';
import { inverse, project, transform, validate, measure } from './roi-geometry';
describe('canonical image pixel coordinates', () => {
 it('round trips rotation flip pan zoom and crop over deterministic samples', () => { for (const rotation of [0, 30, 90, 180, 270, 359]) for (const flip of [false, true]) for (const zoom of [.01, .3, 1, 10]) for (let i = 0; i < 20; i++) { const m = transform(rotation, flip, zoom, { x: 723, y: -341 }, { x: 60, y: 35, width: 220, height: 180 }), p = { x: i * 23.1, y: i * 15.7 }, back = project(project(p, m), inverse(m)); expect(back.x).toBeCloseTo(p.x, 7); expect(back.y).toBeCloseTo(p.y, 7); } });
 it('rejects singular, invalid and non-finite transforms', () => { expect(() => inverse([0, 0, 0, 0, 0, 0])).toThrow(); expect(() => transform(0, false, 0, { x: 0, y: 0 }, { x: 0, y: 0, width: 1, height: 1 })).toThrow(); expect(() => inverse([NaN, 0, 0, 1, 0, 0])).toThrow(); });
 it('rejects self intersections, excess vertices, duplicate points and image boundary violations', () => { expect(() => validate('POLYGON', [{ x: 0, y: 0 }, { x: 10, y: 10 }, { x: 0, y: 10 }, { x: 10, y: 0 }], 512, 384)).toThrow(); for (const x of [NaN, Infinity, -1, 513]) expect(() => validate('POINT', [{ x, y: 0 }], 512, 384)).toThrow(); expect(() => validate('POLYGON', Array.from({ length: 33 }, (_, i) => ({ x: i, y: i })), 512, 384)).toThrow(); expect(() => validate('RECTANGLE', [{ x: 0, y: 1 }, { x: 0, y: 10 }], 512, 384)).toThrow(); });
 it('keeps anisotropic X/Y units separate and never invents calibration', () => { const p = [{ x: 0, y: 0 }, { x: 10, y: 20 }]; expect(measure('RECTANGLE', p)).toEqual({ length: 60, area: 200, unit: 'px' }); expect(measure('RECTANGLE', p, { version: 7, mppX: .5, mppY: 2 })).toEqual({ length: 90, area: 200, unit: '合成µm' }); expect(() => measure('RECTANGLE', p, { version: 1, mppX: NaN, mppY: 2 })).toThrow(); });
});

it('measures a fixed anisotropic triangle independently of winding and repeated transforms', () => {
 const points = [{ x: 0, y: 0 }, { x: 12, y: 0 }, { x: 0, y: 8 }];
 for (const p of [points, [...points].reverse()]) {
  const m = measure('POLYGON', p, { version: 3, mppX: .25, mppY: 2 });
  expect(m.area).toBe(24); expect(m.length).toBeCloseTo(19 + Math.hypot(3, 16), 10);
  const matrix = transform(137, true, .27, { x: -451, y: 97 }, { x: 1, y: 2, width: 127, height: 111 });
  let q = p;
  for (let i = 0; i < 100; i++) q = q.map(v => project(project(v, matrix), inverse(matrix)));
  expect(measure('POLYGON', q, { version: 3, mppX: .25, mppY: 2 }).area).toBeCloseTo(24, 7);
 }
});
