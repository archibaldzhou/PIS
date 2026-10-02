import { ApiError } from '../api';
/** Minimal list projection; all identity and permission decisions remain server-side. */
export interface RequestSummary {
  id: string;
  version: number;
  requestNumber: string;
  patientLabel: string;
  patientIdentifier: string;
  encounterNumber: string;
  department: string;
  site: string;
  requestedAt: string;
  statusLabel: string;
}
export interface RequestFilter { keyword: string; date: string; status: string; source: string; page: number }
export interface RequestPage { items: RequestSummary[]; total: number; page: number; pageSize: number }
export type ReadResult<T> = { status: 'ready'; data: T }
  | { status: 'unavailable' | 'forbidden' | 'stale' | 'error'; message: string };
export type ReadState<T> = ReadResult<T> | { status: 'loading' };
export interface RequestReader {
  search(filter: RequestFilter, signal: AbortSignal): Promise<ReadResult<RequestPage>>;
}
/** New request invalidates old data immediately, even if a transport ignores abort. */
export class ReadController<T, Q> {
  private state: ReadState<T> = { status: 'loading' };
  private listeners = new Set<() => void>();
  private request?: AbortController;
  private generation = 0;
  constructor(private load: (query: Q, signal: AbortSignal) => Promise<ReadResult<T>>) {}
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  private set(state: ReadState<T>) { this.state = state; this.listeners.forEach(listener => listener()); }
  stop = () => { this.generation++; this.request?.abort(); };
  run = async (query: Q) => {
    this.stop();
    const generation = this.generation;
    const request = new AbortController();
    this.request = request;
    this.set({ status: 'loading' });
    try {
      const result = await this.load(query, request.signal);
      if (generation === this.generation) this.set(result);
    } catch (error) {
      if (generation === this.generation) this.set(error instanceof ApiError && error.code === 'WORKFLOW_DISABLED'
        ? { status: 'unavailable', message: error.message }
        : error instanceof ApiError && error.status === 403
        ? { status: 'forbidden', message: '无权查看当前资源。' }
        : error instanceof ApiError && error.status === 409 ? { status: 'stale', message: '数据已变化，请重新查询。' }
        : { status: 'error', message: '查询失败或功能未启用，请重试或联系管理员。未将故障解释为空数据。' });
    }
  };
}
