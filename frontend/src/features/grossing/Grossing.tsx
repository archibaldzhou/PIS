import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { base64, grossCommand, loadGross, photoBytes, syntheticBytes, type GrossCommand, type GrossView, type Photo } from './api';

export function Grossing({ requestId, onDirty, onClean, onPending, onExpired }: {
  requestId: string; onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void;
}) {
  const [pending, setPending] = useState(false); const [dirty, setDirty] = useState(false); const [confirmRefresh, setConfirmRefresh] = useState(false);
  const [notice, setNotice] = useState('');
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadGross(id, signal) }; }
    catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(requestId); return reader.stop; }, [requestId, reader]);
  const clean = () => { setDirty(false); onClean(); };
  const refresh = () => { clean(); setConfirmRefresh(false); void reader.run(requestId); };
  return <>
    <Alert type="warning" title="合成取材记录 · 不代表处理/包埋完成" description="图像仅接受随程序发布的合成PNG，不接收真实患者照片；取材盒为待处理实体，尚未制成蜡块。" />
    {notice && <Alert role="status" title={notice} type="info" />}
    <Button disabled={pending} onClick={() => { if (dirty) setConfirmRefresh(true); else refresh(); }}>刷新取材记录</Button>
    <ReadPanel state={state}>{data => <>
      <section className="identity-strip" aria-label="取材病例身份"><strong>{data.caseNumber}</strong><span>{data.request.requestNumber}</span><span>{data.request.patientLabel}</span><span>患者 {data.request.patientId}</span><span>就诊 {data.request.encounterNumber}</span>
        <Tag>{data.record?.state ?? '未建立取材记录'}</Tag><span>版本 {data.record?.version ?? '—'}</span></section>
      <GrossEditor key={data.record ? `${data.record.id}:${data.record.version}` : data.caseId} data={data}
        onDirty={() => { setDirty(true); onDirty(); }} onClean={clean} onPending={value => { setPending(value); onPending(value); }} onExpired={onExpired}
        onSaved={() => { clean(); setNotice('服务器已确认取材记录操作；后续处理与包埋尚未执行。'); void reader.run(requestId); }} />
      {data.record && <>
        <Card title="取材盒谱系（待处理，不是已制备蜡块）"><Table rowKey="id" dataSource={data.record.cassettes} pagination={false} scroll={{ x: 750 }} columns={[
          { title: '取材盒', dataIndex: 'number' }, { title: '部位', dataIndex: 'site' }, { title: '块数', dataIndex: 'pieces' }, { title: '状态', dataIndex: 'state' },
          { title: '来源容器', key: 'sources', render: (_, box) => box.containerIds.join(' / ') },
        ]} /></Card>
        <Card title="大体图像（固定合成样本，非真实照片）">{data.record.photos.length === 0 ? <p>尚无附件</p> : data.record.photos.map(photo => <PhotoPanel key={photo.id + String(photo.withdrawnAt) + data.record?.state} photo={photo} cancelled={data.record?.state === 'CANCELLED'} onExpired={onExpired} />)}</Card>
        <Card title="描述修订（最近50条，旧版本保留）"><Table rowKey="version" dataSource={data.record.revisions} pagination={false} scroll={{ x: 650 }} columns={[
          { title: '版本', dataIndex: 'version' }, { title: '描述', dataIndex: 'description' }, { title: '原因', dataIndex: 'reason' }, { title: '操作者', dataIndex: 'actorId' },
        ]} /></Card>
        <Card title="操作轨迹（最近100条）"><Table rowKey="id" dataSource={data.record.events} pagination={false} scroll={{ x: 650 }} columns={[
          { title: '版本', dataIndex: 'version' }, { title: '操作', dataIndex: 'action' }, { title: '目标', dataIndex: 'targetId' }, { title: '原因', dataIndex: 'reason' }, { title: '时间 UTC', dataIndex: 'occurredAt' },
        ]} /></Card>
      </>}
    </>}</ReadPanel>
    <Modal open={confirmRefresh} title="放弃本地输入并刷新？" okText="放弃并刷新" cancelText="继续编辑" onCancel={() => setConfirmRefresh(false)} onOk={refresh}><p>未保存输入将清除，服务器历史保留。</p></Modal>
  </>;
}
const names: Record<string, string> = { create: '建立取材记录', description: '保存大体描述', correction: '更正已完成描述', cassettes: '新增取材盒', photos: '添加合成图像', 'cancel-box': '取消取材盒', 'withdraw-photo': '撤回图像', complete: '完成取材记录', cancel: '取消取材记录' };
function GrossEditor({ data, onDirty, onClean, onPending, onExpired, onSaved }: {
  data: GrossView; onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void; onSaved: () => void;
}) {
  const modes = !data.record ? ['create'] : data.record.state === 'DRAFT' ? ['description', 'cassettes', 'photos', 'cancel-box', 'withdraw-photo', 'complete', 'cancel'] : data.record.state === 'COMPLETED' ? ['correction'] : [];
  const [mode, setMode] = useState(modes[0] ?? ''); const [next, setNext] = useState(''); const [dirty, setDirty] = useState(false); const [pending, setPending] = useState(false);
  const change = (value: string) => { setMode(value); setNext(''); setDirty(false); onClean(); };
  return <Card title="取材记录操作">
    <p>当前描述：{data.record?.description || '尚未填写'}</p>
    {modes.length === 0 ? <Alert type="warning" title="记录已取消，历史保留，不能继续编辑" /> : <>
      <label htmlFor="gross-operation">取材操作</label><Select id="gross-operation" virtual={false} value={mode} disabled={pending} style={{ width: '100%' }} options={modes.map(value => ({ value, label: names[value] }))} onChange={value => { if (dirty) setNext(value); else change(value); }} />
      <OperationForm key={mode} mode={mode} data={data} onDirty={() => { setDirty(true); onDirty(); }} onPending={v => { setPending(v); onPending(v); }} onExpired={onExpired} onSaved={onSaved} />
    </>}
    <Modal open={!!next} title="放弃当前操作的本地输入？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNext('')} onOk={() => change(next)}><p>不同操作的输入不互相沿用。</p></Modal>
  </Card>;
}
interface Values { description: string; reason: string; site: string; pieces: number; containerIds: string[]; containerId: string; caption: string; base64: string; targetId: string }
function OperationForm({ mode, data, onDirty, onPending, onExpired, onSaved }: { mode: string; data: GrossView; onDirty: () => void; onPending: (v: boolean) => void; onExpired: () => void; onSaved: () => void }) {
  const [form] = Form.useForm<Values>(); const [busy, setBusy] = useState(false); const [uncertain, setUncertain] = useState(false); const [message, setMessage] = useState('');
  const active = useRef(false); const alive = useRef(true); const imageAbort = useRef<AbortController | undefined>(undefined);
  const [intent] = useState(() => new CommandIntent<GrossCommand>()); const original = useRef<GrossCommand | undefined>(undefined);
  useEffect(() => { alive.current = true; return () => { alive.current = false; imageAbort.current?.abort(); }; }, []);
  async function execute(command: GrossCommand) {
    if (active.current) return; active.current = true; original.current = command; setBusy(true); onDirty(); onPending(true); setMessage('');
    try { const id = await intent.run(command, grossCommand); if (id && alive.current) { onPending(false); onSaved(); } }
    catch (e) {
      if (!alive.current) return;
      if (e instanceof ApiError && e.status === 401) { onExpired(); return; }
      const definite = e instanceof ApiError && ([400, 403, 404, 413].includes(e.status) || (e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)));
      if (definite) { intent.rejected(); onPending(false); }
      setUncertain(!definite); setMessage(definite ? e.message : '取材操作结果待确认；请保留原输入与请求键重试。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  function submit(values: Values) {
    if (busy || uncertain) return;
    let path = '/api/grossing/records/' + data.record?.id + '/' + mode;
    const expectedVersion = data.record?.version ?? 0; let body: GrossCommand['body'];
    if (mode === 'create') { path = '/api/grossing/requests/' + data.request.id; body = { requestVersion: data.request.version, description: values.description ?? '' }; }
    else if (['description', 'correction'].includes(mode)) body = { expectedVersion, description: values.description ?? '', reason: values.reason };
    else if (mode === 'cassettes') body = { expectedVersion, containerIds: values.containerIds, site: values.site, pieces: values.pieces };
    else if (mode === 'photos') body = { expectedVersion, containerId: values.containerId, caption: values.caption ?? '', base64: values.base64 };
    else { body = { expectedVersion, reason: values.reason }; if (mode === 'cancel-box') path = '/api/grossing/records/' + data.record?.id + '/cassettes/' + values.targetId + '/cancel'; if (mode === 'withdraw-photo') path = '/api/grossing/records/' + data.record?.id + '/photos/' + values.targetId + '/withdraw'; }
    void execute({ path, body });
  }
  async function image(file?: File) {
    if (busy || uncertain) return; imageAbort.current?.abort(); const abort = new AbortController(); imageAbort.current = abort; setBusy(true); setMessage('');
    try {
      const bytes = file ? (file.size > 16384 ? (() => { throw new Error('图像超过16KiB'); })() : await syntheticBytes(await file.arrayBuffer())) : await photoBytes('/api/grossing/requests/' + data.request.id + '/sample', abort.signal);
      if (alive.current && imageAbort.current === abort) { form.setFieldValue('base64', base64(bytes)); onDirty(); setMessage('已核验随包合成PNG样本，尚未保存附件。'); }
    } catch (e) { if (alive.current && imageAbort.current === abort) { if (e instanceof ApiError && e.status === 401) onExpired(); else { form.setFieldValue('base64', ''); setMessage('只接受随程序发布的合成PNG，不发送或保存其他照片。'); } } }
    finally { if (alive.current && imageAbort.current === abort) setBusy(false); }
  }
  const options = data.request.containers.map(c => ({ value: c.id, label: `${c.id} / ${c.site}` }));
  return <>
    {message && <Alert role="status" title={message} type="info" />}
    {uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>重试原取材请求</Button>}
    <Form<Values> form={form} layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={submit} initialValues={{ description: data.record?.description ?? '', pieces: 1, caption: '' }}>
      {['create', 'description', 'correction'].includes(mode) && <Form.Item label="大体描述" name="description" rules={[{ max: 8000 }]}><Input.TextArea rows={5} /></Form.Item>}
      {mode === 'cassettes' && <><Form.Item label="来源已接收容器" name="containerIds" rules={[{ required: true }]}><Select mode="multiple" options={options} /></Form.Item><Form.Item label="取材部位" name="site" rules={[{ required: true, whitespace: true }, { max: 255 }]}><Input /></Form.Item><Form.Item label="材料块数" name="pieces" rules={[{ required: true }]}><InputNumber min={1} max={99} precision={0} /></Form.Item></>}
      {mode === 'photos' && <><Form.Item label="图像关联容器" name="containerId" rules={[{ required: true }]}><Select options={options} /></Form.Item><Form.Item label="合成图像备注" name="caption" rules={[{ max: 255 }]}><Input /></Form.Item>
        <Form.Item name="base64" hidden rules={[{ required: true, message: '先选择受控合成PNG样本' }]}><Input /></Form.Item><Space wrap><Button disabled={busy || uncertain} onClick={() => void image()}>选择随包合成PNG样本</Button><label>导入同一受控PNG<input type="file" accept="image/png" disabled={busy || uncertain} onChange={e => { const file = e.target.files?.[0]; if (file) void image(file); e.target.value = ''; }} /></label></Space></>}
      {mode === 'cancel-box' && <Form.Item label="取消的取材盒" name="targetId" rules={[{ required: true }]}><Select options={data.record?.cassettes.filter(c => c.state === 'PLANNED').map(c => ({ value: c.id, label: c.number }))} /></Form.Item>}
      {mode === 'withdraw-photo' && <Form.Item label="撤回的图像" name="targetId" rules={[{ required: true }]}><Select options={data.record?.photos.filter(p => !p.withdrawnAt).map(p => ({ value: p.id, label: p.caption || p.id }))} /></Form.Item>}
      {!['create', 'cassettes', 'photos'].includes(mode) && <Form.Item label="操作原因 / 更正说明" name="reason" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={2} /></Form.Item>}
      <Button type="primary" htmlType="submit" disabled={busy || uncertain}>{names[mode]}</Button>
    </Form>
  </>;
}
function PhotoPanel({ photo, cancelled, onExpired }: { photo: Photo; cancelled: boolean; onExpired: () => void }) {
  const [open, setOpen] = useState(false);
  return <section className="container-fields"><p>图像 {photo.id} · 来源 {photo.containerId}<br />{photo.caption} · {photo.width}×{photo.height} · SHA256 {photo.sha256}</p>
    {photo.withdrawnAt || cancelled ? <Tag>已撤回或记录已取消，不能下载</Tag> : <><Button onClick={() => setOpen(value => !value)}>{open ? '关闭合成图像' : '查看合成图像'}</Button>{open && <PhotoContent photo={photo} onExpired={onExpired} />}</>}
  </section>;
}
function PhotoContent({ photo, onExpired }: { photo: Photo; onExpired: () => void }) {
  const [url, setUrl] = useState(''); const [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController(); let objectUrl = '';
    void photoBytes('/api/grossing/photos/' + photo.id + '/content', abort.signal, photo.sha256).then(bytes => {
      if (abort.signal.aborted) return; objectUrl = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: 'image/png' })); setUrl(objectUrl);
    }).catch(e => { if (!abort.signal.aborted) { if (e instanceof ApiError && e.status === 401) onExpired(); else setError('图像读取/校验失败或已撤回，请刷新核对'); } });
    return () => { abort.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [photo.id, photo.sha256, onExpired]);
  return error ? <Alert role="alert" type="error" title={error} /> : url ? <img src={url} width={256} height={160} alt="合成取材示意图，不是真实照片，无测量校准" style={{ maxWidth: '100%', height: 'auto' }} /> : <p role="status">正在核验合成图像…</p>;
}
