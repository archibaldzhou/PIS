import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { loadReview, loadReviewHistory, sendReview, type Detail, type Command, type Action } from './reviewApi';
type Props = { id: string; onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void; onSaved: () => void };
export function ReviewEditor({ id, ...callbacks }: Props) {
 const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadReview(id, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); if (e instanceof ApiError && [403, 404].includes(e.status)) return { status: 'forbidden' as const, message: '没有当前病例的合成复核或模拟签署资格。' }; throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(id); return reader.stop; }, [reader, id]);
 return <ReadPanel state={state}>{d => <>
  <Alert type="warning" title="仅合成工作流模拟 · 不具有临床、CA或法律签署效力 · 不发送报告" />
  <section className="identity-strip" aria-label="复核版本身份"><strong>{d.number}</strong><span>病例 {d.caseId}</span><span>患者 {d.patientId}</span><span>分配版本 {d.assignmentVersion}</span><span>流程版本 {d.version}</span><span>状态 {d.state}</span><span>报告修订 {d.draft?.id ?? '无'}</span><span>模板 {d.draft?.templateCode} / v{d.draft?.templateVersion}</span></section>
  <Alert type={d.ready ? 'info' : 'warning'} title={d.state === 'SIMULATED_SIGNED' ? '合成模拟已完成，原草稿冻结；历史成功不代表当前QC仍就绪' : d.ready ? '当前复核依赖有效，模拟签署仍需服务器重新验证' : '尚无当前有效复核，不能模拟签署'} description={`开发策略 ${d.policy.code}：作者与复核者${d.policy.separateAuthorReview ? '必须不同' : '允许相同'}；复核者与模拟签署者${d.policy.separateReviewSign ? '必须不同' : '允许相同'}。`} />
  <Card title="确切修订的人工字段"><pre style={{ whiteSpace: 'pre-wrap' }}>{d.draft ? JSON.stringify(d.draft.fields, null, 2) : '尚无草稿'}</pre></Card>
  <ReviewForm detail={d} {...callbacks} />
  <ReviewHistory id={id} onExpired={callbacks.onExpired} />
 </>}</ReadPanel>;
}
function ReviewForm({ detail: d, onDirty, onClean, onPending, onExpired, onSaved }: Omit<Props, 'id'> & { detail: Detail }) {
 const [action, setAction] = useState<Action>('APPROVE'), [next, setNext] = useState<Action | undefined>(), [confirm, setConfirm] = useState<Command | undefined>(), [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState('');
 const [form] = Form.useForm<{ confirmedId: string; reason: string }>(); const [intent] = useState(() => new CommandIntent<Command>()); const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined);
 useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
 const allowed = !!d.draft && d.state !== 'SIMULATED_SIGNED' && (action === 'SIMULATE_SIGN' ? d.canSimulateSign && d.ready : d.canReview);
 async function execute(c: Command) {
  if (active.current) return; active.current = true; original.current = c; setConfirm(undefined); setBusy(true); onDirty(); onPending(true); setMessage('');
  try { if (await intent.run(c, sendReview) && alive.current) { onPending(false); onClean(); onSaved(); } }
  catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; } const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)); if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '模拟操作结果待确认，请保留原病例、修订和请求键重试。'); }
  finally { active.current = false; if (alive.current) setBusy(false); }
 }
 return <Card title="人工复核与模拟签署">
  {message && <Alert role="status" type="info" title={message} />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>确认原复核请求</Button>}
  <label htmlFor="review-action">合成复核动作</label><Select id="review-action" virtual={false} value={action} disabled={busy || uncertain || !!confirm || d.state === 'SIMULATED_SIGNED'} style={{ width: '100%' }} options={[{ value: 'APPROVE', label: '复核通过（合成）' }, { value: 'RETURN', label: '退回修改' }, { value: 'SIMULATE_SIGN', label: '模拟签署（无临床效力）' }]} onChange={(v: Action) => { if (v !== action) setNext(v); }} />
  <Form form={form} layout="vertical" disabled={busy || uncertain || !!confirm || d.state === 'SIMULATED_SIGNED'} onValuesChange={onDirty} onFinish={v => { if (!allowed || !d.draft || busy || uncertain) return; const c: Command = { caseId: d.caseId, action, body: { expectedVersion: d.version, confirmedCaseId: v.confirmedId, revisionId: d.draft.id, draftVersion: d.draft.version, assignmentVersion: d.assignmentVersion, templateCode: d.draft.templateCode, templateVersion: d.draft.templateVersion, dependencyToken: d.dependencyToken, reason: v.reason, simulationAcknowledged: action === 'SIMULATE_SIGN' } }; if (action === 'SIMULATE_SIGN') setConfirm(c); else void execute(c); }}>
   <Form.Item name="confirmedId" label="核对复核病例 UUID" rules={[{ validator: (_, v: unknown) => v === d.caseId ? Promise.resolve() : Promise.reject(new Error('请核对当前病例 UUID')) }]}><Input maxLength={36} /></Form.Item>
   <Form.Item name="reason" label="复核或退回原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} /></Form.Item>
   <Button htmlType="submit" type="primary" disabled={!allowed || busy || uncertain || !!confirm}>提交合成复核操作</Button>
  </Form>
  <Modal open={!!next} title="切换动作并清除原因？" okText="清除并切换动作" cancelText="保留当前输入" onCancel={() => setNext(undefined)} onOk={() => { if (next) setAction(next); setNext(undefined); form.resetFields(); onDirty(); }}><p>重新核对当前病例、报告修订和操作原因。</p></Modal>
  <Modal open={!!confirm} title="确认仅执行无临床效力的模拟签署？" okText="确认合成模拟" cancelText="取消模拟" onCancel={() => setConfirm(undefined)} onOk={() => { if (confirm) void execute(confirm); }}><p>病例 {confirm?.caseId}，修订 {confirm?.body.revisionId}。不会生成CA签名或发送报告。服务器成功后原草稿冻结。</p></Modal>
 </Card>;
}

function ReviewHistory({ id, onExpired }: { id: string; onExpired: () => void }) {
 const [page, setPage] = useState(1); const [reader] = useState(() => new ReadController(async (p: number, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadReviewHistory(id, p, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(page); return reader.stop; }, [reader, page]);
 return (  <Card title="不可变复核历史"><ReadPanel state={state}>{events => <><Table rowKey="id" dataSource={events} pagination={false} scroll={{ x: 1100 }} columns={[{ title: '版本', dataIndex: 'version' }, { title: '动作', dataIndex: 'action' }, { title: '报告修订', dataIndex: 'revisionId' }, { title: '操作者', dataIndex: 'actorId' }, { title: '原因', dataIndex: 'reason' }, { title: '原复核', dataIndex: 'reviewId' }, { title: '时间', dataIndex: 'occurredAt' }]} /><Button disabled={page === 1} onClick={() => setPage(p => p - 1)}>上一页复核历史</Button><span>第 {page} 页</span><Button disabled={events.length < 20 || page >= 10000} onClick={() => setPage(p => p + 1)}>下一页复核历史</Button></>}</ReadPanel></Card>);
}
