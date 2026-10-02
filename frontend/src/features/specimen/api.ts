import { fetchCsrf, readJson, request } from '../../api';
export interface ReceptionView {
  request: { id: string; version: number; state: string; requestNumber: string; patientId: string; patientLabel: string; encounterNumber: string;
    containers: { id: string; site: string; laterality: string; materialQuantity: number; fixative: string; fixedAt: string | null }[] };
  caseNumber: string | null;
  events: { id: string; requestVersion: number; action: string; category: string | null; reason: string; actorId: string; occurredAt: string }[];
}
export type ReceptionCommand = { action: 'receive'; body: { expectedVersion: number; patientId: string; encounterNumber: string; containerIds: string[] } }
  | { action: 'exception'; body: { expectedVersion: number; category: string; reason: string } }
  | { action: 'return' | 'resolve'; body: { expectedVersion: number; reason: string } };
function object(x: unknown): Record<string, unknown> { if (!x || typeof x !== 'object' || Array.isArray(x)) throw new Error('Invalid response'); return x as Record<string, unknown>; }
function str(x: unknown): string { if (typeof x !== 'string') throw new Error('Invalid response'); return x; }
function num(x: unknown): number { if (typeof x !== 'number' || !Number.isSafeInteger(x) || x < 0) throw new Error('Invalid response'); return x; }
function nullable(x: unknown) { return x === null ? null : str(x); }
function list<T>(x: unknown, parse: (v: unknown) => T): T[] { if (!Array.isArray(x)) throw new Error('Invalid response'); return x.map(parse); }
export async function loadReception(id: string, signal: AbortSignal): Promise<ReceptionView> {
  const data = object(await readJson(await request('/api/receptions/' + encodeURIComponent(id), { signal })));
  const r = object(data.request);
  const state = str(r.state);
  if (!['DRAFT', 'SUBMITTED', 'EXCEPTION', 'RECEIVED', 'RETURNED'].includes(state)) throw new Error('Unknown state');
  return { caseNumber: nullable(data.caseNumber), request: { id: str(r.id), version: num(r.version), state,
    requestNumber: str(r.requestNumber), patientId: str(r.patientId), patientLabel: r.patientLabel === null ? '姓名未知' : str(r.patientLabel), encounterNumber: str(r.encounterNumber),
    containers: list(r.containers, v => { const c = object(v); return { id: str(c.id), site: str(c.site), laterality: str(c.laterality), materialQuantity: num(c.materialQuantity), fixative: str(c.fixative), fixedAt: nullable(c.fixedAt) }; }) },
    events: list(data.events, v => { const e = object(v); return { id: str(e.id), requestVersion: num(e.requestVersion), action: str(e.action), category: nullable(e.category), reason: str(e.reason), actorId: str(e.actorId), occurredAt: str(e.occurredAt) }; }) };
}
export async function receptionCommand(id: string, command: ReceptionCommand, key: string): Promise<boolean> {
  const timeout = new AbortController(); const timer = setTimeout(() => timeout.abort(), 15000);
  try {
    const csrf = await fetchCsrf(timeout.signal);
    const data = object(await readJson(await request('/api/receptions/' + encodeURIComponent(id) + '/' + command.action, {
      method: 'POST', signal: timeout.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(command.body),
    })));
    const receipt = object(data.receipt);
    if (str(receipt.resourceId) !== id || num(receipt.version) !== command.body.expectedVersion + 1) throw new Error('Unexpected receipt');
    return true;
  } finally { clearTimeout(timer); }
}
