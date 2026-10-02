import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Input, Modal, Select, Space, Table, Tag } from 'antd';
import { ApiError } from '../../api';
import { CommandIntent } from '../../shared/command';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { barcodeBars } from './barcode';
import { checkLabel, labelCommand, loadTarget, loadJob, type LabelCommand, type LabelJob } from './api';

const states: Record<string, string> = { PREVIEW_READY: '可预览（未确认实物打印）', FAILED: '开发模拟失败', CANCELLED: '已取消' };
export function Labels({ containerIds, materialIds, onDirty, onClean, onPending, onExpired }: {
  containerIds?: string[]; materialIds?: string[]; onDirty: () => void; onClean: () => void; onPending: (value: boolean) => void; onExpired: () => void;
}) {
  const kind = materialIds ? 'material' : 'container'; const ids = materialIds ?? containerIds ?? [];
  const [cid, setCid] = useState(''); const [jobId, setJobId] = useState('');
  const [reason, setReason] = useState(''); const [scan, setScan] = useState(''); const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false); const [uncertain, setUncertain] = useState(false); const [preview, setPreview] = useState(false);
  const [nextContainer, setNextContainer] = useState(''); const [nextJob, setNextJob] = useState('');
  const active = useRef(false); const alive = useRef(true); const generation = useRef(0);
  const original = useRef<LabelCommand | undefined>(undefined); const [intent] = useState(() => new CommandIntent<LabelCommand>());
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadTarget(id, signal, kind) }; }
    catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; }
  }));
  const [detail] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadJob(id, signal) }; }
    catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); const selected = useSyncExternalStore(detail.subscribe, detail.getSnapshot);
  useEffect(() => { alive.current = true; return () => { alive.current = false; reader.stop(); detail.stop(); }; }, [reader, detail]);
  function chooseContainer(id: string) {
    generation.current++; reader.stop(); detail.stop(); setCid(id); setJobId(''); setPreview(false); setReason(''); setScan(''); setMessage(''); onClean(); void reader.run(id);
  }
  function chooseJob(id: string) { generation.current++; detail.stop(); setJobId(id); setPreview(false); setReason(''); setScan(''); setMessage(''); onClean(); void detail.run(id); }
  async function execute(command: LabelCommand) {
    if (active.current) return; active.current = true; original.current = command; setBusy(true); onDirty(); onPending(true); setMessage('');
    try {
      const id = await intent.run(command, labelCommand);
      if (id && alive.current) { onPending(false); onClean(); setReason(''); setScan(''); setUncertain(false); await reader.run(cid); chooseJob(id); setMessage('服务器已确认任务操作；尚未确认任何实物打印。'); }
    } catch (e) {
      if (!alive.current) return;
      if (e instanceof ApiError && e.status === 401) { onExpired(); return; }
      const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || (e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)));
      if (definite) { intent.rejected(); onPending(false); }
      setUncertain(!definite); setMessage(definite ? e.message : '标签操作结果待确认；请保留原任务与请求键重试。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  function act(action: string) {
    if (busy || uncertain || selected.status !== 'ready') return;
    if (!reason.trim() || reason.length > 2000) { setMessage('请填写1–2000字的操作原因'); return; }
    void execute({ path: '/api/labels/jobs/' + selected.data.job.id + '/' + action, body: { expectedVersion: selected.data.job.version, reason } });
  }
  async function verifyOrPrint(print: boolean, openPreview = false) {
    if (busy || uncertain || selected.status !== 'ready') return;
    const job = selected.data.job; const current = generation.current; setBusy(true);
    try {
      if (print || openPreview) {
        const fresh = await loadJob(job.id);
        if (!alive.current || current !== generation.current) return;
        if (fresh.job.targetId !== cid || fresh.job.version !== job.version || fresh.job.state !== 'PREVIEW_READY') throw new Error('任务状态已变化，请刷新');
        if (openPreview) setPreview(true);
        else { setMessage('请求打开浏览器打印对话框；关闭对话框不代表实物打印成功。'); window.print(); }
      } else {
        await checkLabel(job.id, cid, scan, kind);
        if (alive.current && current === generation.current) setMessage(kind === 'material' ? '服务器确认条码与此材料任务一致；不代表实物打印成功。' : '服务器确认条码与此容器任务一致；不代表实物打印成功。');
      }
    } catch (e) {
      if (alive.current && current === generation.current) { setPreview(false); if (e instanceof ApiError && e.status === 401) onExpired(); else setMessage(e instanceof ApiError ? e.message : '校验或打印对话框请求失败，请刷新核对'); }
    } finally { if (alive.current) setBusy(false); }
  }
  return <>
    <Alert type="warning" title="合成标签预览 · 无真实打印机连接" description="仅有效已核对实体可创建任务；条码/模板不代表医院批准。模拟失败不会发送设备作业。" />
    {message && <Alert role="status" className="workflow-notice" title={message} type="info" />}
    {uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>重试原标签请求</Button>}
    <label htmlFor="label-container">{kind === 'material' ? '选择材料实体' : '选择容器实体'}</label>
    <Select id="label-container" virtual={false} style={{ width: '100%' }} value={cid || undefined} disabled={busy || uncertain} options={ids.map(value => ({ value, label: value }))}
      onChange={id => { if (reason || scan) setNextContainer(id); else chooseContainer(id); }} />
    {cid && <ReadPanel state={state}>{data => <Card title="实体标签任务（最近100条）">
      <Space wrap><Button disabled={busy || uncertain || !data.ready || data.jobs.length > 0} onClick={() => void execute({ path: '/api/labels/' + (kind === 'material' ? 'materials/' : 'containers/') + cid + '/jobs', body: kind === 'material' ? { requestVersion: data.requestVersion, materialVersion: data.targetVersion } : { requestVersion: data.requestVersion, containerVersion: data.targetVersion } })}>创建标签任务</Button>
        <Button disabled={busy || uncertain} onClick={() => { setPreview(false); void reader.run(cid); if (jobId) void detail.run(jobId); }}>刷新标签任务</Button></Space>
      <Table<LabelJob> rowKey="id" dataSource={data.jobs} pagination={false} scroll={{ x: 750 }} columns={[
        { title: '任务', dataIndex: 'id' }, { title: '模板', dataIndex: 'templateVersion' }, { title: '状态', key: 'state', render: (_, row) => states[row.state] },
        { title: '预览生成次数', dataIndex: 'attempts' }, { title: '操作', key: 'action', render: (_, row) => <Button disabled={busy || uncertain} onClick={() => { if (reason || scan) setNextJob(row.id); else chooseJob(row.id); }}>查看任务 {row.id}</Button> },
      ]} />
    </Card>}</ReadPanel>}
    {jobId && <ReadPanel state={selected}>{data => {
      const job = data.job; const code = barcodeBars(job.barcode);
      return <Card title="任务预览与重打">
        <p>{kind === 'material' ? '材料' : '容器'} {job.targetId} · 任务 {job.id} · 版本 {job.version}</p><Tag>{states[job.state]}</Tag>
        <p>条码：<code>{job.barcode}</code></p>{job.parentJobId && <p>来源任务：{job.parentJobId}</p>}
        <label htmlFor="label-reason">重打 / 重试 / 取消 / 模拟失败原因</label><Input.TextArea id="label-reason" value={reason} disabled={busy || uncertain} maxLength={2000} onChange={e => { setReason(e.target.value); onDirty(); }} />
        <Space wrap>
          <Button disabled={busy || uncertain} onClick={() => act('reprint')}>同实体重打</Button>
          <Button disabled={busy || uncertain || job.state !== 'PREVIEW_READY'} onClick={() => act('simulate-failure')}>模拟适配器失败</Button>
          <Button disabled={busy || uncertain || job.state !== 'FAILED'} onClick={() => act('retry')}>重试此任务</Button>
          <Button danger disabled={busy || uncertain || job.state === 'CANCELLED'} onClick={() => act('cancel')}>取消任务</Button>
          <Button disabled={busy || uncertain || job.state !== 'PREVIEW_READY'} onClick={() => void verifyOrPrint(false, true)}>打开标签预览</Button>
        </Space>
        <label htmlFor="label-scan">{kind === 'material' ? '扫描条码校验此材料' : '扫描条码校验此容器'}</label><Input id="label-scan" value={scan} maxLength={64} disabled={busy || uncertain} onChange={e => { setScan(e.target.value); onDirty(); }} />
        <Button disabled={busy || uncertain || !scan} onClick={() => void verifyOrPrint(false)}>校验条码身份</Button>
        {preview && <><section className="label-print-preview" aria-label={kind === 'material' ? '合成材料标签预览' : '合成容器标签预览'}>
          <strong>合成开发标签 · 非临床使用</strong><p>{job.caseNumber} / {job.requestNumber}</p>
          <p>{job.patientLabel} · {job.patientId}<br />就诊 {job.encounterNumber}</p><p>{kind === 'material' ? '材料' : '容器'} {job.targetId}<br />{job.site} / {job.laterality}</p>
          <svg role="img" aria-label={'Code39 ' + job.barcode} viewBox={`0 0 ${code.width} 70`} width="100%" style={{ background: 'white' }}>
            {code.bars.map(bar => <rect key={bar.x} x={bar.x} width={bar.width} y={0} height={70} fill="black" />)}
          </svg><p>{job.barcode}</p><p>模板 {job.templateVersion}<br />任务 {job.id}</p>
        </section><Space><Button disabled={busy || uncertain} onClick={() => setPreview(false)}>关闭标签预览</Button>
          <Button disabled={busy || uncertain || job.state !== 'PREVIEW_READY'} onClick={() => void verifyOrPrint(true)}>打开浏览器打印对话框</Button></Space></>}
        <Table rowKey="id" dataSource={data.events} pagination={false} scroll={{ x: 650 }} columns={[
          { title: '版本', dataIndex: 'jobVersion' }, { title: '动作', dataIndex: 'action' }, { title: '原因', dataIndex: 'reason' }, { title: '操作者', dataIndex: 'actorId' }, { title: '时间 UTC', dataIndex: 'occurredAt' },
        ]} />
      </Card>;
    }}</ReadPanel>}
    <Modal open={!!nextJob} title="放弃当前任务的本地输入？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNextJob('')} onOk={() => { chooseJob(nextJob); setNextJob(''); }}><p>切换清除本地原因与扫描内容。</p></Modal>
    <Modal open={!!nextContainer} title="放弃当前容器的本地输入？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNextContainer('')} onOk={() => { chooseContainer(nextContainer); setNextContainer(''); }}><p>切换清除本地原因与扫描内容，不改变服务器任务。</p></Modal>
  </>;
}
