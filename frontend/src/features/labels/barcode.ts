// Standard Code39 symbol mapping, referenced from ZXing (Copyright 2008 ZXing authors).
// Apache-2.0 attribution/license: /third-party/NOTICE.md and Apache-2.0.txt.
const alphabet = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%';
const patterns = [0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064,
  0x109, 0x049, 0x148, 0x019, 0x118, 0x058, 0x00d, 0x10c, 0x04c, 0x01c,
  0x103, 0x043, 0x142, 0x013, 0x112, 0x052, 0x007, 0x106, 0x046, 0x016,
  0x181, 0x0c1, 0x1c0, 0x091, 0x190, 0x0d0, 0x085, 0x184, 0x0c4, 0x0a8, 0x0a2, 0x08a, 0x02a];
export function validBarcode(value: string): boolean {
  if (value.length !== 34 || !/^S[0-9A-F]{32}[0-9A-Z. $/+%-]$/.test(value)) return false;
  const sum = [...value.slice(0, 33)].reduce((sum, c) => sum + alphabet.indexOf(c), 0);
  return value[33] === alphabet[sum % 43];
}
export function barcodeBars(value: string): { bars: { x: number; width: number }[]; width: number } {
  if (!validBarcode(value)) throw new Error('Invalid label barcode');
  let x = 10; const bars: { x: number; width: number }[] = [];
  for (const c of '*' + value + '*') {
    const pattern = c === '*' ? 0x094 : patterns[alphabet.indexOf(c)];
    for (let bit = 8; bit >= 0; bit--) {
      const width = (pattern & (1 << bit)) ? 2 : 1;
      if (bit % 2 === 0) bars.push({ x, width });
      x += width;
    }
    x++; // narrow inter-character gap
  }
  return { bars, width: x - 1 + 10 };
}
