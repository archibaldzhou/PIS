import { fetchCsrf, readJson, request } from '../../api';
export const kinds = ['ALL', 'REQUEST', 'RECEPTION', 'TECHNICAL', 'QUALITY'];
export const states = ['ALL', 'DRAFT', 'SUBMITTED', 'RECEIVED', 'EXCEPTION', 'RETURNED', 'QUEUED', 'ACTIVE', 'HANDOFF_PENDING', 'SIMULATED_DONE', 'ABORTED', 'NOT_ASSESSED', 'PASS', 'FAIL', 'PENDING', 'IDENTITY_MISMATCH', 'REVOKED', 'REWORK_REQUIRED', 'INVALIDATED', 'SOURCE_QUARANTINED'];
export interface Filter { kind: string; state: string; due: string; sort: string; page: number; pageSize: number }
export interface Item { kind: string; id: string; requestId: string; patientId: string; requestNumber: string; state: string; version: number; createdAt: string; cassetteId: string | null; blocked: boolean; active: boolean; dueAt: string | null; overdue: boolean }
export interface WorkPage { total: number; page: number; pageSize: number; asOf: string; syntheticDueMinutes: number; items: Item[] }
export interface Claim { taskId: string; expectedVersion: number; confirmedCassetteId: string }
export interface Batch { batchId: string; items: Claim[]; reason: string }
export interface ItemResult { taskId: string; outcome: string; status: number; code: string; version: number | null; replayed: boolean }
export interface BatchResult { batchId: string; items: ItemResult[] }
export interface TraceEvent { eventId: string; domain: string; entityId: string; version: number; action: string; relatedId: string | null; relatedType: string | null; occurredAt: string }
export interface Trace { requestId: string; total: number; page: number; pageSize: number; events: TraceEvent[] }
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid worklist response'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw new Error('Invalid worklist text'); return v; }
function num(v: unknown, min = 0): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < min) throw new Error('Invalid worklist number'); return v; }
function bool(v: unknown): boolean { if (typeof v !== 'boolean') throw new Error('Invalid worklist flag'); return v; }
function nullable(v: unknown) { return v === null ? null : str(v); }
function list<T>(v: unknown, parse: (v: unknown) => T) { if (!Array.isArray(v)) throw new Error('Invalid worklist list'); return v.map(parse); }
export function parsePage(v: unknown): WorkPage {
  const p = obj(v); const items = list(p.items, v => {
    const i = obj(v), kind = str(i.kind), state = str(i.state); if (!kinds.slice(1).includes(kind) || !states.slice(1).includes(state)) throw new Error('Unknown worklist kind/state');
    const item = { kind, state, id: str(i.id), requestId: str(i.requestId), patientId: str(i.patientId), requestNumber: str(i.requestNumber), version: num(i.version, kind === 'QUALITY' ? -1 : 0), createdAt: str(i.createdAt), cassetteId: nullable(i.cassetteId), blocked: bool(i.blocked), active: bool(i.active), dueAt: nullable(i.dueAt), overdue: bool(i.overdue) };
    if (item.overdue && !item.active || item.active !== (item.dueAt !== null) || kind === 'TECHNICAL' && item.cassetteId === null) throw new Error('Invalid deadline/source'); return item;
  });
  if (new Set(items.map(keyOf)).size !== items.length) throw new Error('Duplicate work item');
  return { total: num(p.total), page: num(p.page, 1), pageSize: num(p.pageSize, 1), asOf: str(p.asOf), syntheticDueMinutes: num(p.syntheticDueMinutes, 1), items };
}
export const keyOf = (i: Item) => i.kind + ':' + i.id;
export const claimable = (i: Item) => i.kind === 'TECHNICAL' && i.state === 'QUEUED' && !i.blocked && i.cassetteId !== null;
export async function loadPage(scope: string, filter: Filter, signal: AbortSignal) {
  const params = new URLSearchParams(Object.entries(filter).map(([k, v]) => [k, String(v)]));
  const p = parsePage(await readJson(await request('/api/worklists/scopes/' + scope + '?' + params, { signal })));
  if (p.page !== filter.page || p.pageSize !== filter.pageSize) throw new Error('Mismatched page'); return p;
}
export function parseBatch(value: unknown, input: Batch): BatchResult {
  const result = obj(value), items = list(result.items, v => { const i = obj(v); const outcome = str(i.outcome), status = num(i.status), version = i.version === null ? null : num(i.version), replayed = bool(i.replayed);
    if (!['SUCCESS', 'REJECTED', 'UNKNOWN'].includes(outcome) || (outcome === 'SUCCESS' ? status < 200 || status >= 300 || version === null : status < 400 || version !== null || replayed)) throw new Error('Invalid batch outcome');
    return { taskId: str(i.taskId), outcome, status, code: str(i.code), version, replayed };
  });
  if (result.batchId !== input.batchId || items.length !== input.items.length || items.some((i, n) => i.taskId !== input.items[n].taskId)) throw new Error('Mismatched batch receipt');
  return { batchId: input.batchId, items };
}
export async function sendBatch(scope: string, input: Batch): Promise<BatchResult> {
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 30000);
  try { const csrf = await fetchCsrf(abort.signal); return parseBatch(await readJson(await request('/api/worklists/scopes/' + scope + '/claims', { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(input) })), input); }
  finally { clearTimeout(timer); }
}
export async function loadTrace(id: string, page: number, signal: AbortSignal): Promise<Trace> {
  const t = obj(await readJson(await request('/api/worklists/requests/' + id + '/trace?page=' + page + '&pageSize=20', { signal })));
  if (t.requestId !== id || t.page !== page || t.pageSize !== 20) throw new Error('Mismatched trace identity/page');
  return { requestId: id, total: num(t.total), page, pageSize: 20, events: list(t.events, v => { const e = obj(v), domain = str(e.domain); if (!['REQUEST', 'RECEPTION', 'GROSSING', 'TECHNICAL', 'MATERIAL', 'LABEL', 'QUALITY'].includes(domain)) throw new Error('Unknown trace domain'); return { eventId: str(e.eventId), domain, entityId: str(e.entityId), version: num(e.version), action: str(e.action), relatedId: nullable(e.relatedId), relatedType: nullable(e.relatedType), occurredAt: str(e.occurredAt) }; }) };
}
