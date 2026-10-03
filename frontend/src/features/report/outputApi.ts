import { fetchCsrf, readJson, request } from '../../api';
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid output object'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw new Error('Invalid output text'); return v; }
function num(v: unknown, min = 0): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < min) throw new Error('Invalid output number'); return v; }
const hash = (v: unknown) => { if (!/^[a-f0-9]{64}$/.test(str(v))) throw new Error('Invalid output hash'); return str(v); };
export const labels = { PREVIEW: '预览固定PDF', DOWNLOAD: '下载固定PDF', PRINT_REQUEST: '登记打印请求（不连接打印机）', REPRINT_REQUEST: '登记同版重印请求', USER_REPORTED_PRINTED: '用户自报已打印（未获硬件确认）', USER_REPORTED_FAILED: '用户自报失败', USER_REPORTED_CANCELLED: '用户自报取消' };
export type Kind = keyof typeof labels;
export interface Artifact { id: string; caseId: string; version: number; signatureId: string; signatureVersion: number; revisionId: string; draftVersion: number; templateCode: string; templateVersion: number; schemaCode: string; dependencyToken: string; rendererVersion: string; fontHash: string; sha256: string; byteSize: number; pages: number; createdAt: string }
export interface Detail { caseId: string; signatureId: string; signatureVersion: number; revisionId: string; dependenciesCurrent: boolean; artifact: Artifact | null; activityVersion: number }
export interface Event { id: string; version: number; kind: Kind; requestId: string | null; actorId: string; reason: string; occurredAt: string }
export type Command = { caseId: string; kind: 'CREATE'; body: { confirmedCaseId: string; signatureId: string; signatureVersion: number; revisionId: string; reason: string } } | { caseId: string; kind: Kind; artifact: Artifact; body: { confirmedCaseId: string; artifactVersion: number; sha256: string; expectedVersion: number; requestId: string | null; reason: string } };
export type Result = { kind: 'receipt' } | { kind: 'pdf'; blob: Blob; artifact: Artifact; download: boolean };
const base = '/api/requests/reports/cases/';
export function parseDetail(value: unknown, id: string): Detail {
 const d = obj(value); if (d.caseId !== id || typeof d.dependenciesCurrent !== 'boolean') throw new Error('Mismatched output case'); let artifact: Artifact | null = null;
 if (d.artifact !== null) { const a = obj(d.artifact); if (a.caseId !== id || a.signatureId !== d.signatureId || a.revisionId !== d.revisionId || a.signatureVersion !== d.signatureVersion || a.version !== 0 || num(a.byteSize) < 100 || num(a.byteSize) > 4194304 || num(a.pages) < 1 || num(a.pages) > 16 || !['SYN-TEXT-1', 'SYN-STRUCTURED-2'].includes(str(a.schemaCode)) || a.rendererVersion !== 'SYN-RASTER-PDF-1') throw new Error('Invalid fixed artifact binding');
  artifact = { id: str(a.id), caseId: id, version: 0, signatureId: str(a.signatureId), signatureVersion: num(a.signatureVersion), revisionId: str(a.revisionId), draftVersion: num(a.draftVersion), templateCode: str(a.templateCode), templateVersion: num(a.templateVersion, 1), schemaCode: str(a.schemaCode), dependencyToken: hash(a.dependencyToken), rendererVersion: str(a.rendererVersion), fontHash: hash(a.fontHash), sha256: hash(a.sha256), byteSize: num(a.byteSize), pages: num(a.pages), createdAt: str(a.createdAt) }; }
 return { caseId: id, signatureId: str(d.signatureId), signatureVersion: num(d.signatureVersion), revisionId: str(d.revisionId), dependenciesCurrent: d.dependenciesCurrent, artifact, activityVersion: num(d.activityVersion, -1) };
}
export async function loadOutput(id: string, signal: AbortSignal) { return parseDetail(await readJson(await request(base + id + '/output', { signal })), id); }
export async function loadHistory(id: string, artifact: string, page: number, signal: AbortSignal): Promise<Event[]> {
 const d = obj(await readJson(await request(base + id + '/output/' + artifact + '/history?page=' + page, { signal })));
 if (d.caseId !== id || d.artifactId !== artifact || d.page !== page || !Array.isArray(d.events)) throw new Error('Mismatched output history');
 return d.events.map(value => { const e = obj(value); if (typeof e.kind !== 'string' || !Object.hasOwn(labels, e.kind)) throw new Error('Unknown print result'); return { id: str(e.id), version: num(e.version), kind: e.kind as Kind, requestId: e.requestId === null ? null : str(e.requestId), actorId: str(e.actorId), reason: str(e.reason), occurredAt: str(e.occurredAt) }; });
}
export async function verifiedPdf(response: Response, a: Artifact): Promise<Blob> {
 if (response.headers.get('Content-Type')?.split(';')[0] !== 'application/pdf' || response.headers.get('X-Artifact-Id') !== a.id || response.headers.get('X-Artifact-SHA256') !== a.sha256 || response.headers.get('X-Artifact-Version') !== '0' || Number(response.headers.get('Content-Length')) !== a.byteSize || !response.body) throw new Error('Mismatched PDF response');
 const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0;
 try { for (;;) { const next = await reader.read(); if (next.done) break; size += next.value.length; if (size > a.byteSize || size > 4194304) throw new Error('PDF exceeds bound'); chunks.push(next.value); } } catch (e) { await reader.cancel(); throw e; } finally { reader.releaseLock(); }
 const bytes = new Uint8Array(size); let offset = 0; for (const part of chunks) { bytes.set(part, offset); offset += part.length; }
 const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('');
 if (size !== a.byteSize || digest !== a.sha256 || new TextDecoder().decode(bytes.subarray(0, 8)) !== '%PDF-1.4') throw new Error('PDF integrity mismatch'); return new Blob([bytes], { type: 'application/pdf' });
}
export async function sendOutput(c: Command, key: string): Promise<Result> {
 const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 20000);
 try { const csrf = await fetchCsrf(abort.signal); const binary = c.kind === 'PREVIEW' || c.kind === 'DOWNLOAD'; const path = base + c.caseId + '/output' + (c.kind === 'CREATE' ? '' : '/' + c.artifact.id + (binary ? '/bytes/' : '/events/') + c.kind); const response = await request(path, { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(c.body) });
  if (c.kind === 'PREVIEW' || c.kind === 'DOWNLOAD') return { kind: 'pdf', blob: await verifiedPdf(response, c.artifact), artifact: c.artifact, download: c.kind === 'DOWNLOAD' };
  const r = obj(obj(await readJson(response)).receipt); if (r.status !== 200 || !str(r.resourceId) || (c.kind === 'CREATE' ? r.resourceType !== 'REPORT_ARTIFACT' || r.version !== 0 : r.resourceType !== 'REPORT_OUTPUT_EVENT' || r.version !== c.body.expectedVersion + 1)) throw new Error('Mismatched output receipt'); return { kind: 'receipt' };
 } finally { clearTimeout(timer); }
}
