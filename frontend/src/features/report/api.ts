import { fetchCsrf, readJson, request } from '../../api';
export interface Template { code: string; version: number; title: string; schemaCode: string }
export interface Revision { id: string; caseId: string; version: number; templateCode: string; templateVersion: number; fields: Record<string, string | number | boolean>; assignmentVersion: number; authorId: string; reason: string; createdAt: string }
export interface Detail { context: { caseId: string; requestId: string; patientId: string; number: string; assignmentVersion: number; ready: boolean }; current: Revision | null; templates: Template[] }
export interface Command { caseId: string; body: { expectedVersion: number; assignmentVersion: number; confirmedCaseId: string; templateCode: string; templateVersion: number; fields: Record<string, string | number | boolean>; reason: string } }
export const base = '/api/requests/reports/cases/';
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid draft response'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw new Error('Invalid draft text'); return v; }
function num(v: unknown): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw new Error('Invalid draft version'); return v; }
export function parseRevision(v: unknown, id: string): Revision {
  const r = obj(v), fields = obj(r.fields); if (r.caseId !== id) throw new Error('Mismatched draft case');
  const typed: Revision['fields'] = {}; for (const [k, value] of Object.entries(fields)) { if (!['gross', 'microscopy', 'diagnosis', 'notes', 'sampleCount', 'manualChecked'].includes(k) || !['string', 'number', 'boolean'].includes(typeof value)) throw new Error('Invalid draft field'); typed[k] = value as string | number | boolean; }
  if (['gross', 'microscopy', 'diagnosis', 'notes'].some(k => typeof typed[k] !== 'string') || ('sampleCount' in typed || 'manualChecked' in typed) && (typeof typed.sampleCount !== 'number' || !Number.isInteger(typed.sampleCount) || typed.sampleCount < 0 || typed.sampleCount > 1000 || typeof typed.manualChecked !== 'boolean')) throw new Error('Invalid typed draft snapshot');
  return { id: str(r.id), caseId: id, version: num(r.version), templateCode: str(r.templateCode), templateVersion: num(r.templateVersion), fields: typed, assignmentVersion: num(r.assignmentVersion), authorId: str(r.authorId), reason: str(r.reason), createdAt: str(r.createdAt) };
}
export async function load(id: string, signal: AbortSignal): Promise<Detail> {
  const d = obj(await readJson(await request(base + id, { signal }))), c = obj(d.context);
  if (c.caseId !== id || typeof c.ready !== 'boolean' || !Array.isArray(d.templates)) throw new Error('Mismatched report context');
  return { context: { caseId: id, requestId: str(c.requestId), patientId: str(c.patientId), number: str(c.number), assignmentVersion: num(c.assignmentVersion), ready: c.ready }, current: d.current === null ? null : parseRevision(d.current, id), templates: d.templates.map(v => { const t = obj(v); if (!['SYN-TEXT-1', 'SYN-STRUCTURED-2'].includes(str(t.schemaCode))) throw new Error('Unsupported template schema'); return { code: str(t.code), version: num(t.version), title: str(t.title), schemaCode: str(t.schemaCode) }; }) };
}
export async function history(id: string, page: number, signal: AbortSignal) {
  const h = obj(await readJson(await request(base + id + '/history?page=' + page, { signal })));
  if (h.caseId !== id || h.page !== page || !Array.isArray(h.revisions)) throw new Error('Mismatched draft history'); return h.revisions.map(v => parseRevision(v, id));
}
export async function send(c: Command, key: string) {
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
  try { const csrf = await fetchCsrf(abort.signal); const response = obj(await readJson(await request(base + c.caseId + '/draft', { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(c.body) }))); const r = obj(response.receipt); if (r.resourceType !== 'REPORT_DRAFT' || r.resourceId !== c.caseId || r.version !== c.body.expectedVersion + 1 || r.status !== 200) throw new Error('Mismatched draft receipt'); return true; }
  finally { clearTimeout(timer); }
}
