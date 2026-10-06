import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { loadList, loadDetail, send, type Filter, type Command, type Detail } from './api';
export type DiagnosisCallbacks = { onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void };
function unavailable(e: unknown, expired: () => void) { if (e instanceof ApiError && e.status === 401) expired(); if (e instanceof ApiError && [403, 404].includes(e.status)) return { status: 'forbidden' as const, message: '诊断对象或范围不可用，或没有诊断授权。' }; throw e; }
export function Diagnosis({ scopeId, renderEditor, editorKind, ...callbacks }: DiagnosisCallbacks & { scopeId: string; editorKind?: 'consultation' | 'review' | 'output' | 'amendment' | 'delivery'; renderEditor?: (id: string, callbacks: DiagnosisCallbacks & { onSaved: () => void }) => React.ReactNode }) {
  const [filter, setFilter] = useState<Filter>({ page: 1, state: 'ALL' }), [selected, setSelected] = useState(''), [revision, setRevision] = useState(0), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [confirming, setConfirming] = useState(false);
  const next = useRef<(() => void) | undefined>(undefined);
  const [reader] = useState(() => new ReadController(async (f: Filter, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadList(scopeId, f, signal, editorKind === 'output' || editorKind === 'delivery') }; } catch (e) { return unavailable(e, callbacks.onExpired); } }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(filter); return reader.stop; }, [reader, filter]);
  function clean() { setDirty(false); callbacks.onClean(); }
  function change(action: () => void) { if (pending) return; if (dirty) { next.current = action; setConfirming(true); } else action(); }
  return <>
    <Alert type="warning" title={editorKind === 'consultation' ? '院内合成会诊与复阅 · 病例限时授权' : editorKind === 'delivery' ? '本地投递合成模拟 · 无外部发送' : editorKind === 'amendment' ? '报告补充与更正 · 原版只读保留' : editorKind === 'output' ? '固定合成PDF与打印记录 · 无物理打印命令' : editorKind === 'review' ? '合成复核与模拟签署 · 独立资格及职责分离策略' : renderEditor ? '合成报告草稿 · 仅当前已领取的合格医生可编辑' : '诊断分配演练 · 仅合成资格与材料QC就绪病例'} description={editorKind === 'consultation' ? '仅明确受邀医生可访问本次会诊，不扩大原组织范围；会诊不签署、不对外共享。' : editorKind === 'delivery' ? 'HTTP成功不是业务ACK，无临床送达或法律签署效力。' : editorKind === 'amendment' ? '新草稿重新复核和模拟签署；下游仅待替换，无发送或ACK。' : editorKind === 'output' ? '每次访问均鉴权审计；同一产物重复预览下载保持原字节，用户自报不等于硬件确认。' : editorKind === 'review' ? '仅合成演练，无临床、CA或法律效力；无发送。' : renderEditor ? '人工输入，保存受病例归属和QC门禁约束；无复核、签署或发送。' : '转交后须由受让人领取。不包含报告编辑、签署或发布；管理员不自动获得资格。'} />
    <label htmlFor="diagnosis-state">诊断队列状态</label><Select id="diagnosis-state" virtual={false} value={filter.state} disabled={pending} options={['ALL', 'UNASSIGNED', 'ASSIGNED', 'ACTIVE'].map(value => ({ value, label: value }))} onChange={v => change(() => { clean(); setSelected(''); setFilter({ page: 1, state: v }); })} />
    <Button disabled={pending} onClick={() => change(() => { clean(); setRevision(v => v + 1); void reader.run(filter); })}>刷新诊断队列与资格</Button>
    <ReadPanel state={state}>{data => <Table rowKey="caseId" dataSource={data.items} scroll={{ x: 1100 }} pagination={{ current: filter.page, pageSize: 10, total: data.total, showSizeChanger: false, disabled: pending, onChange: page => change(() => { clean(); setSelected(''); setFilter({ ...filter, page }); }) }} columns={[
      { title: '病例 / 患者', key: 'identity', render: (_, i) => <span>{i.number}<br />{i.caseId}<br />患者 {i.patientId}</span> }, { title: '状态', dataIndex: 'state' }, { title: '版本', dataIndex: 'version' }, { title: '当前人员ID', dataIndex: 'ownerId' }, { title: '材料就绪', key: 'ready', render: (_, i) => i.ready ? '合成QC门禁通过' : '未就绪 / 隔离，禁止操作' }, { title: '操作', key: 'select', render: (_, i) => <Button disabled={pending} onClick={() => { if (selected !== i.caseId) change(() => { clean(); setSelected(i.caseId); }); }}>{editorKind === 'consultation' ? '查看院内会诊 ' : editorKind === 'delivery' ? '查看本地投递 ' : editorKind === 'amendment' ? '查看报告版本链 ' : editorKind === 'output' ? '查看固定产物 ' : editorKind === 'review' ? '复核合成报告 ' : renderEditor ? '编辑报告草稿 ' : '处理诊断分配 '}{i.caseId}</Button> },
    ]} />}</ReadPanel>
    {selected && <div key={selected + ':' + revision}>{renderEditor ? renderEditor(selected, { ...callbacks, onClean: clean, onDirty: () => { setDirty(true); callbacks.onDirty(); }, onPending: v => { setPending(v); callbacks.onPending(v); }, onSaved: () => { clean(); setRevision(v => v + 1); void reader.run(filter); } }) : <Editor id={selected} {...callbacks} onDirty={() => { setDirty(true); callbacks.onDirty(); }} onPending={v => { setPending(v); callbacks.onPending(v); }} onSaved={() => { clean(); setRevision(v => v + 1); void reader.run(filter); }} />}</div>}
    <Modal open={confirming} title={editorKind === 'consultation' ? '放弃未保存的会诊输入？' : editorKind === 'amendment' ? '放弃未保存的新版本输入？' : editorKind === 'output' ? '放弃未保存的产物操作输入？' : editorKind === 'review' ? '放弃未保存的复核输入？' : renderEditor ? '放弃未保存的报告草稿输入？' : '放弃未保存的诊断分配输入？'} okText="放弃并切换" cancelText="继续编辑" onCancel={() => { next.current = undefined; setConfirming(false); }} onOk={() => { const action = next.current; next.current = undefined; setConfirming(false); action?.(); }}><p>切换后重新核对病例、人员和版本。</p></Modal>
  </>;
}
function Editor({ id, onSaved, ...callbacks }: DiagnosisCallbacks & { id: string; onSaved: () => void }) {
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadDetail(id, signal) }; } catch (e) { return unavailable(e, callbacks.onExpired); } }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(id); return reader.stop; }, [reader, id]);
  return <ReadPanel state={state}>{detail => <>
    <section className="identity-strip" aria-label="诊断分配身份"><strong>{detail.item.number}</strong><span>病例 {id}</span><span>患者 {detail.item.patientId}</span><span>申请 {detail.item.requestId}</span><span>版本 {detail.item.version}</span><span>{detail.item.state}</span><span>当前人员 {detail.item.ownerId ?? '未分配'}</span></section>
    {!detail.item.ready && <Alert type="warning" title="材料或身份门禁未就绪，分配、领取和转交均被阻断" />}
    <Card title="明确分配操作"><AssignmentForm detail={detail} {...callbacks} onSaved={onSaved} /></Card>
    <Card title="追加分配历史（最近100项）"><Table rowKey="id" dataSource={detail.events} pagination={false} scroll={{ x: 1100 }} columns={[{ title: '版本', dataIndex: 'version' }, { title: '动作', dataIndex: 'action' }, { title: '前持有人', dataIndex: 'previousOwnerId' }, { title: '后持有人', dataIndex: 'nextOwnerId' }, { title: '操作者', dataIndex: 'actorId' }, { title: '原因', dataIndex: 'reason' }, { title: '时间 UTC', dataIndex: 'occurredAt' }]} /></Card>
  </>}</ReadPanel>;
}
interface Values { confirmedId: string; target: string; reason: string }
function AssignmentForm({ detail, onSaved, onDirty, onPending, onExpired }: Pick<DiagnosisCallbacks, 'onDirty' | 'onPending' | 'onExpired'> & { detail: Detail; onSaved: () => void }) {
  const [action, setAction] = useState('claim'), [next, setNext] = useState(''), [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState('');
  const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined); const [intent] = useState(() => new CommandIntent<Command>()); const [form] = Form.useForm<Values>();
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const i = detail.item, mine = detail.actorId === i.ownerId;
  const allowed = i.ready && (action === 'assign' ? detail.canAssign && i.state === 'UNASSIGNED' : action === 'claim' ? detail.canDiagnose && (i.state === 'UNASSIGNED' || i.state === 'ASSIGNED' && mine) : detail.canDiagnose && i.state === 'ACTIVE' && mine);
  async function execute(c: Command) {
    if (active.current) return; active.current = true; original.current = c; setBusy(true); onPending(true); onDirty(); setMessage('');
    try { if (await intent.run(c, send) && alive.current) { onPending(false); onSaved(); } }
    catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; }
      const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code));
      if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '分配结果待确认，请保留原病例、人员、版本和请求键重试。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  return <>
    {message && <Alert role="status" title={message} type="info" />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>重试原诊断分配请求</Button>}
    <Form<Values> form={form} layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={v => { if (allowed && !busy && !uncertain) void execute({ caseId: i.caseId, action, body: { expectedVersion: i.version, confirmedCaseId: v.confirmedId, targetUserId: action === 'claim' ? null : v.target, reason: v.reason } }); }}>
      <Form.Item label="诊断分配操作" htmlFor="diagnosis-action"><Select id="diagnosis-action" virtual={false} value={action} options={[{ value: 'claim', label: '本人领取' }, { value: 'assign', label: '分配给合格人员' }, { value: 'transfer', label: '转交并等待对方领取' }]} onChange={v => { if (form.isFieldsTouched()) setNext(v); else { form.resetFields(); setAction(v); onDirty(); } }} /></Form.Item>
      {action !== 'claim' && <Form.Item name="target" label="同范围合格人员" preserve={false} rules={[{ required: true }]}><Select virtual={false} options={detail.candidates.filter(c => action !== 'transfer' || c.id !== detail.actorId).map(c => ({ value: c.id, label: c.name + ' / ' + c.id }))} /></Form.Item>}
      <Form.Item name="confirmedId" label="核对诊断病例 UUID" rules={[{ required: true, whitespace: true }, { validator: (_, v: unknown) => v === i.caseId ? Promise.resolve() : Promise.reject(new Error('必须核对当前病例 UUID')) }]}><Input maxLength={36} /></Form.Item>
      <Form.Item name="reason" label="分配领取转交原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} /></Form.Item>
      <Button type="primary" htmlType="submit" disabled={!allowed || busy || uncertain}>提交诊断分配操作</Button>
    </Form>
    <Modal open={!!next} title="切换诊断操作并清除输入？" okText="清除并切换" cancelText="继续编辑" onCancel={() => setNext('')} onOk={() => { form.resetFields(); setAction(next); setNext(''); onDirty(); }}><p>目标人员不在领取操作中隐式提交。</p></Modal>
  </>;
}
