import { fetchCsrf, readJson, request } from '../../api';
import { validBarcode } from './barcode';
export interface LabelJob {
  id: string; containerId: string; barcode: string; parentJobId: string | null; templateVersion: string; state: string;
  version: number; attempts: number; patientId: string; patientLabel: string; encounterNumber: string; requestNumber: string; caseNumber: string;
  site: string; laterality: string; reason: string; createdBy: string; createdAt: string;
}
export interface LabelContainer { containerId: string; requestVersion: number; containerVersion: number; requestState: string; jobs: LabelJob[] }
export interface LabelView { job: LabelJob; events: { id: string; jobVersion: number; action: string; reason: string; actorId: string; occurredAt: string }[] }
export type LabelCommand = { path: string; body: { requestVersion: number; containerVersion: number } | { expectedVersion: number; reason: string } };
function object(x: unknown): Record<string, unknown> { if (!x || typeof x !== 'object' || Array.isArray(x)) throw new Error('Invalid response'); return x as Record<string, unknown>; }
function str(x: unknown): string { if (typeof x !== 'string') throw new Error('Invalid response'); return x; }
function num(x: unknown): number { if (typeof x !== 'number' || !Number.isSafeInteger(x) || x < 0) throw new Error('Invalid response'); return x; }
function list<T>(x: unknown, parse: (v: unknown) => T): T[] { if (!Array.isArray(x)) throw new Error('Invalid response'); return x.map(parse); }
function job(x: unknown): LabelJob {
  const r = object(x); const barcode = str(r.barcode); const state = str(r.state);
  if (!validBarcode(barcode) || !['PREVIEW_READY', 'FAILED', 'CANCELLED'].includes(state) || r.templateVersion !== 'SYN-CONTAINER-1') throw new Error('Unknown label content');
  return { id: str(r.id), containerId: str(r.containerId), barcode, parentJobId: r.parentJobId === null ? null : str(r.parentJobId), templateVersion: str(r.templateVersion), state,
    version: num(r.version), attempts: num(r.attempts), patientId: str(r.patientId), patientLabel: str(r.patientLabel), encounterNumber: str(r.encounterNumber), requestNumber: str(r.requestNumber),
    caseNumber: str(r.caseNumber), site: str(r.site), laterality: str(r.laterality), reason: str(r.reason), createdBy: str(r.createdBy), createdAt: str(r.createdAt) };
}
export async function loadContainer(id: string, signal: AbortSignal): Promise<LabelContainer> {
  const r = object(await readJson(await request('/api/labels/containers/' + encodeURIComponent(id), { signal })));
  const data = { containerId: str(r.containerId), requestVersion: num(r.requestVersion), containerVersion: num(r.containerVersion), requestState: str(r.requestState), jobs: list(r.jobs, job) };
  if (data.containerId !== id || data.jobs.some(j => j.containerId !== id)) throw new Error('Mismatched label container');
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
export async function checkLabel(id: string, containerId: string, barcode: string): Promise<void> {
  const result = object(await post('/api/labels/jobs/' + encodeURIComponent(id) + '/verify', { containerId, barcode }));
  if (result.matches !== true) throw new Error('Barcode identity mismatch');
}
