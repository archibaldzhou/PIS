import { fetchCsrf, readJson, request } from '../../api';
export interface Cassette { id: string; number: string; site: string; pieces: number; state: string; version: number; containerIds: string[] }
export interface Photo { id: string; containerId: string; caption: string; sha256: string; bytes: number; width: number; height: number; withdrawnAt: string | null }
export interface Revision { version: number; description: string; reason: string; actorId: string; createdAt: string }
export interface GrossRecord { id: string; state: string; version: number; description: string; cassettes: Cassette[]; photos: Photo[]; revisions: Revision[];
  events: { id: string; version: number; action: string; targetId: string | null; reason: string; actorId: string; occurredAt: string }[] }
export interface GrossView { request: { id: string; version: number; requestNumber: string; patientId: string; patientLabel: string; encounterNumber: string; containers: { id: string; site: string }[] }; caseId: string; caseNumber: string; record: GrossRecord | null }
export interface GrossCommand { path: string; body: Record<string, string | number | string[]> }
function obj(x: unknown): Record<string, unknown> { if (!x || typeof x !== 'object' || Array.isArray(x)) throw new Error('Invalid grossing response'); return x as Record<string, unknown>; }
function str(x: unknown): string { if (typeof x !== 'string') throw new Error('Invalid grossing response'); return x; }
function num(x: unknown): number { if (typeof x !== 'number' || !Number.isSafeInteger(x) || x < 0) throw new Error('Invalid grossing response'); return x; }
function list<T>(x: unknown, parse: (v: unknown) => T): T[] { if (!Array.isArray(x)) throw new Error('Invalid grossing response'); return x.map(parse); }
function nullable(x: unknown) { return x === null ? null : str(x); }
function record(x: unknown): GrossRecord {
  const r = obj(x); const state = str(r.state); if (!['DRAFT', 'COMPLETED', 'CANCELLED'].includes(state)) throw new Error('Unknown grossing state');
  return { id: str(r.id), state, version: num(r.version), description: str(r.description),
    cassettes: list(r.cassettes, item => { const b = obj(item); return { id: str(b.id), number: str(b.number), site: str(b.site), pieces: num(b.pieces), state: str(b.state), version: num(b.version), containerIds: list(b.containerIds, str) }; }),
    photos: list(r.photos, item => { const p = obj(item); return { id: str(p.id), containerId: str(p.containerId), caption: str(p.caption), sha256: str(p.sha256), bytes: num(p.bytes), width: num(p.width), height: num(p.height), withdrawnAt: nullable(p.withdrawnAt) }; }),
    revisions: list(r.revisions, item => { const v = obj(item); return { version: num(v.version), description: str(v.description), reason: str(v.reason), actorId: str(v.actorId), createdAt: str(v.createdAt) }; }),
    events: list(r.events, item => { const e = obj(item); return { id: str(e.id), version: num(e.version), action: str(e.action), targetId: nullable(e.targetId), reason: str(e.reason), actorId: str(e.actorId), occurredAt: str(e.occurredAt) }; }) };
}
export async function loadGross(id: string, signal: AbortSignal): Promise<GrossView> {
  const r = obj(await readJson(await request('/api/grossing/requests/' + encodeURIComponent(id), { signal }))); const q = obj(r.request);
  if (q.id !== id) throw new Error('Mismatched request');
  return { request: { id, version: num(q.version), requestNumber: str(q.requestNumber), patientId: str(q.patientId), patientLabel: q.patientLabel === null ? '姓名未知' : str(q.patientLabel), encounterNumber: str(q.encounterNumber),
    containers: list(q.containers, item => { const c = obj(item); return { id: str(c.id), site: str(c.site) }; }) }, caseId: str(r.caseId), caseNumber: str(r.caseNumber), record: r.record === null ? null : record(r.record) };
}
export async function grossCommand(input: GrossCommand, key: string): Promise<string> {
  const abort = new AbortController(); const timer = setTimeout(() => abort.abort(), 15000);
  try {
    const csrf = await fetchCsrf(abort.signal);
    const result = obj(await readJson(await request(input.path, { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(input.body) })));
    return str(obj(result.receipt).resourceId);
  } finally { clearTimeout(timer); }
}
export const sampleSha = 'adaacd24ed0218efbde244a9a0c314cf26b8f27f612146bd9d164971fcb92623';
export async function syntheticBytes(bytes: ArrayBuffer, expected = sampleSha): Promise<Uint8Array> {
  if (bytes.byteLength > 16384 || bytes.byteLength === 0) throw new Error('只接受不超过16KiB的受控合成PNG');
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('');
  if (digest !== expected || digest !== sampleSha) throw new Error('只接受随程序发布的合成PNG样本，不上传其他照片');
  return new Uint8Array(bytes);
}
export async function photoBytes(path: string, signal?: AbortSignal, expected = sampleSha): Promise<Uint8Array> {
  const response = await request(path, { signal });
  if (!response.headers.get('Content-Type')?.startsWith('image/png')) throw new Error('图像类型不匹配');
  return syntheticBytes(await response.arrayBuffer(), expected);
}
export function base64(bytes: Uint8Array): string { return btoa(Array.from(bytes, b => String.fromCharCode(b)).join('')); }
