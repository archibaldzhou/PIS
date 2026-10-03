import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { load, send, snapshot, type Detail, type Command, type Kind } from './amendmentApi';
import { OutputEditor } from './OutputEditor';
type Props = { id: string; onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void; onSaved: () => void };
export function AmendmentEditor({ id, ...callbacks }: Props) {
 const [page, setPage] = useState(1), [selected, setSelected] = useState(''), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [confirming, setConfirming] = useState(false), [reset, setReset] = useState(0); const next = useRef<(() => void) | undefined>(undefined);
 const local = { ...callbacks, onDirty: () => { setDirty(true); callbacks.onDirty(); }, onClean: () => { setDirty(false); callbacks.onClean(); }, onPending: (v: boolean) => { setPending(v); callbacks.onPending(v); } };
 function change(action: () => void) { if (pending) return; if (dirty) { next.current = action; setConfirming(true); } else action(); }
 function select(value: string) { if (value !== selected) change(() => setSelected(value)); }
 const [reader] = useState(() => new ReadController(async (p: number, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await load(id, p, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(page); return reader.stop; }, [reader, page]);
 return <><Alert type="warning" title="报告补充与更正 · 合成演示／非临床报告" description="原版永久保留。新草稿需重新人工编辑、复核及模拟签署；下游仅待替换，未发送、无ACK。" />
 <ReadPanel state={state}>{d => <>
  <section className="identity-strip" aria-label="版本链身份"><span>病例 {id}</span><span>链版本 {d.version}</span><span>新草稿ID {d.draftId ?? '原始报告'}</span><span>当前修订 {d.revisionId ?? '尚无草稿'} / {d.draftVersion}</span></section>
  <Alert type={d.pending ? 'warning' : 'info'} title={d.pending ? '存在尚未完成的新草稿；旧冻结版尚未被替代' : '当前草稿已模拟冻结；可申请新的补充或更正'} />
  {d.frozenSignatureId && <Button onClick={() => select(d.frozenSignatureId ?? '')}>查看当前冻结版</Button>}
  {!selected && <CreateForm key={reset} detail={d} {...local} />}
  {selected && <Button onClick={() => select('')}>返回新版本草稿操作</Button>}
  <Card title="补充／更正追加版本链"><Table rowKey="id" dataSource={d.nodes} pagination={false} scroll={{ x: 1000 }} columns={[{ title: '链版本 / 新草稿', key: 'version', render: (_, n) => <span>{n.version} / {n.id}</span> }, { title: '类型', key: 'kind', render: (_, n) => n.kind === 'ADDENDUM' ? '补充' : '更正' }, { title: '原因', dataIndex: 'reason' }, { title: '状态', key: 'state', render: (_, n) => n.newSignatureId ? '已模拟冻结；下游待替换／未发送' : '新草稿待重新复核' }, { title: '版本对照', key: 'links', render: (_, n) => <><Button onClick={() => select(n.baseSignatureId)}>查看原冻结版 {n.version - 1}</Button>{n.newSignatureId && <Button onClick={() => select(n.newSignatureId ?? '')}>查看冻结版 {n.version}</Button>}</> }]} /><Button disabled={page === 1} onClick={() => change(() => setPage(p => p - 1))}>上一页版本链</Button><span>第 {page} 页</span><Button disabled={d.nodes.length < 20 || page >= 10000} onClick={() => change(() => setPage(p => p + 1))}>下一页版本链</Button></Card>
 </>}</ReadPanel>
 {selected && <Frozen key={id + ':' + selected + ':' + reset} id={id} signature={selected} {...local} />}
 <Modal open={confirming} title="放弃当前版本操作输入？" okText="放弃并查看版本" cancelText="继续当前操作" onCancel={() => { next.current = undefined; setConfirming(false); }} onOk={() => { const action = next.current; next.current = undefined; setConfirming(false); local.onClean(); setReset(v => v + 1); action?.(); }}><p>不会撤销已经提交的操作；重新核对所选版本。</p></Modal></>;
}
function CreateForm({ detail: d, onDirty, onClean, onPending, onExpired, onSaved }: Omit<Props, 'id'> & { detail: Detail }) {
 const [kind, setKind] = useState<Kind>('ADDENDUM'), [next, setNext] = useState<Kind | undefined>(), [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState('');
 const [form] = Form.useForm<{ confirmedId: string; reason: string }>(), [intent] = useState(() => new CommandIntent<Command>()); const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined);
 useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
 async function execute(c: Command) {
  if (active.current) return; active.current = true; original.current = c; setBusy(true); onDirty(); onPending(true); setMessage('');
  try { if (await intent.run(c, send) && alive.current) { onPending(false); onClean(); onSaved(); } }
  catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; } const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)); if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '新版本结果待确认；保留原病例、原版、原因与请求键重试。'); }
  finally { active.current = false; if (alive.current) setBusy(false); }
 }
 return <Card title="创建独立补充／更正草稿">
  <p>保留原模板和人工字段作为新草稿初始副本；成功后到“报告草稿”人工编辑，再重新复核、模拟签署并生成固定PDF。没有自动诊断或自动签署。</p>
  {!d.canCreate && <Alert type="warning" title="仅当前持有病例、资格及QC就绪且没有待完成草稿时可建新版本" />}
  {message && <Alert role="status" title={message} />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>确认原新版本请求</Button>}
  <label htmlFor="amendment-kind">新版本类型</label><Select id="amendment-kind" virtual={false} value={kind} disabled={busy || uncertain} style={{ width: '100%' }} options={[{ value: 'ADDENDUM', label: '补充报告' }, { value: 'CORRECTION', label: '更正报告' }]} onChange={(v: Kind) => { if (v !== kind) setNext(v); }} />
  <Form form={form} layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={v => { if (!d.canCreate || !d.frozenSignatureId || !d.frozenRevisionId || busy || uncertain) return; void execute({ caseId: d.caseId, body: { confirmedCaseId: v.confirmedId, expectedVersion: d.version, assignmentVersion: d.assignmentVersion, baseSignatureId: d.frozenSignatureId, baseRevisionId: d.frozenRevisionId, baseDraftVersion: d.draftVersion, kind, reason: v.reason } }); }}>
   <Form.Item name="confirmedId" label="核对版本链病例 UUID" rules={[{ validator: (_, v: unknown) => v === d.caseId ? Promise.resolve() : Promise.reject(new Error('请核对病例UUID')) }]}><Input maxLength={36} /></Form.Item>
   <Form.Item name="reason" label="补充／更正强制原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} maxLength={2000} /></Form.Item>
   <Button type="primary" htmlType="submit" disabled={!d.canCreate || busy || uncertain}>创建新版本草稿</Button>
  </Form>
  <Modal open={!!next} title="切换补充／更正类型并清除输入？" okText="清除并切换类型" cancelText="保留当前类型和输入" onCancel={() => setNext(undefined)} onOk={() => { if (next) setKind(next); setNext(undefined); form.resetFields(); onDirty(); }}><p>原报告保持不变，须重新填写原因和核对病例。</p></Modal>
 </Card>;
}
function Frozen({ id, signature, ...callbacks }: Props & { signature: string }) {
 const [reader] = useState(() => new ReadController(async (_: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await snapshot(id, signature, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(signature); return reader.stop; }, [reader, signature]);
 return <Card title="冻结版本只读对照"><ReadPanel state={state}>{s => <><Alert type="warning" title={s.currentFrozen ? '只读当前冻结版；不是正在编辑的新草稿' : '正在查看历史旧版；已被新版替代，原文与PDF未改写'} /><p>模拟签署 {s.signatureId} · 修订 {s.revision.id} / v{s.revision.version} · 模板 {s.revision.templateCode} v{s.revision.templateVersion}</p><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(s.revision.fields, null, 2)}</pre>{s.artifactId ? <OutputEditor id={id} artifactId={s.artifactId} frozenBinding={{ signatureId: s.signatureId, revisionId: s.revision.id, draftVersion: s.revision.version }} readOnlyHistory {...callbacks} /> : <Alert type="info" title="此冻结版尚无固定PDF；不会使用新模板伪造旧产物" />}</>}</ReadPanel></Card>;
}