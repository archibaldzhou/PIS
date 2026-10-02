import { fetchCsrf, readJson, request } from '../../api';
import { validBarcode } from './barcode';
export interface LabelJob {
  id: string; containerId: string | null; materialId: string | null; targetId: string; barcode: string; parentJobId: string | null; templateVersion: string; state: string;
  version: number; attempts: number; patientId: string; patientLabel: string; encounterNumber: string; requestNumber: string; caseNumber: string;
  site: string; laterality: string; reason: string; createdBy: string; createdAt: string;
}
export interface LabelTarget { targetId: string; requestVersion: number; targetVersion: number; ready: boolean; jobs: LabelJob[] }
export interface LabelView { job: LabelJob; events: { id: string; jobVersion: number; action: string; reason: string; actorId: string; occurredAt: string }[] }
export type LabelCommand = { path: string; body: { requestVersion: number; containerVersion: number } | { requestVersion: number; materialVersion: number } | { expectedVersion: number; reason: string } };
function object(x: unknown): Record<string, unknown> { if (!x || typeof x !== 'object' || Array.isArray(x)) throw new Error('Invalid response'); return x as Record<string, unknown>; }
function str(x: unknown): string { if (typeof x !== 'string') throw new Error('Invalid response'); return x; }
function num(x: unknown): number { if (typeof x !== 'number' || !Number.isSafeInteger(x) || x < 0) throw new Error('Invalid response'); return x; }
function list<T>(x: unknown, parse: (v: unknown) => T): T[] { if (!Array.isArray(x)) throw new Error('Invalid response'); return x.map(parse); }
function job(x: unknown): LabelJob {
  const r = object(x); const barcode = str(r.barcode); const state = str(r.state);
  if (!validBarcode(barcode) || !['PREVIEW_READY', 'FAILED', 'CANCELLED'].includes(state) || !['SYN-CONTAINER-1', 'SYN-MATERIAL-1'].includes(str(r.templateVersion))) throw new Error('Unknown label content');
  const materialId = r.materialId === null || r.materialId === undefined ? null : str(r.materialId);
  const containerId = r.containerId === null ? null : str(r.containerId);
  const targetId = r.targetId === undefined ? str(r.containerId) : str(r.targetId);
  if ((containerId === null) === (materialId === null) || targetId !== (materialId ?? containerId) || (materialId === null ? r.templateVersion !== 'SYN-CONTAINER-1' : r.templateVersion !== 'SYN-MATERIAL-1')) throw new Error('Mismatched label identity');
  return { id: str(r.id), containerId, materialId, targetId, barcode, parentJobId: r.parentJobId === null ? null : str(r.parentJobId), templateVersion: str(r.templateVersion), state,
    version: num(r.version), attempts: num(r.attempts), patientId: str(r.patientId), patientLabel: str(r.patientLabel), encounterNumber: str(r.encounterNumber), requestNumber: str(r.requestNumber),
    caseNumber: str(r.caseNumber), site: str(r.site), laterality: str(r.laterality), reason: str(r.reason), createdBy: str(r.createdBy), createdAt: str(r.createdAt) };
}
export async function loadTarget(id: string, signal: AbortSignal, kind: 'container' | 'material'): Promise<LabelTarget> {
  const r = object(await readJson(await request('/api/labels/' + (kind === 'material' ? 'materials/' : 'containers/') + encodeURIComponent(id), { signal })));
  const data = { targetId: str(kind === 'material' ? r.materialId : r.containerId), requestVersion: num(r.requestVersion), targetVersion: num(kind === 'material' ? r.materialVersion : r.containerVersion), ready: kind === 'material' ? r.state === 'ACTIVE' : r.requestState === 'RECEIVED', jobs: list(r.jobs, job) };
  if (data.targetId !== id || data.jobs.some(j => j.targetId !== id || (kind === 'material' ? j.materialId !== id : j.containerId !== id))) throw new Error('Mismatched label target');
  return data;
}
export async function loadJob(id: string, signal?: AbortSignal): Promise<LabelView> {
  const r = object(await readJson(await request('/api/labels/jobs/' + encodeURIComponent(id), { signal })));
  const result = { job: job(r.job), events: list(r.events, v => { const e = object(v); return { id: str(e.id), jobVersion: num(e.jobVersion), action: str(e.action), reason: str(e.reason), actorId: str(e.actorId), occurredAt: str(e.occurredAt) }; }) };
  if (result.job.id !== id) throw new Error('Mismatched label job'); return result;
}
async function post(path: string, body: unknown, key?: string): Promise<unknown> {
  const timeout = new AbortController(); const timer = setTimeout(() => timeout.abort(), 15000);
  try {
    const csrf = await fetchCsrf(timeout.signal);
    return readJson(await request(path, { method: 'POST', signal: timeout.signal,
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, ...(key ? { 'Idempotency-Key': key } : {}) }, body: JSON.stringify(body) }));
  } finally { clearTimeout(timer); }
}
export async function labelCommand(input: LabelCommand, key: string): Promise<string> {
  const result = object(await post(input.path, input.body, key)); return str(object(result.receipt).resourceId);
}
export async function checkLabel(id: string, targetId: string, barcode: string, kind: 'container' | 'material' = 'container'): Promise<void> {
  const result = object(await post('/api/labels/jobs/' + encodeURIComponent(id) + (kind === 'material' ? '/verify-material' : '/verify'), kind === 'material' ? { materialId: targetId, barcode } : { containerId: targetId, barcode }));
  if (result.matches !== true) throw new Error('Barcode identity mismatch');
}
