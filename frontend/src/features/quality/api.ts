import { fetchCsrf, readJson, request } from '../../api';
export const states = ['NOT_ASSESSED', 'PASS', 'FAIL', 'PENDING', 'IDENTITY_MISMATCH', 'REVOKED', 'REWORK_REQUIRED', 'INVALIDATED', 'SOURCE_QUARANTINED'] as const;
export interface Subject { id: string; requestId: string; patientId: string; caseId: string; number: string; kind: string; route: string; state: string; version: number; taskId: string | null; taskVersion: number | null; blockId: string | null }
export interface Head { state: string; version: number; assessmentId: string; repairTaskId: string | null }
export interface Item { subject: Subject; head: Head | null; effectiveState: string }
export interface Assessment { id: string; materialVersion: number; taskId: string | null; taskVersion: number | null; standardVersion: string; outcome: string; reason: string; actorId: string; occurredAt: string }
export interface Event { id: string; version: number; action: string; assessmentId: string; relatedTaskId: string | null; reason: string; actorId: string; occurredAt: string }
export interface Detail { item: Item; assessments: Assessment[]; events: Event[]; exceptionReleaseEnabled: false }
export interface Command { path: string; body: Record<string, string | number | null> }
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid quality response'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw new Error('Invalid quality text'); return v; }
function num(v: unknown): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw new Error('Invalid quality version'); return v; }
function nullable(v: unknown) { return v === null ? null : str(v); }
function list<T>(v: unknown, parser: (v: unknown) => T): T[] { if (!Array.isArray(v)) throw new Error('Invalid quality list'); return v.map(parser); }
export function parseItem(v: unknown): Item {
  const item = obj(v), s = obj(item.subject), h = item.head === null ? null : obj(item.head);
  const effectiveState = str(item.effectiveState);
  if (!(states as readonly string[]).includes(effectiveState) || (h && !['PASS', 'FAIL', 'PENDING', 'IDENTITY_MISMATCH', 'REVOKED', 'REWORK_REQUIRED', 'INVALIDATED'].includes(str(h.state)))) throw new Error('Unknown quality state');
  if (!['ACTIVE', 'VOID', 'SOURCE_QUARANTINED'].includes(str(s.state)) || !['BLOCK', 'SLIDE'].includes(str(s.kind))) throw new Error('Unknown quality subject');
  const taskId = nullable(s.taskId), taskVersion = s.taskVersion === null ? null : num(s.taskVersion);
  if ((taskId === null) !== (taskVersion === null) || (h && h.materialId !== s.id)) throw new Error('Mismatched quality source');
  return { subject: { id: str(s.id), requestId: str(s.requestId), patientId: str(s.patientId), caseId: str(s.caseId), number: str(s.number), kind: str(s.kind), route: str(s.route), state: str(s.state), version: num(s.version), taskId, taskVersion, blockId: nullable(s.blockId) }, head: h ? { state: str(h.state), version: num(h.version), assessmentId: str(h.assessmentId), repairTaskId: nullable(h.repairTaskId) } : null, effectiveState };
}
export async function loadList(id: string, signal: AbortSignal) {
  const v = obj(await readJson(await request('/api/quality/requests/' + id, { signal }))), items = list(v.items, parseItem);
  if (v.requestId !== id || items.some(i => i.subject.requestId !== id)) throw new Error('Mismatched quality request'); return items;
}
export async function loadDetail(id: string, signal: AbortSignal): Promise<Detail> {
  const d = obj(await readJson(await request('/api/quality/materials/' + id, { signal }))), item = parseItem(d.item);
  if (item.subject.id !== id || d.exceptionReleaseEnabled !== false) throw new Error('Unsupported quality release contract');
  return { item, exceptionReleaseEnabled: false, assessments: list(d.assessments, v => { const a = obj(v); if (!['PASS', 'FAIL', 'PENDING', 'IDENTITY_MISMATCH'].includes(str(a.outcome)) || a.standardVersion !== 'SYN-MATERIAL-QC-1') throw new Error('Unknown quality standard/outcome'); return { id: str(a.id), materialVersion: num(a.materialVersion), taskId: nullable(a.taskId), taskVersion: a.taskVersion === null ? null : num(a.taskVersion), standardVersion: str(a.standardVersion), outcome: str(a.outcome), reason: str(a.reason), actorId: str(a.actorId), occurredAt: str(a.occurredAt) }; }), events: list(d.events, v => { const e = obj(v); if (!['ASSESS', 'REVOKE', 'REWORK', 'INVALIDATE'].includes(str(e.action))) throw new Error('Unknown quality event'); return { id: str(e.id), version: num(e.version), action: str(e.action), assessmentId: str(e.assessmentId), relatedTaskId: nullable(e.relatedTaskId), reason: str(e.reason), actorId: str(e.actorId), occurredAt: str(e.occurredAt) }; }) };
}
export async function send(command: Command, key: string): Promise<string> {
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
  try { const csrf = await fetchCsrf(abort.signal); const result = obj(await readJson(await request(command.path, { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(command.body) }))); return str(obj(result.receipt).resourceId); }
  finally { clearTimeout(timer); }
}
