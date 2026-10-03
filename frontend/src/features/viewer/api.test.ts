import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { parseManifest, binary, type Tile } from './api';
const tiles = JSON.parse(readFileSync(new URL('../../../ui-tests/fixtures/viewer/a/tiles.json', import.meta.url), 'utf8')) as Tile[];
const content = { hospitalId: 'hospital', requestId: 'request', scanId: 'scan', slideId: 'slide', objectId: 'object', objectHash: 'a'.repeat(64), scanVersion: 2, provider: 'SYN-RGB-PYRAMID-1', width: 512, height: 384, tileSize: 128, maxLevel: 9, manifestHash: 'b'.repeat(64), tiles };
const manifest = { content, publicationVersion: 1, capability: 'SYNTHETIC_RGB_ONLY', calibration: 'UNAVAILABLE_NO_PHYSICAL_SCALE' };
afterEach(() => vi.unstubAllGlobals());
describe('exact synthetic tile contract', () => {
 it('accepts a complete bounded real generator manifest', () => { expect(parseManifest(manifest, 'request', 'scan', 1).content.tiles).toHaveLength(24); });
 it('rejects mismatched scan/publication, unknown format and invented calibration', () => { expect(() => parseManifest(manifest, 'other', 'scan', 1)).toThrow(); expect(() => parseManifest(manifest, 'request', 'other', 1)).toThrow(); expect(() => parseManifest(manifest, 'request', 'scan', 2)).toThrow(); expect(() => parseManifest({ ...manifest, calibration: 'CLINICAL' }, 'request', 'scan', 1)).toThrow(); expect(() => parseManifest({ ...manifest, content: { ...content, provider: 'SVS' } }, 'request', 'scan', 1)).toThrow(); });
 it('rejects missing, duplicated, wrong-size or oversized levels', () => { for (const patch of [{ tiles: tiles.slice(1) }, { tiles: [...tiles, tiles[0]] }, { tiles: [{ ...tiles[0], width: 2 }, ...tiles.slice(1)] }, { width: 513 }, { maxLevel: 10 }]) expect(() => parseManifest({ ...manifest, content: { ...content, ...patch } }, 'request', 'scan', 1)).toThrow(); });
 it('verifies actual binary PNG magic, byte length and hash before decoding', async () => { const t = tiles.find(t => t.level === 9 && t.x === 0 && t.y === 0); if (!t) throw Error('missing fixture'); const b = new Uint8Array(readFileSync(new URL('../../../ui-tests/fixtures/viewer/a/9-0-0.png', import.meta.url))); const headers = { 'Content-Type': 'image/png', 'Content-Length': String(b.length), 'X-Content-SHA256': t.sha256 }; vi.stubGlobal('fetch', vi.fn(async () => new Response(b.buffer, { headers }))); expect((await binary('/api/requests/r/scans/s/viewer/tiles/9/0/0', t, new AbortController().signal)).size).toBe(b.length); b[0] = 0; await expect(binary('/api/requests/r/scans/s/viewer/tiles/9/0/0', t, new AbortController().signal)).rejects.toThrow('PNG'); });
 it('never fetches external URLs or traversal paths', async () => { const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); await expect(binary('https://example.invalid/tile', tiles[0], new AbortController().signal)).rejects.toThrow(); await expect(binary('/api/requests/../secret', tiles[0], new AbortController().signal)).rejects.toThrow(); expect(fetch).not.toHaveBeenCalled(); });
});

describe('bounded corrupt streaming tile response', () => {
 it('cancels a body that exceeds its declared authorized tile size before draining it', async () => { let reads = 0, cancelled = false; const tile = tiles[0]; const stream = new ReadableStream<Uint8Array>({ pull(c) { reads++; if (reads <= 100) c.enqueue(new Uint8Array(65536)); else c.close(); }, cancel() { cancelled = true; } }, { highWaterMark: 0 }); vi.stubGlobal('fetch', vi.fn(async () => new Response(stream, { headers: { 'Content-Type': 'image/png', 'Content-Length': String(tile.size), 'X-Content-SHA256': tile.sha256 } }))); await expect(binary('/api/requests/r/scans/s/viewer/tiles/0/0/0', tile, new AbortController().signal)).rejects.toThrow(); expect(cancelled).toBe(true); expect(reads).toBe(1); });
});

it('accepts split PNG chunks, rejects truncation and cancels a pending read on abort', async () => {
 const t = tiles[0], b = new Uint8Array(readFileSync(new URL(`../../../ui-tests/fixtures/viewer/a/${t.level}-${t.x}-${t.y}.png`, import.meta.url)));
 const headers = { 'Content-Type': 'image/png', 'Content-Length': String(t.size), 'X-Content-SHA256': t.sha256 };
 vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream({ start(c) { c.enqueue(b.slice(0, 8)); c.enqueue(b.slice(8)); c.close(); } }), { headers })));
 expect((await binary('/api/requests/r/tile', t, new AbortController().signal)).size).toBe(t.size);
 vi.stubGlobal('fetch', vi.fn(async () => new Response(b.slice(0, -1), { headers })));
 await expect(binary('/api/requests/r/tile', t, new AbortController().signal)).rejects.toThrow('长度');
 let cancelled = false; let started: (() => void) | undefined;
 const pending = new Promise<void>(resolve => { started = resolve; });
 vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream({ pull() { started?.(); }, cancel() { cancelled = true; } }, { highWaterMark: 0 }), { headers })));
 const controller = new AbortController(), result = binary('/api/requests/r/tile', t, controller.signal);
 const assertion = expect(result).rejects.toMatchObject({ name: 'AbortError' });
 await pending; controller.abort(); await assertion; expect(cancelled).toBe(true);
});
