import { fetchCsrf, readJson, request } from '../../api';
export const metrics = ['RECEPTION', 'TECHNICAL', 'REPORT', 'QC'] as const;
export type Metric = typeof metrics[number];
export type Sort = 'ENTITY' | 'START_ASC' | 'START_DESC';
export interface Query { from: string; to: string; zone: string }
export interface Summary { metric: Metric; availability: string; cohort: number; completed: number; open: number; unknown: number; excluded: number; numerator: number; denominator: number; ratio: number | null; medianSeconds: number | null }
export interface Fact { metric: Metric; entityId: string; requestId: string; startEvent: string | null; endEvent: string | null; startAt: string | null; endAt: string | null; status: string; durationSeconds: number | null; sourceBasis: string }
export interface View extends Query { id: string; scopeId: string; definition: string; cutoff: string; factsHash: string; canDrill: boolean; summaries: Summary[]; metric: Metric | null; sort: Sort; page: number; total: number; facts: Fact[] }
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw Error('统计响应格式错误'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw Error('统计文本无效'); return v; }
function num(v: unknown): number { if (typeof v !== 'number' || !Number.isFinite(v) || v < 0) throw Error('统计数值无效'); return v; }
function count(v: unknown): number { const n = num(v); if (!Number.isSafeInteger(n)) throw Error('统计计数无效'); return n; }
function nullable(v: unknown): string | null { return v === null ? null : str(v); }
function metric(v: unknown): Metric { if (!metrics.includes(v as Metric)) throw Error('未知统计指标'); return v as Metric; }
export function parseView(value: unknown, scope: string, id: string): View {
  const v = obj(value); if (v.scopeId !== scope || v.id !== id || v.definition !== 'SYN-STATS-1' || typeof v.canDrill !== 'boolean' || !Array.isArray(v.summaries) || !Array.isArray(v.facts)) throw Error('统计快照身份不匹配');
  const summaries = v.summaries.map(x => { const s = obj(x); const out = { metric: metric(s.metric), availability: str(s.availability), cohort: count(s.cohort), completed: count(s.completed), open: count(s.open), unknown: count(s.unknown), excluded: count(s.excluded), numerator: count(s.numerator), denominator: count(s.denominator), ratio: s.ratio === null ? null : num(s.ratio), medianSeconds: s.medianSeconds === null ? null : num(s.medianSeconds) };
    if (!['DATA', 'NO_DATA', 'NOT_AUTHORIZED'].includes(out.availability) || out.numerator > out.denominator || (out.denominator === 0 ? out.ratio !== null : out.ratio === null || Math.abs(out.ratio - out.numerator / out.denominator) > 1e-9)) throw Error('统计分母或比率无效'); return out; });
  if (summaries.length !== 4 || new Set(summaries.map(s => s.metric)).size !== 4) throw Error('统计指标不完整');
  const facts = v.facts.map(x => { const f = obj(x); const status = str(f.status); if (!['COMPLETED', 'OPEN', 'UNKNOWN', 'EXCLUDED', 'PASS', 'FAIL'].includes(status)) throw Error('未知统计状态'); const seconds = f.durationSeconds === null ? null : count(f.durationSeconds); if (['OPEN', 'COMPLETED'].includes(status) !== (seconds !== null)) throw Error('缺失时长不得补零'); return { metric: metric(f.metric), entityId: str(f.entityId), requestId: str(f.requestId), startEvent: nullable(f.startEvent), endEvent: nullable(f.endEvent), startAt: nullable(f.startAt), endAt: nullable(f.endAt), status, durationSeconds: seconds, sourceBasis: str(f.sourceBasis) }; });
  const page = count(v.page), total = count(v.total); const m = v.metric === null ? null : metric(v.metric); const sort = str(v.sort);
  if (page < 1 || page > 250 || total > 5000 || facts.length > 20 || facts.some(f => f.metric !== m) || !['ENTITY', 'START_ASC', 'START_DESC'].includes(sort) || !/^[a-f0-9]{64}$/.test(str(v.factsHash))) throw Error('统计分页或摘要无效');
  return { id, scopeId: scope, definition: 'SYN-STATS-1', from: str(v.from), to: str(v.to), zone: str(v.zone), cutoff: str(v.cutoff), factsHash: str(v.factsHash), canDrill: v.canDrill, summaries, facts, metric: m, sort: sort as Sort, page, total };
}
export async function createSnapshot(scope: string, query: Query, key: string, signal: AbortSignal) {
  const csrf = await fetchCsrf(signal); const v = obj(await readJson(await request(`/api/requests/statistics/scopes/${scope}`, { method: 'POST', signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(query) })));
  const receipt = obj(v.receipt); if (receipt.resourceType !== 'STATISTICS_SNAPSHOT' || receipt.status !== 200 || receipt.version !== 0) throw Error('统计回执无效'); return str(receipt.resourceId);
}
export async function loadSnapshot(scope: string, id: string, metric: Metric | null, sort: Sort, page: number, signal: AbortSignal) {
  const params = new URLSearchParams({ sort, page: String(page), ...(metric ? { metric } : {}) });
  const view = parseView(await readJson(await request(`/api/requests/statistics/scopes/${scope}/${id}?${params}`, { signal })), scope, id);
  if (view.metric !== metric || view.sort !== sort || view.page !== page) throw Error('统计查询响应不匹配'); return view;
}
