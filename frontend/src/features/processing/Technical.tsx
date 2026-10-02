import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table, Tag } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { actionNames, availableActions, loadEvents, loadTechnical, sendCommand, type Command, type Task, type TechnicalView } from './api';
type Callbacks = { onDirty: () => void; onClean: () => void; onPending: (value: boolean) => void; onExpired: () => void };
export function Technical({ requestId, onDirty, onClean, onPending, onExpired }: Callbacks & { requestId: string }) {
  const [selected, setSelected] = useState(''); const [dirty, setDirty] = useState(false); const [pending, setPending] = useState(false);
  const [next, setNext] = useState<string | null>(null); const [notice, setNotice] = useState('');
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadTechnical(id, signal) }; }
    catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(requestId); return reader.stop; }, [reader, requestId]);
  const clean = () => { setDirty(false); onClean(); };
  const change = (value: string) => { clean(); setNext(null); setSelected(value); if (value === 'refresh') { setSelected(''); void reader.run(requestId); } };
  const choose = (value: string) => { if (pending) return; if (dirty) setNext(value); else change(value); };
  return <>
    <Alert type="warning" title="技术任务合成演练 · 不代表真实工艺完成" description="不连接设备，不创建蜡块或玻片；前置任务仅用于本开发流程核对，路线/参数/岗位制度尚未获医院批准。" />
    {notice && <Alert role="status" type="info" title={notice} />}
    <Button disabled={pending} onClick={() => choose('refresh')}>刷新技术任务</Button><Button disabled={pending} onClick={() => choose('')}>新建技术任务</Button>
    <ReadPanel state={state}>{view => {
      const task = view.tasks.find(t => t.id === selected);
      return <>
        <section className="identity-strip" aria-label="技术病例身份"><strong>{view.source.caseNumber}</strong><span>{view.source.patientLabel}</span><span>患者 {view.source.patientId}</span><span>就诊 {view.source.encounterNumber}</span><span>当前用户 {view.actorId}</span></section>
        <Card title="处理 / 包埋 / 切片技术队列"><Table rowKey="id" dataSource={view.tasks} pagination={false} scroll={{ x: 950 }} columns={[
          { title: '任务 UUID', dataIndex: 'id' }, { title: '类别', dataIndex: 'kind' }, { title: '状态', dataIndex: 'state' }, { title: '来源取材盒', dataIndex: 'cassetteId' }, { title: '持有人', dataIndex: 'ownerId' },
          { title: '前置任务', dataIndex: 'predecessorId' }, { title: '返工原任务', dataIndex: 'reworkOf' }, { title: '操作', key: 'select', render: (_, t) => <Button disabled={pending} onClick={() => choose(t.id)}>查看技术任务 {t.id}</Button> },
        ]} /></Card>
        {task && <section className="identity-strip" aria-label="技术任务身份" data-task-id={task.id}><strong>{task.id}</strong><span>来源盒 {task.cassetteId}</span><Tag>{task.state}</Tag><span>版本 {task.version}</span><span>持有人 {task.ownerId ?? '尚未领取'}</span></section>}
        {(!selected || task) && <TaskForm key={task ? `form:${task.id}:${task.version}` : 'create'} view={view} task={task} onDirty={() => { setDirty(true); onDirty(); }} onPending={v => { setPending(v); onPending(v); }} onExpired={onExpired}
          onSaved={id => { clean(); setSelected(id); setNotice('服务器已确认技术任务操作；不表示真实工艺或实物交接验收。'); void reader.run(requestId); }} />}
        {task && <TaskHistory key={`history:${task.id}:${task.version}`} id={task.id} onExpired={onExpired} />}
      </>;
    }}</ReadPanel>
    <Modal open={next !== null} title="放弃技术任务的本地输入？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNext(null)} onOk={() => { if (next !== null) change(next); }}><p>未确认的输入不会保留到另一任务。</p></Modal>
  </>;
}
interface Values { cassetteId: string; kind: string; predecessorId?: string; reason: string; confirmedCassetteId: string; action: string }
function TaskForm({ view, task, onDirty, onPending, onExpired, onSaved }: Pick<Callbacks, 'onDirty' | 'onPending' | 'onExpired'> & { view: TechnicalView; task?: Task; onSaved: (id: string) => void }) {
  const [form] = Form.useForm<Values>(); const cassette = Form.useWatch('cassetteId', form);
  const [busy, setBusy] = useState(false); const [uncertain, setUncertain] = useState(false); const [message, setMessage] = useState('');
  const active = useRef(false), alive = useRef(true); const original = useRef<Command | undefined>(undefined); const [intent] = useState(() => new CommandIntent<Command>());
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const actions = task ? availableActions(task, view.actorId) : [];
  async function execute(command: Command) {
    if (active.current) return; active.current = true; original.current = command; setBusy(true); onDirty(); onPending(true); setMessage('');
    try { const id = await intent.run(command, sendCommand); if (id && alive.current) { onPending(false); onSaved(id); } }
    catch (e) {
      if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; }
      const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || (e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)));
      if (definite) { intent.rejected(); onPending(false); }
      setUncertain(!definite); setMessage(definite ? e.message : '技术任务结果待确认；保留原任务、输入和请求键重试。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  function submit(v: Values) {
    if (busy || uncertain) return;
    const command: Command = task ? { path: '/api/technical/tasks/' + task.id + '/' + v.action, body: { expectedVersion: task.version, confirmedCassetteId: v.confirmedCassetteId, reason: v.reason } }
      : { path: '/api/technical/requests/' + view.source.requestId, body: { requestVersion: view.source.requestVersion, cassetteId: v.cassetteId, kind: v.kind, predecessorId: v.predecessorId ?? null, reason: v.reason } };
    void execute(command);
  }
  return <Card title={task ? '核对与操作' : '建立技术任务'}>
    {message && <Alert role="status" title={message} type="info" />}
    {uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>重试原技术请求</Button>}
    {task && actions.length === 0 ? <Alert title="任务由其他用户持有，需其先发起交接" type="info" /> : <Form<Values> form={form} layout="vertical" disabled={busy || uncertain} initialValues={{ kind: 'PROCESSING', action: actions[0] }} onValuesChange={onDirty} onFinish={submit}>
      {task ? <><Form.Item name="action" label="技术任务操作" rules={[{ required: true }]}><Select virtual={false} options={actions.map(value => ({ value, label: actionNames[value] }))} /></Form.Item>
        <Form.Item name="confirmedCassetteId" label="核对来源取材盒 UUID" rules={[{ required: true, whitespace: true }]}><Input maxLength={36} /></Form.Item></> : <>
        <Form.Item name="cassetteId" label="来源已释放取材盒" rules={[{ required: true }]}><Select virtual={false} onChange={() => form.setFieldValue('predecessorId', undefined)} options={view.source.cassettes.map(c => ({ value: c.id, label: c.number + ' / ' + c.site }))} /></Form.Item>
        <Form.Item name="kind" label="技术任务类别" rules={[{ required: true }]}><Select virtual={false} options={[{ value: 'PROCESSING', label: '处理任务' }, { value: 'EMBEDDING', label: '包埋任务' }, { value: 'SECTIONING', label: '切片任务' }]} /></Form.Item>
        <Form.Item name="predecessorId" label="同盒前置合成任务（可选，路线须说明）"><Select virtual={false} allowClear options={view.tasks.filter(t => t.cassetteId === cassette && t.state === 'SIMULATED_DONE').map(t => ({ value: t.id, label: t.kind + ' / ' + t.id }))} /></Form.Item>
      </>}
      <Form.Item name="reason" label="路线 / 操作 / 交接说明" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} /></Form.Item>
      <Button type="primary" htmlType="submit" disabled={busy || uncertain}>{task ? '确认技术任务操作' : '保存技术任务'}</Button>
    </Form>}
  </Card>;
}
function TaskHistory({ id, onExpired }: { id: string; onExpired: () => void }) {
  const [reader] = useState(() => new ReadController(async (taskId: string, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadEvents(taskId, signal) }; }
    catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(id); return reader.stop; }, [reader, id]);
  return <section aria-label="技术任务交接历史" data-task-id={id}><Card title="追加操作历史（最近100条）"><ReadPanel state={state}>{events => <Table rowKey="id" dataSource={events} pagination={false} scroll={{ x: 950 }} columns={[
    { title: '版本', dataIndex: 'version' }, { title: '操作', dataIndex: 'action' }, { title: '操作者', dataIndex: 'actorId' }, { title: '原持有人', dataIndex: 'previousOwnerId' }, { title: '新持有人', dataIndex: 'nextOwnerId' }, { title: '关联任务', dataIndex: 'relatedTaskId' }, { title: '说明', dataIndex: 'reason' }, { title: '时间 UTC', dataIndex: 'occurredAt' },
  ]} />}</ReadPanel></Card></section>;
}
