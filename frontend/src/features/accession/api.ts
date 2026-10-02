import { fetchCsrf, readJson, request } from '../../api';
import type { RequestFilter, RequestPage, ReadResult } from '../../shared/workflow';

export interface Scope { id: string; name: string }
export interface Encounter { id: string; patientId: string; patientLabel: string; encounterNumber: string }
export interface ContainerInput { site: string; laterality: string; materialQuantity: number; fixative: string; fixedAt: string | null }
export interface Draft { clinicalHistory: string; sampledAt: string | null; containers: ContainerInput[] }
export interface RequestDetail extends Draft {
  id: string; scopeId: string; version: number; state: string; requestNumber: string;
  patientId: string; patientLabel: string; encounterId: string; encounterNumber: string;
  containers: (ContainerInput & { id: string })[];
}
export interface Receipt { resourceId: string; version: number }
export interface AccessionApi {
  scopes(signal: AbortSignal): Promise<Scope[]>;
  encounters(scopeId: string, number: string, signal: AbortSignal): Promise<Encounter[]>;
  search(scopeId: string, filter: RequestFilter, signal: AbortSignal): Promise<ReadResult<RequestPage>>;
  detail(id: string, signal: AbortSignal): Promise<RequestDetail>;
  create(scopeId: string, encounterId: string, draft: Draft, key: string): Promise<Receipt>;
  edit(id: string, version: number, draft: Draft, key: string): Promise<Receipt>;
  submit(id: string, version: number, key: string): Promise<Receipt>;
}
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('响应格式不正确');
  return value as Record<string, unknown>;
}
function string(value: unknown): string { if (typeof value !== 'string') throw new Error('响应字段不正确'); return value; }
function number(value: unknown): number { if (!Number.isSafeInteger(value) || typeof value !== 'number' || value < 0) throw new Error('响应版本/数量不正确'); return value; }
function array<T>(value: unknown, parse: (item: unknown) => T): T[] { if (!Array.isArray(value)) throw new Error('响应列表不正确'); return value.map(parse); }
function time(value: unknown): string | null { return value === null ? null : string(value); }
function detail(value: unknown): RequestDetail {
  const r = object(value);
  return { id: string(r.id), scopeId: string(r.scopeId), version: number(r.version), state: string(r.state),
    requestNumber: string(r.requestNumber), patientId: string(r.patientId), patientLabel: r.patientLabel === null ? '姓名未知' : string(r.patientLabel),
    encounterId: string(r.encounterId), encounterNumber: string(r.encounterNumber), clinicalHistory: string(r.clinicalHistory), sampledAt: time(r.sampledAt),
    containers: array(r.containers, item => { const c = object(item); return { id: string(c.id), site: string(c.site), laterality: string(c.laterality),
      materialQuantity: number(c.materialQuantity), fixative: string(c.fixative), fixedAt: time(c.fixedAt) }; }) };
}
async function get(path: string, signal: AbortSignal) { return readJson(await request(path, { signal })); }
async function write(path: string, method: string, body: unknown, key: string): Promise<Receipt> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), 15000);
  try {
    const csrf = await fetchCsrf(timeout.signal);
    const data = object(await readJson(await request(path, { method, signal: timeout.signal, headers: { 'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(body) })));
    const receipt = object(data.receipt);
    return { resourceId: string(receipt.resourceId), version: number(receipt.version) };
  } finally { clearTimeout(timer); }
}
export const accessionApi: AccessionApi = {
  async scopes(signal) { return array(await get('/api/requests/scopes', signal), item => { const r = object(item); return { id: string(r.id), name: string(r.name) }; }); },
  async encounters(scopeId, number, signal) {
    return array(await get('/api/requests/encounters?' + new URLSearchParams({ scopeId, number }), signal), item => {
      const r = object(item); return { id: string(r.id), patientId: string(r.patientId), patientLabel: r.patientLabel === null ? '姓名未知' : string(r.patientLabel), encounterNumber: string(r.encounterNumber) };
    });
  },
  async search(scopeId, filter, signal) {
    const states: Record<string, string> = { '草稿': 'DRAFT', '待接收': 'SUBMITTED' };
    const params = new URLSearchParams({ scopeId, keyword: filter.keyword, state: states[filter.status] ?? filter.status, page: String(filter.page) });
    if (filter.date) params.set('date', filter.date);
    const data = object(await get('/api/requests?' + params, signal));
    return { status: 'ready', data: { total: number(data.total), page: number(data.page), pageSize: number(data.pageSize),
      items: array(data.items, item => { const r = object(item); const d = detail(item); return { id: d.id, version: d.version,
        requestNumber: d.requestNumber, patientLabel: d.patientLabel, patientIdentifier: d.patientId, encounterNumber: d.encounterNumber,
        department: string(r.department), site: '查看详情', requestedAt: string(r.requestedAt), statusLabel: d.state === 'DRAFT' ? '草稿' : d.state === 'SUBMITTED' ? '待接收' : d.state }; }) } };
  },
  async detail(id, signal) { return detail(await get('/api/requests/' + encodeURIComponent(id), signal)); },
  create(scopeId, encounterId, draft, key) { return write('/api/requests', 'POST', { scopeId, encounterId, draft }, key); },
  edit(id, expectedVersion, draft, key) { return write('/api/requests/' + encodeURIComponent(id), 'PUT', { expectedVersion, draft }, key); },
  submit(id, expectedVersion, key) { return write('/api/requests/' + encodeURIComponent(id) + '/submit', 'POST', { expectedVersion }, key); },
};
