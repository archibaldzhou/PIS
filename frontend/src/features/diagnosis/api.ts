import { fetchCsrf, readJson, request } from '../../api';
export const base = '/api/requests/diagnosis';
export interface Item { caseId: string; requestId: string; patientId: string; number: string; state: string; version: number; ownerId: string | null; ready: boolean }
export interface Detail { item: Item; actorId: string; canAssign: boolean; canDiagnose: boolean; candidates: { id: string; name: string }[]; events: { id: string; version: number; action: string; actorId: string; previousOwnerId: string | null; nextOwnerId: string; reason: string; occurredAt: string }[] }
export interface Filter { page: number; state: string }
export interface Command { caseId: string; action: string; body: { expectedVersion: number; confirmedCaseId: string; targetUserId: string | null; reason: string } }
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid diagnosis response'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw new Error('Invalid diagnosis text'); return v; }
function num(v: unknown, min = 0): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < min) throw new Error('Invalid diagnosis number'); return v; }
function bool(v: unknown): boolean { if (typeof v !== 'boolean') throw new Error('Invalid diagnosis flag'); return v; }
function nullable(v: unknown) { return v === null ? null : str(v); }
function list<T>(v: unknown, parse: (v: unknown) => T): T[] { if (!Array.isArray(v)) throw new Error('Invalid diagnosis list'); return v.map(parse); }
export function parseItem(v: unknown): Item {
  const i = obj(v), state = str(i.state), version = num(i.version, -1), ownerId = nullable(i.ownerId);
  if (!['UNASSIGNED', 'ASSIGNED', 'ACTIVE'].includes(state) || (state === 'UNASSIGNED' ? version !== -1 || ownerId !== null : version < 0 || ownerId === null)) throw new Error('Invalid diagnosis state');
  return { caseId: str(i.caseId), requestId: str(i.requestId), patientId: str(i.patientId), number: str(i.number), state, version, ownerId, ready: bool(i.ready) };
}
export async function loadList(scope: string, f: Filter, signal: AbortSignal) {
  const p = obj(await readJson(await request(base + '/scopes/' + scope + '?page=' + f.page + '&pageSize=10&state=' + encodeURIComponent(f.state), { signal })));
  if (p.page !== f.page || p.pageSize !== 10) throw new Error('Mismatched diagnosis page');
  return { total: num(p.total), items: list(p.items, parseItem) };
}
export function parseDetail(value: unknown, id: string): Detail {
  const d = obj(value), item = parseItem(d.item); if (item.caseId !== id) throw new Error('Mismatched diagnosis case');
  return { item, actorId: str(d.actorId), canAssign: bool(d.canAssign), canDiagnose: bool(d.canDiagnose), candidates: list(d.candidates, v => { const c = obj(v); return { id: str(c.id), name: str(c.name) }; }), events: list(d.events, v => { const e = obj(v), action = str(e.action); if (!['ASSIGN', 'CLAIM', 'TRANSFER'].includes(action)) throw new Error('Unknown diagnosis event'); return { id: str(e.id), version: num(e.version), action, actorId: str(e.actorId), previousOwnerId: nullable(e.previousOwnerId), nextOwnerId: str(e.nextOwnerId), reason: str(e.reason), occurredAt: str(e.occurredAt) }; }) };
}
export async function loadDetail(id: string, signal: AbortSignal) { return parseDetail(await readJson(await request(base + '/cases/' + id, { signal })), id); }
export async function send(c: Command, key: string) {
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
  try {
    const csrf = await fetchCsrf(abort.signal); const r = obj(await readJson(await request(base + '/cases/' + c.caseId + '/' + c.action, { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(c.body) })));
    const receipt = obj(r.receipt);
    if (receipt.resourceId !== c.caseId || receipt.resourceType !== 'DIAGNOSIS_ASSIGNMENT' || receipt.status !== 200 || num(receipt.version) !== c.body.expectedVersion + 1) throw new Error('Mismatched diagnosis receipt');
    return true;
  } finally { clearTimeout(timer); }
}
