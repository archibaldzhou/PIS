import { fetchCsrf, readJson, request } from '../../api';
export const states = ['QUEUED', 'ACTIVE', 'HANDOFF_PENDING', 'SIMULATED_DONE', 'ABORTED'] as const;
export type TaskState = typeof states[number];
export interface Task { id: string; cassetteId: string; kind: string; state: TaskState; ownerId: string | null; predecessorId: string | null; reworkOf: string | null; version: number; createdBy: string; createdAt: string }
export interface TechnicalView { actorId: string; source: { requestId: string; requestVersion: number; caseId: string; caseNumber: string; patientId: string; patientLabel: string; encounterNumber: string; cassettes: { id: string; number: string; site: string; containerIds: string[] }[] }; tasks: Task[] }
export interface TaskEvent { id: string; version: number; action: string; actorId: string; previousOwnerId: string | null; nextOwnerId: string | null; relatedTaskId: string | null; reason: string; occurredAt: string }
export interface Command { path: string; body: Record<string, string | number | null> }
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid technical response'); return value as Record<string, unknown>; }
function str(value: unknown): string { if (typeof value !== 'string') throw new Error('Invalid technical response'); return value; }
function num(value: unknown): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('Invalid technical response'); return value; }
function optional(value: unknown): string | null { return value === null ? null : str(value); }
function list<T>(value: unknown, parse: (v: unknown) => T): T[] { if (!Array.isArray(value)) throw new Error('Invalid technical response'); return value.map(parse); }
export function parseTask(value: unknown): Task {
  const t = object(value), state = str(t.state);
  if (!states.some(s => s === state)) throw new Error('Unknown technical state');
  if (!['PROCESSING', 'EMBEDDING', 'SECTIONING'].includes(str(t.kind))) throw new Error('Unknown task kind');
  return { id: str(t.id), cassetteId: str(t.cassetteId), kind: str(t.kind), state: state as TaskState, ownerId: optional(t.ownerId), predecessorId: optional(t.predecessorId), reworkOf: optional(t.reworkOf), version: num(t.version), createdBy: str(t.createdBy), createdAt: str(t.createdAt) };
}
export async function loadTechnical(id: string, signal: AbortSignal): Promise<TechnicalView> {
  const v = object(await readJson(await request('/api/technical/requests/' + id, { signal }))), s = object(v.source);
  if (s.requestId !== id) throw new Error('Mismatched request');
  return { actorId: str(v.actorId), source: { requestId: id, requestVersion: num(s.requestVersion), caseId: str(s.caseId), caseNumber: str(s.caseNumber), patientId: str(s.patientId), patientLabel: s.patientLabel === null ? '姓名未知' : str(s.patientLabel), encounterNumber: str(s.encounterNumber),
    cassettes: list(s.cassettes, value => { const c = object(value); return { id: str(c.id), number: str(c.number), site: str(c.site), containerIds: list(c.containerIds, str) }; }) }, tasks: list(v.tasks, parseTask) };
}
export async function loadEvents(id: string, signal: AbortSignal): Promise<TaskEvent[]> {
  const result = object(await readJson(await request('/api/technical/tasks/' + id, { signal })));
  if (parseTask(result.task).id !== id) throw new Error('Mismatched task');
  return list(result.events, value => { const e = object(value); return { id: str(e.id), version: num(e.version), action: str(e.action), actorId: str(e.actorId), previousOwnerId: optional(e.previousOwnerId), nextOwnerId: optional(e.nextOwnerId), relatedTaskId: optional(e.relatedTaskId), reason: str(e.reason), occurredAt: str(e.occurredAt) }; });
}
export async function sendCommand(command: Command, key: string): Promise<string> {
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
  try {
    const csrf = await fetchCsrf(abort.signal);
    const result = object(await readJson(await request(command.path, { method: 'POST', signal: abort.signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(command.body) })));
    return str(object(result.receipt).resourceId);
  } finally { clearTimeout(timer); }
}
export const actionNames: Record<string, string> = { claim: '核对并领取', offer: '发起人员交接', accept: '核对并接收交接', withdraw: '撤回待确认交接', 'finish-simulation': '记录合成演练完成', abort: '中止任务', rework: '建立关联返工任务' };
export function availableActions(task: Task, actor: string): string[] {
  if (task.state === 'QUEUED') return ['claim', 'abort'];
  if (task.state === 'ACTIVE') return task.ownerId === actor ? ['offer', 'finish-simulation', 'abort'] : [];
  if (task.state === 'HANDOFF_PENDING') return task.ownerId === actor ? ['withdraw', 'abort'] : ['accept'];
  return ['rework'];
}
