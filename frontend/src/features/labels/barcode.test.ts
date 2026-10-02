import { describe, expect, it } from 'vitest';
import { barcodeBars, validBarcode } from './barcode';
describe('restricted Code39 label identity', () => {
  const zero = 'S' + '0'.repeat(32) + 'S';
  it('checks independent Mod43 vectors and rejects altered/malformed content', () => {
    expect(validBarcode(zero)).toBe(true);
    expect(validBarcode('S' + '0'.repeat(31) + '1T')).toBe(true);
    for (const invalid of [zero.slice(0, -1) + 'T', zero.toLowerCase(), '*' + zero + '*', ' ' + zero, zero + ' ', zero + '\n', '<script>']) {
      expect(validBarcode(invalid)).toBe(false); expect(() => barcodeBars(invalid)).toThrow();
    }
  });
  it('encodes the fixed start/stop symbol, whole payload and quiet zones', () => {
    const result = barcodeBars(zero);
    expect(result.width).toBe(487); // 36 symbols * 12 modules + 35 gaps + 20 quiet modules
    expect(result.bars).toHaveLength(180); // five black bars per start/data/check/stop symbol
    expect(result.bars.slice(0, 5)).toEqual([{ x: 10, width: 1 }, { x: 13, width: 1 }, { x: 15, width: 2 }, { x: 18, width: 2 }, { x: 21, width: 1 }]);
    expect(result.bars.at(-1)).toEqual({ x: 476, width: 1 });
  });
});
