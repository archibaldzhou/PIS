import { fetchCsrf, readJson, request } from '../../api';
export const actions = { CLAIM: '领取发送尝试', RECEIVE: '本地模拟接收', ACK: '验证本地业务ACK', RECONCILE: '核对接收端当前版本', FAIL: '记录模拟传输失败', TIMEOUT: '回收过期尝试', POISON: '隔离无法处理的模拟消息' };
export type Action = keyof typeof actions;
export interface Item { id: string; artifactId: string; signatureId: string; revisionId: string; sha256: string; destination: string; predecessor: string | null; version: number; state: string; attempts: number; attemptId: string | null; leaseUntil: string | null; nextAt: string }
export interface Detail { caseId: string; caStatus: 'NOT_CONFIGURED' | 'UNVERIFIED'; items: Item[] }
export interface Command { caseId: string; delivery?: string; action?: Action; body: { confirmedCaseId: string; artifactId: string; signatureId: string; revisionId: string; sha256: string; destination: string; expectedVersion: number; attemptId: string | null; reason: string } }
const base = '/api/requests/reports/cases/';
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid delivery response'); return v as Record<string, unknown>; }
function text(v: unknown): string { if (typeof v !== 'string' || !v) throw new Error('Invalid delivery text'); return v; }
function num(v: unknown): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw new Error('Invalid delivery version'); return v; }
const nullable = (v: unknown) => v === null ? null : text(v);
export function parseDetail(v: unknown, id: string): Detail {
 const d = obj(v); if (d.caseId !== id || !['NOT_CONFIGURED', 'UNVERIFIED'].includes(text(d.caStatus)) || !Array.isArray(d.items) || d.items.length > 20) throw new Error('Mismatched delivery scope or CA');
 return { caseId: id, caStatus: d.caStatus as Detail['caStatus'], items: d.items.map(v => { const i = obj(v); if (!['QUEUED', 'ATTEMPTING', 'RETRY_WAIT', 'ACKED', 'RECONCILED', 'REJECTED', 'DEAD'].includes(text(i.state)) || i.destination !== 'LOCAL_SIM' || !/^[a-f0-9]{64}$/.test(text(i.sha256)) || num(i.attempts) > 3 || (i.state === 'ATTEMPTING') !== (i.leaseUntil !== null) || (i.attempts === 0) !== (i.attemptId === null)) throw new Error('Invalid delivery state'); return { id: text(i.id), artifactId: text(i.artifactId), signatureId: text(i.signatureId), revisionId: text(i.revisionId), sha256: text(i.sha256), destination: text(i.destination), predecessor: nullable(i.predecessor), version: num(i.version), state: text(i.state), attempts: num(i.attempts), attemptId: nullable(i.attemptId), leaseUntil: nullable(i.leaseUntil), nextAt: text(i.nextAt) }; }) };
}
export async function load(id: string, page: number, signal: AbortSignal) { return parseDetail(await readJson(await request(base + id + '/deliveries?page=' + page, { signal })), id); }
export async function send(c: Command, key: string) {
 const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
 try { const csrf = await fetchCsrf(abort.signal), d = obj(await readJson(await request(base + c.caseId + '/deliveries' + (c.delivery ? '/' + c.delivery + '/' + c.action : ''), { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(c.body) }))), r = obj(d.receipt); if (r.status !== 200 || r.resourceType !== 'REPORT_DELIVERY' || c.delivery && r.resourceId !== c.delivery || num(r.version) !== (c.delivery ? c.body.expectedVersion + 1 : 0)) throw new Error('Mismatched delivery receipt'); text(r.resourceId); return true; } finally { clearTimeout(timer); }
}

export interface Event { version: number; action: string; state: string; attemptId: string | null; actorId: string; reason: string; occurredAt: string }
export async function history(id: string, delivery: string, page: number, signal: AbortSignal): Promise<Event[]> {
 const rows: unknown = await readJson(await request(base + id + '/deliveries/' + delivery + '/history?page=' + page, { signal })); if (!Array.isArray(rows) || rows.length > 20) throw new Error('Invalid delivery history');
 return rows.map(v => { const e = obj(v); if (e.action !== 'QUEUE' && !Object.hasOwn(actions, text(e.action))) throw new Error('Unknown delivery event'); return { version: num(e.version), action: text(e.action), state: text(e.state), attemptId: nullable(e.attemptId), actorId: text(e.actorId), reason: text(e.reason), occurredAt: text(e.occurredAt) }; });
}
