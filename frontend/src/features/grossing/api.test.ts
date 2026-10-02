import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { base64, syntheticBytes, sampleSha } from './api';

const sample = readFileSync(new URL('../../../../backend/src/main/resources/grossing-assets/synthetic-v1.png', import.meta.url));
const buffer = (bytes: Uint8Array) => new Uint8Array(bytes).buffer;
describe('controlled synthetic photo boundary', () => {
  it('accepts the bundled PNG only, preserving bytes and digest', async () => {
    const bytes = await syntheticBytes(buffer(sample));
    expect(bytes.byteLength).toBe(656);
    expect(Buffer.from(base64(bytes), 'base64')).toEqual(sample);
    expect(sampleSha).toHaveLength(64);
  });
  it('rejects empty, oversized, changed and unexpected objects before upload or display', async () => {
    for (const bytes of [new Uint8Array(), new Uint8Array(16385), new TextEncoder().encode('<svg onload="alert(1)"/>')]) {
      await expect(syntheticBytes(buffer(bytes))).rejects.toThrow();
    }
    const changed = new Uint8Array(sample); changed[100] ^= 1;
    await expect(syntheticBytes(buffer(changed))).rejects.toThrow();
    await expect(syntheticBytes(buffer(sample), '0'.repeat(64))).rejects.toThrow();
  });
});
