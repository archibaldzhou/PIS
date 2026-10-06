import { fetchCsrf, readJson, request } from '../../api';

export interface Option { id: string; name: string }
export interface Hospital extends Option { manage: boolean }
export interface Scope extends Option { campusId: string; departmentId: string; sourceId: string; enabled: boolean; version: number }
export interface Permission { code: string; name: string; professional: boolean; menu: string }
export interface Role { code: string; name: string; permissions: string[]; available: boolean }
export interface Catalog { roles: Role[]; permissions: Permission[]; campuses: Option[]; departments: Option[]; sources: Option[]; scopes: Scope[] }
export interface Assignment { scopeId: string; roles: string[]; permissions: string[]; qualificationVerified: boolean; validUntil: string }
export interface User { id: string; username: string; displayName: string; employeeNumber: string; enabled: boolean; passwordChangeRequired: boolean; administrator: boolean; defaultScopeId: string | null; version: number; assignments: Assignment[] }
export interface UserPage { items: User[]; total: number; page: number }
export interface Event { id: string; actorId: string; targetId: string; action: string; reason: string; version: number; occurredAt: string; changeSet: string }
export interface WorkContext { defaultScopeId: string | null; name: string; scopes: Option[]; menus: string[]; administration: boolean; writableScopes: string[] }
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('管理接口格式不正确'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string') throw new Error('管理接口字段不正确'); return v; }
function bool(v: unknown): boolean { if (typeof v !== 'boolean') throw new Error('管理接口布尔字段不正确'); return v; }
function num(v: unknown): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw new Error('管理接口版本不正确'); return v; }
function list<T>(v: unknown, parse: (item: unknown) => T): T[] { if (!Array.isArray(v)) throw new Error('管理接口列表不正确'); return v.map(parse); }
function option(v: unknown): Option { const r = obj(v); return { id: str(r.id), name: str(r.name) }; }
function assignment(v: unknown): Assignment { const r = obj(v); return { scopeId: str(r.scopeId), roles: list(r.roles, str), permissions: list(r.permissions, str), qualificationVerified: bool(r.qualificationVerified), validUntil: str(r.validUntil) }; }
function user(v: unknown): User { const r = obj(v); return { id: str(r.id), username: str(r.username), displayName: str(r.displayName), employeeNumber: str(r.employeeNumber), enabled: bool(r.enabled), passwordChangeRequired: bool(r.passwordChangeRequired), administrator: bool(r.administrator), defaultScopeId: r.defaultScopeId === null ? null : str(r.defaultScopeId), version: num(r.version), assignments: list(r.assignments, assignment) }; }
async function get(path: string, signal: AbortSignal) { return readJson(await request('/api/requests/' + path, { signal })); }
export async function workContext(signal: AbortSignal): Promise<WorkContext> {
  const r = obj(await get('work-context', signal)); return { defaultScopeId: r.defaultScopeId === null ? null : str(r.defaultScopeId), name: str(r.name), scopes: list(r.scopes, option), menus: list(r.menus, str), administration: bool(r.administration), writableScopes: list(r.writableScopes, str) };
}
export const identityApi = {
  async hospitals(signal: AbortSignal): Promise<Hospital[]> { return list(await get('admin/hospitals', signal), v => { const r = obj(v); return { ...option(v), manage: bool(r.manage) }; }); },
  async catalog(h: string, signal: AbortSignal): Promise<Catalog> { const r = obj(await get(`admin/${encodeURIComponent(h)}/catalog`, signal)); return {
    roles: list(r.roles, v => { const x = obj(v); return { code: str(x.code), name: str(x.name), permissions: list(x.permissions, str), available: bool(x.available) }; }),
    permissions: list(r.permissions, v => { const x = obj(v); return { code: str(x.code), name: str(x.name), professional: bool(x.professional), menu: str(x.menu) }; }),
    campuses: list(r.campuses, option), departments: list(r.departments, option), sources: list(r.sources, option),
    scopes: list(r.scopes, v => { const x = obj(v); return { ...option(v), campusId: str(x.campusId), departmentId: str(x.departmentId), sourceId: str(x.sourceId), enabled: bool(x.enabled), version: num(x.version) }; }),
  }; },
  async users(h: string, search: string, page: number, signal: AbortSignal): Promise<UserPage> { const r = obj(await get(`admin/${encodeURIComponent(h)}/users?` + new URLSearchParams({ search, page: String(page) }), signal)); return { items: list(r.items, user), total: num(r.total), page: num(r.page) }; },
  async user(h: string, id: string, signal: AbortSignal) { return user(await get(`admin/${encodeURIComponent(h)}/users/${encodeURIComponent(id)}`, signal)); },
  async events(h: string, page: number, signal: AbortSignal): Promise<Event[]> { return list(await get(`admin/${encodeURIComponent(h)}/audit?page=${page}`, signal), v => { const r = obj(v); return { id: str(r.id), actorId: str(r.actorId), targetId: str(r.targetId), action: str(r.action), reason: str(r.reason), version: num(r.version), occurredAt: str(r.occurredAt), changeSet: str(r.changeSet) }; }); },
};
export interface Change { path: string; method: 'POST' | 'PUT'; body: unknown }
export async function change(input: Change, key: string) {
  const signal = AbortSignal.timeout(15000); const csrf = await fetchCsrf(signal);
  const r = obj(await readJson(await request('/api/requests/admin/' + input.path, { method: input.method, signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(input.body) })));
  const receipt = obj(r.receipt); return { id: str(receipt.resourceId), version: num(receipt.version) };
}
export async function changePassword(oldPassword: string, newPassword: string) {
  const signal = AbortSignal.timeout(15000); const csrf = await fetchCsrf(signal);
  await request('/api/auth/password', { method: 'POST', signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify({ oldPassword, newPassword }) });
}
