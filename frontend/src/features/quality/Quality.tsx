import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table, Tag } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { loadList, loadDetail, send, type Command, type Detail } from './api';
type Callbacks = { onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void };
export function Quality({ requestId, ...callbacks }: Callbacks & { requestId: string }) {
  const [selected, setSelected] = useState(''), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [next, setNext] = useState<string | null>(null), [revision, setRevision] = useState(0);
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadList(id, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); throw e; } }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(requestId); return reader.stop; }, [reader, requestId]);
  const clean = () => { setDirty(false); callbacks.onClean(); };
  const change = (id: string) => { clean(); setNext(null); if (id === 'refresh') { setRevision(v => v + 1); void reader.run(requestId); } else setSelected(id); };
  const choose = (id: string) => { if (pending) return; if (dirty) setNext(id); else change(id); };
  return <>
    <Alert type="warning" title="合成技术 QC · 非临床放行" description="未质检、待定、不合格均不是通过。隔离阻断新消费；返工需新任务和新实体，原结论保留。" />
    <Button disabled={pending} onClick={() => choose('refresh')}>刷新质检与隔离状态</Button>
    <ReadPanel state={state}>{items => <Card title="逐实体质检与隔离"><Table rowKey={i => i.subject.id} dataSource={items} pagination={false} scroll={{ x: 850 }} columns={[
      { title: '材料', key: 'number', render: (_, i) => i.subject.number }, { title: '实体版本', key: 'version', render: (_, i) => i.subject.version }, { title: '当前有效状态', dataIndex: 'effectiveState' }, { title: 'QC版本', key: 'qc', render: (_, i) => i.head?.version ?? '尚未质检' },
      { title: '操作', key: 'choose', render: (_, i) => <Button disabled={pending} onClick={() => choose(i.subject.id)}>质检材料 {i.subject.id}</Button> },
    ]} />{items.length === 0 && <p>此病例没有可查看的材料；不会自动生成玻片或合格结论。</p>}</Card>}</ReadPanel>
    {selected && <Editor key={`${selected}:${revision}`} id={selected} {...callbacks} onDirty={() => { setDirty(true); callbacks.onDirty(); }} onClean={clean} onPending={v => { setPending(v); callbacks.onPending(v); }} onSaved={() => { clean(); setRevision(v => v + 1); void reader.run(requestId); }} />}
    <Modal open={next !== null} title="放弃未保存的 QC 输入？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNext(null)} onOk={() => { if (next !== null) change(next); }}><p>重新选择后核对确切材料与版本。</p></Modal>
  </>;
}
function Editor({ id, onSaved, ...callbacks }: Callbacks & { id: string; onSaved: () => void }) {
  const [reader] = useState(() => new ReadController(async (key: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadDetail(key, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); throw e; } }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(id); return reader.stop; }, [reader, id]);
  return <ReadPanel state={state}>{detail => <>
    <section className="identity-strip" aria-label="质检材料身份" data-material-id={id}><strong>{detail.item.subject.number}</strong><span>材料 {id}</span><span>患者 {detail.item.subject.patientId}</span><span>病例 {detail.item.subject.caseId}</span><span>材料版本 {detail.item.subject.version}</span><span>任务 {detail.item.subject.taskId ?? '直制无盒级任务'} / {detail.item.subject.taskVersion ?? '不适用'}</span><Tag>{detail.item.effectiveState}</Tag><span>QC版本 {detail.item.head?.version ?? '尚未质检'}</span></section>
    <Card title="QC 判定与处置"><QualityForm detail={detail} {...callbacks} onSaved={onSaved} /></Card>
    <Alert type="info" title="异常放行已禁用：医院审批角色与规则未批准，管理员也不能绕过身份错误" /><Button disabled>异常放行（未批准）</Button>
    {detail.item.head?.repairTaskId && <Alert type="info" title={'关联返工任务：' + detail.item.head.repairTaskId} description="在技术任务页面完成合成演练，再登记新材料并独立质检；旧材料保持隔离。" />}
    <Card title="判定历史（最多100项）"><Table rowKey="id" dataSource={detail.assessments} pagination={false} scroll={{ x: 1000 }} columns={[{ title: '结论', dataIndex: 'outcome' }, { title: '材料版本', dataIndex: 'materialVersion' }, { title: '任务 / 版本', key: 'task', render: (_, a) => `${a.taskId ?? '直制'} / ${a.taskVersion ?? '不适用'}` }, { title: '标准', dataIndex: 'standardVersion' }, { title: '原因', dataIndex: 'reason' }, { title: '操作者', dataIndex: 'actorId' }]} /></Card>
    <Card title="隔离与失效事件"><Table rowKey="id" dataSource={detail.events} pagination={false} scroll={{ x: 900 }} columns={[{ title: 'QC版本', dataIndex: 'version' }, { title: '动作', dataIndex: 'action' }, { title: '关联返工任务', dataIndex: 'relatedTaskId' }, { title: '原因', dataIndex: 'reason' }, { title: '操作者', dataIndex: 'actorId' }, { title: '时间 UTC', dataIndex: 'occurredAt' }]} /></Card>
  </>}</ReadPanel>;
}
interface Values { action: string; outcome: string; confirmedId: string; reason: string }
function QualityForm({ detail, onDirty, onPending, onExpired, onSaved }: Pick<Callbacks, 'onDirty' | 'onPending' | 'onExpired'> & { detail: Detail; onSaved: () => void }) {
  const [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState(''), [action, setAction] = useState('assess'), [nextAction, setNextAction] = useState('');
  const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined); const [intent] = useState(() => new CommandIntent<Command>()); const [form] = Form.useForm<Values>();
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  async function execute(command: Command) {
    if (active.current) return; active.current = true; original.current = command; setBusy(true); onDirty(); onPending(true); setMessage('');
    try { const saved = await intent.run(command, send); if (saved && alive.current) { onPending(false); onSaved(); } }
    catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; }
      const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || (e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)));
      if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : 'QC结果待确认；保留原对象、输入和请求键重试。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  function submit(v: Values) {
    if (busy || uncertain) return; const s = detail.item.subject;
    const body: Command['body'] = { expectedVersion: detail.item.head?.version ?? -1, confirmedMaterialId: v.confirmedId, reason: v.reason };
    if (action === 'assess') Object.assign(body, { materialVersion: s.version, taskVersion: s.taskVersion, standardVersion: 'SYN-MATERIAL-QC-1', outcome: v.outcome });
    void execute({ path: '/api/quality/materials/' + s.id + '/' + action, body });
  }
  return <>
    {message && <Alert role="status" type="info" title={message} />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>重试原QC请求</Button>}
    <Form<Values> form={form} layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={submit}>
      <Form.Item label="质检操作" htmlFor="qc-action"><Select id="qc-action" virtual={false} value={action} onChange={v => { if (form.isFieldsTouched()) setNextAction(v); else { setAction(v); form.resetFields(); onDirty(); } }} options={[{ value: 'assess', label: '追加质检判定' }, { value: 'revoke', label: '撤销当前QC' }, { value: 'rework', label: '隔离并创建返工任务' }]} /></Form.Item>
      {action === 'assess' && <><p>合成标准 SYN-MATERIAL-QC-1（不替代医院标准）</p><Form.Item name="outcome" label="明确质检结论" preserve={false} rules={[{ required: true }]}><Select virtual={false} options={[{ value: 'PASS', label: 'PASS · 合成检查通过' }, { value: 'FAIL', label: 'FAIL · 不合格隔离' }, { value: 'PENDING', label: 'PENDING · 待定隔离' }, { value: 'IDENTITY_MISMATCH', label: 'IDENTITY_MISMATCH · 身份错误，持续阻断' }]} /></Form.Item></>}
      <Form.Item name="confirmedId" label="核对质检材料 UUID" rules={[{ required: true, whitespace: true }]}><Input maxLength={36} /></Form.Item>
      <Form.Item name="reason" label="QC事实与处置原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} /></Form.Item>
      <Button type="primary" htmlType="submit" disabled={busy || uncertain}>提交明确QC操作</Button>
    </Form>
    <Modal open={!!nextAction} title="切换QC操作并清除当前输入？" okText="清除并切换" cancelText="继续编辑" onCancel={() => setNextAction('')} onOk={() => { setAction(nextAction); setNextAction(''); form.resetFields(); onDirty(); }}><p>不同QC操作不提交隐藏的旧判定。</p></Modal>
  </>;
}
