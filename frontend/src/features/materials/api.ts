import { fetchCsrf, readJson, request } from '../../api';
export interface Material { id: string; requestId: string; patientId: string; caseId: string; kind: string; route: string; operation: string; number: string; barcode: string; recordId: string | null; cassetteId: string | null; containerId: string | null; blockId: string | null; sourceSlideId: string | null; technicalTaskId: string | null; state: string; version: number; cytologyPreparationId?: string | null; stainOrderId?: string | null }
export interface SourceTask { id: string; version: number; cassetteId: string; kind: string }
export interface MaterialsView { request: { id: string; version: number; patientId: string; patientLabel: string; encounterNumber: string; containers: { id: string; site: string }[] }; caseNumber: string; entities: Material[]; tasks: SourceTask[] }
export interface MaterialEvent { id: string; version: number; action: string; relatedId: string | null; reason: string; actorId: string; occurredAt: string }
export interface Command { path: string; body: Record<string, string | number> }
function obj(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid material response'); return value as Record<string, unknown>; }
function str(value: unknown): string { if (typeof value !== 'string') throw new Error('Invalid material response'); return value; }
function num(value: unknown): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('Invalid material version'); return value; }
function nullable(value: unknown) { return value === null ? null : str(value); }
function list<T>(value: unknown, parse: (v: unknown) => T): T[] { if (!Array.isArray(value)) throw new Error('Invalid material list'); return value.map(parse); }
export function parseMaterial(value: unknown): Material {
  const m = obj(value); const kind = str(m.kind), route = str(m.route), operation = str(m.operation), state = str(m.state);
  if (!['BLOCK', 'SLIDE'].includes(kind) || !['CASSETTE', 'BLOCK_BASED', 'DIRECT_CYTOLOGY', 'CYTOLOGY_BLOCK', 'CYTOLOGY_SLIDE', 'STAINED_SLIDE'].includes(route) || !['ORIGINAL', 'RECUT', 'DEEPER'].includes(operation) || !['ACTIVE', 'VOID'].includes(state)) throw new Error('Unknown material state');
  const result = { id: str(m.id), requestId: str(m.requestId), patientId: str(m.patientId), caseId: str(m.caseId), kind, route, operation, number: str(m.number), barcode: str(m.barcode), recordId: nullable(m.recordId), cassetteId: nullable(m.cassetteId), containerId: nullable(m.containerId), blockId: nullable(m.blockId), sourceSlideId: nullable(m.sourceSlideId), technicalTaskId: nullable(m.technicalTaskId), state, version: num(m.version), cytologyPreparationId: m.cytologyPreparationId === undefined ? null : nullable(m.cytologyPreparationId), stainOrderId: m.stainOrderId === undefined ? null : nullable(m.stainOrderId) };
  if ((kind === 'BLOCK' && route !== 'CYTOLOGY_BLOCK' && (route !== 'CASSETTE' || !result.cassetteId || result.blockId !== null)) || (route === 'BLOCK_BASED' && (kind !== 'SLIDE' || !result.blockId || !result.cassetteId)) || (route === 'DIRECT_CYTOLOGY' && (kind !== 'SLIDE' || !result.containerId || result.blockId !== null || result.cassetteId !== null || operation !== 'ORIGINAL'))) throw new Error('Invalid material lineage');
  if (route.startsWith('CYTOLOGY_') && (!result.cytologyPreparationId || !result.containerId || result.cassetteId !== null || result.recordId !== null || result.technicalTaskId !== null || result.sourceSlideId !== null || operation !== 'ORIGINAL' || route === 'CYTOLOGY_BLOCK' && (kind !== 'BLOCK' || result.blockId !== null) || route === 'CYTOLOGY_SLIDE' && kind !== 'SLIDE')) throw new Error('Invalid cytology lineage');
  if (route === 'STAINED_SLIDE' && (kind !== 'SLIDE' || operation !== 'ORIGINAL' || !result.stainOrderId || !result.sourceSlideId || result.cytologyPreparationId !== null)) throw new Error('Invalid staining lineage');
  return result;
}
export async function loadMaterials(id: string, signal: AbortSignal): Promise<MaterialsView> {
  const v = obj(await readJson(await request('/api/materials/requests/' + id, { signal }))), q = obj(v.request);
  if (q.id !== id) throw new Error('Mismatched request');
  const entities = list(v.entities, parseMaterial); if (entities.some(e => e.requestId !== id || e.patientId !== q.patientId)) throw new Error('Mismatched material scope');
  return { request: { id, version: num(q.version), patientId: str(q.patientId), patientLabel: q.patientLabel === null ? '姓名未知' : str(q.patientLabel), encounterNumber: str(q.encounterNumber), containers: list(q.containers, x => { const c = obj(x); return { id: str(c.id), site: str(c.site) }; }) }, caseNumber: str(v.caseNumber), entities,
    tasks: list(v.tasks, x => { const t = obj(x); return { id: str(t.id), version: num(t.version), cassetteId: str(t.cassetteId), kind: str(t.kind) }; }) };
}
function detail(value: unknown) { const d = obj(value); return { entity: parseMaterial(d.entity), events: list(d.events, x => { const e = obj(x); return { id: str(e.id), version: num(e.version), action: str(e.action), relatedId: nullable(e.relatedId), reason: str(e.reason), actorId: str(e.actorId), occurredAt: str(e.occurredAt) }; }) }; }
export async function loadMaterial(id: string, signal: AbortSignal) { const d = detail(await readJson(await request('/api/materials/' + id, { signal }))); if (d.entity.id !== id) throw new Error('Mismatched entity'); return d; }
export async function lookupMaterial(barcode: string, signal: AbortSignal) { return detail(await readJson(await request('/api/materials/lookup?barcode=' + encodeURIComponent(barcode), { signal }))); }
export async function sendCommand(command: Command, key: string): Promise<string> {
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
  try { const csrf = await fetchCsrf(abort.signal); const result = obj(await readJson(await request(command.path, { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(command.body) }))); return str(obj(result.receipt).resourceId); }
  finally { clearTimeout(timer); }
}
export const names: Record<string, string> = { block: '登记合成蜡块', direct: '登记细胞学直制玻片', slide: '登记原始玻片', recut: '登记重切新玻片', deeper: '登记加深新玻片', void: '作废材料并保留历史', labels: '标签预览与同实体重打' };
export function modes(entity?: Material) {
  if (!entity) return ['block', 'direct'];
  return [...(entity.kind === 'BLOCK' && entity.route === 'CASSETTE' && entity.state === 'ACTIVE' ? ['slide'] : []), ...(entity.route === 'BLOCK_BASED' ? ['recut', 'deeper'] : []), ...(entity.state === 'ACTIVE' ? ['void'] : []), 'labels'];
}
