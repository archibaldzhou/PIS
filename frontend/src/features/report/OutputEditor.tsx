import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { labels, loadOutput, loadHistory, sendOutput, type Detail, type Command, type Kind, type Result } from './outputApi';
type Props = { id: string; artifactId?: string; readOnlyHistory?: boolean; frozenBinding?: { signatureId: string; revisionId: string; draftVersion: number }; onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void; onSaved: () => void };
export function OutputEditor({ id, artifactId, readOnlyHistory, frozenBinding, ...callbacks }: Props) {
 const [preview, setPreview] = useState(''), url = useRef(''); const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => { try { const data = await loadOutput(id, signal, artifactId); if (frozenBinding && (data.signatureId !== frozenBinding.signatureId || data.revisionId !== frozenBinding.revisionId || data.artifact?.draftVersion !== frozenBinding.draftVersion)) throw new Error('Historical PDF does not match selected frozen revision'); return { status: 'ready' as const, data }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); if (e instanceof ApiError && [403, 404].includes(e.status)) return { status: 'forbidden' as const, message: '报告产物或当前资格不可用。' }; throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(id); return () => { reader.stop(); if (url.current) URL.revokeObjectURL(url.current); }; }, [reader, id]);
 function complete(result: Result) {
  if (url.current) URL.revokeObjectURL(url.current); url.current = ''; setPreview('');
  if (result.kind === 'pdf') { const next = URL.createObjectURL(result.blob); url.current = next; if (result.download) { const a = document.createElement('a'); a.href = next; a.download = 'synthetic-' + result.artifact.id + '-v0.pdf'; a.click(); } else setPreview(next); }
  callbacks.onClean(); void reader.run(id);
 }
 return <>
  <Alert type="warning" title="合成演示／非临床报告 · 固定PDF · 不发送、不连接打印机" description="预览、下载和打印请求不是物理打印成功。用户自报结果没有硬件反馈；历史PDF不表示当前QC就绪。" />
  <ReadPanel state={state}>{d => <>
   <section className="identity-strip" aria-label="输出固定版本"><span>病例 {d.caseId}</span><span>冻结修订 {d.revisionId}</span><span>模拟事件 {d.signatureId} / v{d.signatureVersion}</span><span>活动版本 {d.activityVersion}</span></section>
   {!d.dependenciesCurrent && <Alert type="warning" title={readOnlyHistory ? "只读冻结快照；按当前权限访问保存产物，此模式不生成或登记打印请求" : "当前资格、分配或QC依赖已变化；仅可按当前权限访问历史产物，禁止新生成及打印请求"} />}
   {d.artifact && <Card title="不可变产物"><p>产物 {d.artifact.id} / v0 · {d.artifact.pages} 页 · {d.artifact.byteSize} 字节</p><p>模板 {d.artifact.templateCode} v{d.artifact.templateVersion} · {d.artifact.schemaCode}</p><p style={{ overflowWrap: 'anywhere' }}>SHA-256 {d.artifact.sha256}</p><p style={{ overflowWrap: 'anywhere' }}>冻结依赖 {d.artifact.dependencyToken}</p><p>固定栅格PDF，不含可搜索文本或电子签名。</p></Card>}
   <OutputForm detail={d} readOnlyHistory={readOnlyHistory} {...callbacks} onComplete={complete} />
   {d.artifact && <History id={id} artifact={d.artifact.id} onExpired={callbacks.onExpired} />}
  </>}</ReadPanel>
  {preview && <Card title="已校验固定PDF预览"><Button onClick={() => { URL.revokeObjectURL(url.current); url.current = ''; setPreview(''); }}>关闭PDF预览</Button><iframe title="合成固定PDF预览" src={preview + '#toolbar=0'} style={{ width: '100%', height: 650, border: 0 }} /></Card>}
 </>;
}
function OutputForm({ detail: d, readOnlyHistory, onDirty, onPending, onExpired, onComplete }: Omit<Props, 'id'> & { detail: Detail; onComplete: (r: Result) => void }) {
 const [action, setAction] = useState<Kind>('PREVIEW'), [next, setNext] = useState<Kind | undefined>(), [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState('');
 const [form] = Form.useForm<{ confirmedId: string; reason: string; requestId?: string }>(); const [intent] = useState(() => new CommandIntent<Command>()); const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined);
 useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
 const needsRequest = action === 'REPRINT_REQUEST' || action.startsWith('USER_REPORTED_'); const allowed = d.artifact ? !['PRINT_REQUEST', 'REPRINT_REQUEST'].includes(action) || d.dependenciesCurrent : d.dependenciesCurrent;
 async function execute(c: Command) {
  if (active.current) return; active.current = true; original.current = c; setBusy(true); onDirty(); onPending(true); setMessage('');
  try { const r = await intent.run(c, sendOutput); if (r && alive.current) { onPending(false); onComplete(r); } }
  catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; } const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)); if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '产物操作结果待确认，请使用原病例、产物、输入和请求键确认；不会连接打印机。'); }
  finally { active.current = false; if (alive.current) setBusy(false); }
 }
 return <Card title={d.artifact ? '固定产物访问与打印记录' : '首次生成固定合成PDF'}>
  {message && <Alert role="status" type="info" title={message} />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>确认原产物请求</Button>}
  {d.artifact && <><label htmlFor="output-action">产物操作</label><Select id="output-action" virtual={false} value={action} disabled={busy || uncertain} style={{ width: '100%' }} options={Object.entries(labels).filter(([value]) => !readOnlyHistory || ['PREVIEW', 'DOWNLOAD'].includes(value)).map(([value, label]) => ({ value, label }))} onChange={(v: Kind) => { if (v !== action) setNext(v); }} /></>}
  <Form form={form} layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={v => { if (!allowed || busy || uncertain) return; const a = d.artifact; const c: Command = a ? { caseId: d.caseId, kind: action, artifact: a, body: { confirmedCaseId: v.confirmedId, artifactVersion: a.version, sha256: a.sha256, expectedVersion: d.activityVersion, requestId: needsRequest ? v.requestId ?? null : null, reason: v.reason } } : { caseId: d.caseId, kind: 'CREATE', body: { confirmedCaseId: v.confirmedId, signatureId: d.signatureId, signatureVersion: d.signatureVersion, revisionId: d.revisionId, reason: v.reason } }; void execute(c); }}>
   <Form.Item name="confirmedId" label="核对输出病例 UUID" rules={[{ validator: (_, v: unknown) => v === d.caseId ? Promise.resolve() : Promise.reject(new Error('请核对当前病例 UUID')) }]}><Input maxLength={36} /></Form.Item>
   {d.artifact && needsRequest && <Form.Item name="requestId" label="原打印请求 UUID（见历史）" preserve={false} rules={[{ required: true }, { pattern: /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i }]}><Input maxLength={36} /></Form.Item>}
   <Form.Item name="reason" label="产物访问或打印记录原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} /></Form.Item><Button htmlType="submit" type="primary" disabled={!allowed || busy || uncertain}>{d.artifact ? '提交产物操作（不物理打印）' : '生成固定合成PDF'}</Button>
  </Form>
  <Modal open={!!next} title="切换产物操作并清除输入？" okText="清除并切换操作" cancelText="保留当前输入" onCancel={() => setNext(undefined)} onOk={() => { if (next) setAction(next); setNext(undefined); form.resetFields(); onDirty(); }}><p>重新核对产物与原因；仅记录请求或用户自报，不发送打印机命令。</p></Modal>
 </Card>;
}
function History({ id, artifact, onExpired }: { id: string; artifact: string; onExpired: () => void }) {
 const [page, setPage] = useState(1); const [reader] = useState(() => new ReadController(async (p: number, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadHistory(id, artifact, p, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(page); return reader.stop; }, [reader, page]);
 return <Card title="不可变访问与打印历史"><ReadPanel state={state}>{rows => <><Table rowKey="id" dataSource={rows} pagination={false} scroll={{ x: 1300 }} columns={[{ title: '记录UUID', dataIndex: 'id' }, { title: '版本', dataIndex: 'version' }, { title: '记录类型（非硬件反馈）', key: 'kind', render: (_, r) => labels[r.kind] }, { title: '原请求', dataIndex: 'requestId' }, { title: '操作者', dataIndex: 'actorId' }, { title: '原因', dataIndex: 'reason' }, { title: '时间', dataIndex: 'occurredAt' }]} /><Button disabled={page === 1} onClick={() => setPage(p => p - 1)}>上一页产物历史</Button><span>第 {page} 页</span><Button disabled={rows.length < 20 || page >= 10000} onClick={() => setPage(p => p + 1)}>下一页产物历史</Button></>}</ReadPanel></Card>;
}
