import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table, Tag } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { Labels } from '../labels';
import { loadMaterial, loadMaterials, lookupMaterial, modes, names, sendCommand, type Command, type Material, type MaterialsView } from './api';
type Callbacks = { onDirty: () => void; onClean: () => void; onPending: (value: boolean) => void; onExpired: () => void };
export function Materials({ requestId, onDirty, onClean, onPending, onExpired }: Callbacks & { requestId: string }) {
  const [selected, setSelected] = useState(''), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [next, setNext] = useState<string | null>(null), [message, setMessage] = useState(''), [scan, setScan] = useState('');
  const lookup = useRef<AbortController | undefined>(undefined);
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadMaterials(id, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(requestId); return () => { reader.stop(); lookup.current?.abort(); }; }, [reader, requestId]);
  const clean = () => { setDirty(false); onClean(); };
  const change = (id: string) => { clean(); setNext(null); setScan(''); setSelected(id); if (id === 'refresh') { setSelected(''); void reader.run(requestId); } };
  const choose = (id: string) => { lookup.current?.abort(); if (pending) return; if (dirty) setNext(id); else change(id); };
  async function find() {
    lookup.current?.abort(); const abort = new AbortController(); lookup.current = abort;
    try { const found = await lookupMaterial(scan, abort.signal); if (abort.signal.aborted) return; if (found.entity.requestId !== requestId) setMessage('该条码属于另一授权病例，请从对应申请详情进入'); else choose(found.entity.id); }
    catch (e) { if (!abort.signal.aborted) { if (e instanceof ApiError && e.status === 401) onExpired(); else setMessage(e instanceof ApiError ? e.message : '条码反查失败，未切换材料'); } }
  }
  return <>
    <Alert type="warning" title="合成材料登记 · 非临床制作证明" description="常规材料核对技术任务；细胞学直制明确不要求蜡块。重切/加深创建新玻片，重打标签保留原身份。" />
    {message && <Alert role="status" type="info" title={message} />}
    <Button disabled={pending} onClick={() => choose('refresh')}>刷新材料谱系</Button><Button disabled={pending} onClick={() => choose('')}>新材料登记</Button>
    <label htmlFor="material-barcode">精确材料条码反查</label><Input id="material-barcode" value={scan} maxLength={64} disabled={pending} onChange={e => setScan(e.target.value)} /><Button disabled={pending || !scan} onClick={() => void find()}>反查材料谱系</Button>
    <ReadPanel state={state}>{view => {
      const entity = view.entities.find(e => e.id === selected);
      return <>
        <section className="identity-strip" aria-label="材料病例身份"><strong>{view.caseNumber}</strong><span>{view.request.patientLabel}</span><span>患者 {view.request.patientId}</span><span>就诊 {view.request.encounterNumber}</span></section>
        <Card title="蜡块 / 玻片及来源谱系"><Table rowKey="id" dataSource={view.entities} pagination={false} scroll={{ x: 1100 }} columns={[
          { title: '显示号', dataIndex: 'number' }, { title: '类型 / 路径', key: 'route', render: (_, e) => `${e.kind} / ${e.route} / ${e.operation}` }, { title: '状态', dataIndex: 'state' }, { title: '来源盒 / 容器', key: 'source', render: (_, e) => e.cassetteId ?? e.containerId }, { title: '源蜡块', dataIndex: 'blockId' }, { title: '历史源玻片', dataIndex: 'sourceSlideId' },
          { title: '操作', key: 'select', render: (_, e) => <Button disabled={pending} onClick={() => choose(e.id)}>查看材料 {e.id}</Button> },
        ]} /></Card>
        {entity && <section className="identity-strip" aria-label="材料实体身份" data-material-id={entity.id}><strong>{entity.number}</strong><span>UUID {entity.id}</span><Tag>{entity.state}</Tag><span>版本 {entity.version}</span><span>条码 {entity.barcode}</span><span>技术任务 {entity.technicalTaskId ?? '直制路径无盒级任务'}</span></section>}
        {(!selected || entity) && <MaterialEditor key={entity ? `editor:${entity.id}:${entity.version}` : 'create'} view={view} entity={entity} onDirty={() => { lookup.current?.abort(); setDirty(true); onDirty(); }} onClean={clean} onPending={value => { setPending(value); onPending(value); }} onExpired={onExpired} onSaved={id => { clean(); setSelected(id); setMessage('服务器已确认材料身份操作；不表示实物制作或打印成功。'); void reader.run(requestId); }} />}
        {entity && <History key={`history:${entity.id}:${entity.version}`} id={entity.id} onExpired={onExpired} />}
      </>;
    }}</ReadPanel>
    <Modal open={next !== null} title="放弃材料操作的本地输入？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNext(null)} onOk={() => { if (next !== null) change(next); }}><p>切换清除本地核对信息，服务器历史保留。</p></Modal>
  </>;
}
function MaterialEditor(props: Callbacks & { view: MaterialsView; entity?: Material; onSaved: (id: string) => void }) {
  const options = modes(props.entity), [mode, setMode] = useState(options[0]), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [next, setNext] = useState('');
  const change = (value: string) => { setMode(value); setNext(''); setDirty(false); props.onClean(); };
  const callbacks = { onDirty: () => { setDirty(true); props.onDirty(); }, onClean: () => { setDirty(false); props.onClean(); }, onPending: (value: boolean) => { setPending(value); props.onPending(value); }, onExpired: props.onExpired };
  return <Card title="材料身份操作">
    <label htmlFor="material-mode">材料操作</label><Select id="material-mode" virtual={false} value={mode} disabled={pending} style={{ width: '100%' }} options={options.map(value => ({ value, label: names[value] }))} onChange={value => { if (dirty) setNext(value); else change(value); }} />
    {mode === 'labels' && props.entity ? <Labels key={'labels:' + props.entity.id} materialIds={[props.entity.id]} {...callbacks} /> : <MaterialForm key={mode} mode={mode} view={props.view} entity={props.entity} {...callbacks} onSaved={props.onSaved} />}
    <Modal open={!!next} title="放弃当前材料操作输入？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNext('')} onOk={() => change(next)}><p>不同路径的字段不互相沿用。</p></Modal>
  </Card>;
}
interface Values { taskId: string; confirmedId: string; reason: string }
function MaterialForm({ mode, view, entity, onDirty, onPending, onExpired, onSaved }: Pick<Callbacks, 'onDirty' | 'onPending' | 'onExpired'> & { mode: string; view: MaterialsView; entity?: Material; onSaved: (id: string) => void }) {
  const [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState('');
  const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined); const [intent] = useState(() => new CommandIntent<Command>());
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  async function execute(command: Command) {
    if (active.current) return; active.current = true; original.current = command; setBusy(true); onDirty(); onPending(true); setMessage('');
    try { const id = await intent.run(command, sendCommand); if (id && alive.current) { onPending(false); onSaved(id); } }
    catch (e) {
      if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; }
      const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || (e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)));
      if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '材料操作结果待确认；保留原身份、输入和请求键重试。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  function submit(v: Values) {
    if (busy || uncertain) return; const task = view.tasks.find(t => t.id === v.taskId); let command: Command;
    if (mode === 'direct') command = { path: '/api/materials/requests/' + view.request.id + '/direct-slides', body: { requestVersion: view.request.version, confirmedContainerId: v.confirmedId, reason: v.reason } };
    else if (mode === 'void' && entity) command = { path: '/api/materials/' + entity.id + '/void', body: { expectedVersion: entity.version, confirmedMaterialId: v.confirmedId, reason: v.reason } };
    else {
      if (!task) { setMessage('请选择当前已完成合成演练的技术任务'); return; }
      if (mode === 'block') command = { path: '/api/materials/requests/' + view.request.id + '/blocks', body: { requestVersion: view.request.version, taskId: task.id, taskVersion: task.version, confirmedCassetteId: v.confirmedId, reason: v.reason } };
      else if (entity && mode === 'slide') command = { path: '/api/materials/' + entity.id + '/slides', body: { blockVersion: entity.version, taskId: task.id, taskVersion: task.version, confirmedBlockId: v.confirmedId, reason: v.reason } };
      else if (entity && ['recut', 'deeper'].includes(mode)) command = { path: '/api/materials/' + entity.id + '/' + mode, body: { sourceSlideVersion: entity.version, taskId: task.id, taskVersion: task.version, confirmedSourceSlideId: v.confirmedId, reason: v.reason } };
      else return;
    }
    void execute(command);
  }
  const tasks = view.tasks.filter(t => t.kind === (mode === 'block' ? 'EMBEDDING' : 'SECTIONING') && (!entity || t.cassetteId === entity.cassetteId));
  return <>
    {message && <Alert role="status" title={message} type="info" />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>重试原材料请求</Button>}
    <Form<Values> layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={submit}>
      {!['direct', 'void'].includes(mode) && <Form.Item name="taskId" label="前置合成技术任务" rules={[{ required: true }]}><Select virtual={false} options={tasks.map(t => ({ value: t.id, label: t.kind + ' / ' + t.id + ' / 盒 ' + t.cassetteId }))} /></Form.Item>}
      {mode === 'direct' && <p>直制路径：无需蜡块，不伪造盒级任务。已接收容器：{view.request.containers.map(c => `${c.id} / ${c.site}`).join('；')}</p>}
      <Form.Item name="confirmedId" label={mode === 'block' ? '核对来源盒 UUID' : mode === 'direct' ? '核对直制容器 UUID' : '核对当前材料 UUID'} rules={[{ required: true, whitespace: true }]}><Input maxLength={36} /></Form.Item>
      <Form.Item name="reason" label="材料登记 / 重切 / 作废原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} /></Form.Item>
      <Button type="primary" htmlType="submit" disabled={busy || uncertain}>{names[mode]}</Button>
    </Form>
  </>;
}
function History({ id, onExpired }: { id: string; onExpired: () => void }) {
  const [reader] = useState(() => new ReadController(async (materialId: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await loadMaterial(materialId, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; } }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(id); return reader.stop; }, [reader, id]);
  return <section aria-label="材料谱系历史" data-material-id={id}><Card title="材料追加事件（最近100条）"><ReadPanel state={state}>{d => <Table rowKey="id" dataSource={d.events} pagination={false} scroll={{ x: 900 }} columns={[{ title: '版本', dataIndex: 'version' }, { title: '动作', dataIndex: 'action' }, { title: '关联材料', dataIndex: 'relatedId' }, { title: '原因', dataIndex: 'reason' }, { title: '操作者', dataIndex: 'actorId' }, { title: '时间 UTC', dataIndex: 'occurredAt' }]} />}</ReadPanel></Card></section>;
}
