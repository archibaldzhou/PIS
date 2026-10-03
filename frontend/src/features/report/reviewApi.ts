import { request, readJson, fetchCsrf } from '../../api';
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid review object'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw new Error('Invalid review text'); return v; }
function num(v: unknown): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < -1) throw new Error('Invalid review version'); return v; }
import { parseRevision, type Revision } from './api';
export type Action = 'APPROVE' | 'RETURN' | 'SIMULATE_SIGN';
export interface Detail { caseId: string; patientId: string; number: string; assignmentVersion: number; version: number; state: string; ready: boolean; canReview: boolean; canSimulateSign: boolean; dependencyToken: string; policy: { code: string; separateAuthorReview: boolean; separateReviewSign: boolean }; draft: Revision | null; events: { id: string; version: number; revisionId: string; draftVersion: number; action: Action; actorId: string; reviewId: string | null; reason: string; occurredAt: string }[] }
export interface Command { caseId: string; action: Action; body: { expectedVersion: number; confirmedCaseId: string; revisionId: string; draftVersion: number; assignmentVersion: number; templateCode: string; templateVersion: number; dependencyToken: string; reason: string; simulationAcknowledged: boolean } }
const base = '/api/requests/reports/cases/';
const boolean = (value: unknown) => { if (typeof value !== 'boolean') throw new Error('Invalid review boolean'); return value; };
const action = (value: unknown): Action => { if (value !== 'APPROVE' && value !== 'RETURN' && value !== 'SIMULATE_SIGN') throw new Error('Invalid review action'); return value; };
const parseEvent = (value: unknown) => { const e = obj(value); return { id: str(e.id), version: num(e.version), revisionId: str(e.revisionId), draftVersion: num(e.draftVersion), action: action(e.action), actorId: str(e.actorId), reviewId: e.reviewId === null ? null : str(e.reviewId), reason: str(e.reason), occurredAt: str(e.occurredAt) }; };
export function parseDetail(value: unknown, id: string): Detail {
 const d = obj(value), p = obj(d.policy);
 if (d.caseId !== id || !['DRAFT', 'APPROVED', 'STALE', 'RETURNED', 'SIMULATED_SIGNED'].includes(str(d.state)) || !/^[a-f0-9]{64}$/.test(str(d.dependencyToken)) || p.code !== 'SYN-REVIEW-1' || !Array.isArray(d.events)) throw new Error('Mismatched review snapshot');
 return { caseId: id, patientId: str(d.patientId), number: str(d.number), assignmentVersion: num(d.assignmentVersion), version: num(d.version), state: str(d.state), ready: boolean(d.ready), canReview: boolean(d.canReview), canSimulateSign: boolean(d.canSimulateSign), dependencyToken: str(d.dependencyToken), policy: { code: str(p.code), separateAuthorReview: boolean(p.separateAuthorReview), separateReviewSign: boolean(p.separateReviewSign) }, draft: d.draft === null ? null : parseRevision(d.draft, id), events: d.events.map(parseEvent) };
}
export async function loadReview(id: string, signal: AbortSignal) { return parseDetail(await readJson(await request(base + id + '/review', { signal })), id); }
export async function sendReview(c: Command, key: string) {
 const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
 try { const csrf = await fetchCsrf(abort.signal); const data = obj(await readJson(await request(base + c.caseId + '/review/' + c.action, { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(c.body) }))); const r = obj(data.receipt); if (r.resourceType !== 'REPORT_REVIEW' || r.resourceId !== c.caseId || r.version !== c.body.expectedVersion + 1 || r.status !== 200) throw new Error('Mismatched review receipt'); return true; }
 finally { clearTimeout(timer); }
}

export async function loadReviewHistory(id: string, page: number, signal: AbortSignal) {
 const d = obj(await readJson(await request(base + id + '/review/history?page=' + page, { signal })));
 if (d.caseId !== id || d.page !== page || !Array.isArray(d.events)) throw new Error('Mismatched review history'); return d.events.map(parseEvent);
}
