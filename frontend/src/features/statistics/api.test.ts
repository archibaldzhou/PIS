import { describe, it, expect } from 'vitest';
import { parseView } from './api';
import { statisticsView, statisticsId } from '../../../ui-tests/fixtures/statistics';
describe('statistics boundary', () => {
  it('keeps true zero separate from no denominator and open waiting separate from TAT', () => { const v = parseView(statisticsView('scope', 'RECEPTION'), 'scope', statisticsId); expect(v.summaries[0].ratio).toBe(0); expect(v.summaries[3].ratio).toBeNull(); expect(v.summaries[0].medianSeconds).toBeNull(); expect(v.facts[0].durationSeconds).toBe(3600); });
  it('rejects cross-scope and cross-snapshot responses', () => { expect(() => parseView(statisticsView('other'), 'scope', statisticsId)).toThrow(); expect(() => parseView(statisticsView('scope'), 'scope', 'other')).toThrow(); });
  it('rejects invented zero from missing timestamps/status', () => { const v = statisticsView('scope', 'RECEPTION'); v.facts[0].status = 'UNKNOWN'; expect(() => parseView(v, 'scope', statisticsId)).toThrow(); });
  it('rejects bad denominators and missing metric definitions', () => { const v = statisticsView('scope'); v.summaries[3].ratio = 0; expect(() => parseView(v, 'scope', statisticsId)).toThrow(); v.summaries.pop(); expect(() => parseView(v, 'scope', statisticsId)).toThrow(); });
  it('rejects unbounded pages and malformed hashes', () => { const v = statisticsView('scope'); v.page = 251; expect(() => parseView(v, 'scope', statisticsId)).toThrow(); v.page = 1; v.factsHash = 'bad'; expect(() => parseView(v, 'scope', statisticsId)).toThrow(); });
});
