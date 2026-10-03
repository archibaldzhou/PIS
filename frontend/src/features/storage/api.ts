import { fetchCsrf, readJson, request } from '../../api';
export interface Version { id: string; assetId: string; caseId: string; rootId: string; ordinal: number; version: number; state: string; byteSize: number; sha256: string; purpose: string; createdAt: string }
export interface View { requestId: string; caseId: string; provider: string; providerRootId: string | null; s3: string; page: number; versions: Version[] }
export interface Capacity { provider: string; volumeTotal: number | null; volumeUsable: number | null; reservedBytes: number; syntheticQuota: number; measuredAt: string | null }
function obj(v: unknown): Record<string, unknown> { if (!v || typeof v !== 'object' || Array.isArray(v)) throw Error('存储响应格式错误'); return v as Record<string, unknown>; }
function str(v: unknown): string { if (typeof v !== 'string' || !v) throw Error('存储文本无效'); return v; }
function num(v: unknown): number { if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw Error('存储数量无效'); return v; }
export function parseVersion(value: unknown, caseId: string, id?: string): Version {
 const v = obj(value); if (v.caseId !== caseId || (id && v.id !== id) || !['RESERVED', 'UPLOADING', 'STAGED', 'FINALIZING', 'READY', 'FAILED'].includes(str(v.state)) || v.purpose !== 'SYNTHETIC_ORIGINAL' || !/^[a-f0-9]{64}$/.test(str(v.sha256))) throw Error('存储版本或病例不匹配');
 const byteSize = num(v.byteSize); if (byteSize < 25 || byteSize > 67108864) throw Error('原件长度不合法');
 return { id: str(v.id), assetId: str(v.assetId), caseId, rootId: str(v.rootId), ordinal: num(v.ordinal), version: num(v.version), state: str(v.state), byteSize, sha256: str(v.sha256), purpose: str(v.purpose), createdAt: str(v.createdAt) };
}
export function parseView(value: unknown, requestId: string, page: number): View {
 const v = obj(value); if (v.requestId !== requestId || v.page !== page || !Array.isArray(v.versions) || v.versions.length > 20 || !['CONFIGURED_LOCAL', 'NOT_CONFIGURED'].includes(str(v.provider)) || v.s3 !== 'NOT_CONFIGURED') throw Error('存储申请或分页不匹配');
 const caseId = str(v.caseId); return { requestId, caseId, provider: str(v.provider), providerRootId: v.providerRootId === null ? null : str(v.providerRootId), s3: str(v.s3), page, versions: v.versions.map(x => parseVersion(x, caseId)) };
}
const path = (requestId: string) => `/api/requests/${encodeURIComponent(requestId)}/storage`;
export async function list(requestId: string, page: number, signal: AbortSignal) { return parseView(await readJson(await request(`${path(requestId)}?page=${page}`, { signal })), requestId, page); }
export async function detail(requestId: string, caseId: string, id: string, signal: AbortSignal) { return parseVersion(await readJson(await request(`${path(requestId)}/${id}`, { signal })), caseId, id); }
async function command(url: string, body: unknown, key: string, signal: AbortSignal) { const csrf = await fetchCsrf(signal); return readJson(await request(url, { method: 'POST', signal, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': key }, body: JSON.stringify(body) })); }
export interface Intent { reserveKey: string; finishKey: string; assetId: string | null; expectedHead: number; caseId: string; bytes: Uint8Array<ArrayBuffer>; hash: string; id?: string; finishVersion?: number }
export function syntheticBytes(size: number): Uint8Array<ArrayBuffer> { if (![65536, 2097152].includes(size)) throw Error('仅允许有界合成夹具'); const b = new Uint8Array(size); for (let i = 0; i < size; i++) b[i] = i % 251; b.set(new TextEncoder().encode('PIS-SYNTHETIC-STORAGE-V1\n')); return b; }
export async function digest(bytes: Uint8Array<ArrayBuffer>) { return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), x => x.toString(16).padStart(2, '0')).join(''); }
export async function reserve(requestId: string, intent: Intent, signal: AbortSignal) {
 const value = obj(await command(path(requestId), { assetId: intent.assetId, expectedHead: intent.expectedHead, confirmedCaseId: intent.caseId, byteSize: intent.bytes.length, sha256: intent.hash, purpose: 'SYNTHETIC_ORIGINAL', mediaType: 'application/octet-stream' }, intent.reserveKey, signal));
 const receipt = obj(value.receipt); if (receipt.status !== 201 || receipt.resourceType !== 'STORAGE_VERSION' || receipt.version !== 0) throw Error('存储回执无效'); return str(receipt.resourceId);
}
export async function upload(requestId: string, version: Version, bytes: Uint8Array<ArrayBuffer>, signal: AbortSignal) {
 const csrf = await fetchCsrf(signal); return parseVersion(await readJson(await request(`${path(requestId)}/${version.id}/bytes`, { method: 'PUT', signal, headers: { 'Content-Type': 'application/octet-stream', 'X-Storage-Version': String(version.version), [csrf.headerName]: csrf.token }, body: bytes })), version.caseId, version.id);
}
export async function finish(requestId: string, id: string, intent: Intent, signal: AbortSignal) {
 return parseVersion(await command(`${path(requestId)}/${id}/finish`, { confirmedCaseId: intent.caseId, expectedVersion: intent.finishVersion }, intent.finishKey, signal), intent.caseId, id);
}
export async function reconcile(requestId: string, v: Version, signal: AbortSignal) { return parseVersion(await command(`${path(requestId)}/${v.id}/reconcile`, {}, crypto.randomUUID(), signal), v.caseId, v.id); }
export async function capacity(requestId: string, signal: AbortSignal): Promise<Capacity> {
 const v = obj(await readJson(await request(`${path(requestId)}/capacity`, { signal }))); const provider = str(v.provider);
 if (!['CONFIGURED_LOCAL', 'NOT_CONFIGURED'].includes(provider) || (provider === 'NOT_CONFIGURED' && (v.volumeTotal !== null || v.volumeUsable !== null || v.measuredAt !== null))) throw Error('容量状态无效');
 return { provider, volumeTotal: v.volumeTotal === null ? null : num(v.volumeTotal), volumeUsable: v.volumeUsable === null ? null : num(v.volumeUsable), reservedBytes: num(v.reservedBytes), syntheticQuota: num(v.syntheticQuota), measuredAt: v.measuredAt === null ? null : str(v.measuredAt) };
}
export async function cleanup(requestId: string, signal: AbortSignal): Promise<string[]> { const v = obj(await command(`${path(requestId)}/cleanup-dry-run`, {}, crypto.randomUUID(), signal)); if (v.requestId !== requestId || v.dryRun !== true || v.action !== 'NO_FILES_DELETED' || !Array.isArray(v.failedStagingCandidates) || v.failedStagingCandidates.length > 100) throw Error('清理预检响应无效'); return v.failedStagingCandidates.map(str); }
export async function bytes(requestId: string, v: Version, purpose: 'PREVIEW' | 'DOWNLOAD', signal: AbortSignal): Promise<ArrayBuffer> {
 const end = Math.min(v.byteSize, purpose === 'PREVIEW' ? 128 : 1048576) - 1;
 const r = await request(`${path(requestId)}/${v.id}/bytes`, { signal, headers: { Range: `bytes=0-${end}`, 'X-Storage-Purpose': purpose } });
 if (r.status !== 206 || r.headers.get('Content-Range') !== `bytes 0-${end}/${v.byteSize}` || r.headers.get('Content-Type') !== 'application/octet-stream' || r.headers.get('Content-Length') !== String(end + 1)) throw Error('原件字节响应不匹配');
 const data = await r.arrayBuffer(); if (data.byteLength !== end + 1 || (data.byteLength === v.byteSize && await digest(new Uint8Array(data)) !== v.sha256)) throw Error('原件长度或摘要不匹配'); return data;
}
