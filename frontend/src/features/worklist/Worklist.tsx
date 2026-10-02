import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Input, Modal, Select, Space, Table, Tag } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { kinds, states, keyOf, claimable, loadPage, loadTrace, sendBatch, type Filter, type Item, type Batch, type BatchResult } from './api';
type Callbacks = { onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void };
export function Worklist({ scopeId, ...callbacks }: Callbacks & { scopeId: string }) {
  const [filter, setFilter] = useState<Filter>({ kind: 'ALL', state: 'ALL', due: 'ALL', sort: 'OLDEST', page: 1, pageSize: 10 });
  const [selected, setSelected] = useState<Item[]>([]), [reason, setReason] = useState(''), [trace, setTrace] = useState('');
  const [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState(''), [planned, setPlanned] = useState<Batch>(), [result, setResult] = useState<BatchResult>();
  const [confirming, setConfirming] = useState(false); const next = useRef<(() => void) | undefined>(undefined), active = useRef(false), alive = useRef(true), original = useRef<Batch | undefined>(undefined);
  const [intent] = useState(() => new CommandIntent<Batch>());
  const [reader] = useState(() => new ReadController(async (query: Filter, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadPage(scopeId, query, signal) }; }
    catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); if (e instanceof ApiError && [403, 404].includes(e.status)) return { status: 'forbidden' as const, message: '此工作范围或模块不可用，或未获授权。' }; throw e; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  useEffect(() => { void reader.run(filter); return reader.stop; }, [reader, filter]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const dirty = selected.length > 0 || reason.length > 0;
  function reset() { setSelected([]); setReason(''); callbacks.onClean(); }
  function change(action: () => void) { if (busy || uncertain) return; if (dirty) { next.current = action; setConfirming(true); } else action(); }
  function query(values: Partial<Filter>) { change(() => { reset(); setResult(undefined); setFilter({ ...filter, ...values }); }); }
  async function execute(batch: Batch) {
    if (active.current) return; active.current = true; original.current = batch; setBusy(true); setPlanned(undefined); callbacks.onDirty(); callbacks.onPending(true); setMessage('');
    try {
      const response = await intent.run(batch, b => sendBatch(scopeId, b)); if (!response || !alive.current) return;
      setResult(response); const unknown = response.items.some(i => i.outcome === 'UNKNOWN'); setUncertain(unknown); callbacks.onPending(unknown);
      if (unknown) setMessage('部分结果待确认；原批次重试时，成功项只重放回执。');
      else { reset(); setMessage('已收到逐项结果；成功项已提交，失败项未视为成功。'); void reader.run(filter); }
    } catch (e) {
      if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { callbacks.onExpired(); return; }
      const definite = e instanceof ApiError && [400, 403, 404].includes(e.status);
      if (definite) { intent.rejected(); callbacks.onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '批次结果待确认；不得假定整批回滚，请用原批次确认结果。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  return <>
    <Alert type="warning" title="现有流程工作列表 · 合成超期阈值，非医院TAT承诺" description="仅显示授权域。批量仅支持技术任务领取，逐项独立提交；没有跨页全选或批量QC放行。" />
    <Space wrap><label htmlFor="work-kind">工作类别</label><Select id="work-kind" virtual={false} value={filter.kind} disabled={busy || uncertain} options={kinds.map(value => ({ value, label: value }))} onChange={kind => query({ kind, state: 'ALL', page: 1 })} />
      <label htmlFor="work-state">真实状态</label><Select id="work-state" virtual={false} value={filter.state} disabled={busy || uncertain} options={states.map(value => ({ value, label: value }))} onChange={value => query({ state: value, page: 1 })} />
      <label htmlFor="work-due">超期筛选</label><Select id="work-due" virtual={false} value={filter.due} disabled={busy || uncertain} options={[{ value: 'ALL', label: '全部时限' }, { value: 'OVERDUE', label: '合成阈值超期' }, { value: 'NOT_OVERDUE', label: '未超期或已结束' }]} onChange={due => query({ due, page: 1 })} />
      <label htmlFor="work-sort">工作排序</label><Select id="work-sort" virtual={false} value={filter.sort} disabled={busy || uncertain} options={[{ value: 'OLDEST', label: '最早创建' }, { value: 'NEWEST', label: '最近创建' }, { value: 'NUMBER', label: '申请号' }]} onChange={sort => query({ sort, page: 1 })} />
      <Button disabled={busy || uncertain} onClick={() => change(() => { reset(); void reader.run(filter); })}>刷新工作列表</Button></Space>
    {message && <Alert role="status" type="info" title={message} />}
    {uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>原批次确认/重试</Button>}
    <ReadPanel state={state}>{data => <Card title={'当前授权过滤集：' + data.total + ' 项'}>
      <p>服务端截至 {data.asOf} · 合成阈值 {data.syntheticDueMinutes} 分钟；相等时刻不算超期，结束项不超期。</p>
      <Table<Item> rowKey={keyOf} dataSource={data.items} scroll={{ x: 1450 }} rowSelection={{ selectedRowKeys: selected.map(keyOf), preserveSelectedRowKeys: false, getCheckboxProps: i => ({ disabled: busy || uncertain || !claimable(i), 'aria-label': '选择任务 ' + i.id }), onChange: (_, rows) => { setSelected(rows); if (rows.length || reason) callbacks.onDirty(); else callbacks.onClean(); } }}
        pagination={{ current: filter.page, pageSize: filter.pageSize, total: data.total, showSizeChanger: true, pageSizeOptions: [10, 20, 50], disabled: busy || uncertain, onChange: (page, pageSize) => query({ page, pageSize }) }} columns={[
          { title: '类别 / 稳定ID', key: 'identity', render: (_, i) => <span>{i.kind}<br />{i.id}</span> }, { title: '申请 / 患者ID', key: 'request', render: (_, i) => <span>{i.requestNumber}<br />{i.patientId}</span> }, { title: '真实状态', dataIndex: 'state' }, { title: '版本', dataIndex: 'version' }, { title: '来源盒', dataIndex: 'cassetteId' }, { title: '消费限制', key: 'blocked', render: (_, i) => i.blocked ? <Tag>来源需核查 / 隔离</Tag> : '按原命令核验' },
          { title: '合成截止 / 超期', key: 'due', render: (_, i) => <span>{i.dueAt ?? '已结束 / 不适用'}<br />{i.overdue ? '已超过合成阈值' : '未超期'}</span> }, { title: '追踪', key: 'trace', render: (_, i) => <Button disabled={busy || uncertain} onClick={() => change(() => { reset(); setTrace(i.requestId); })}>追踪申请 {i.requestId}</Button> },
        ]} />{data.items.length === 0 && <p>当前授权过滤集没有工作项；不是未来模块的零统计。</p>}
    </Card>}</ReadPanel>
    <Card title={'本页明确选择：' + selected.length + ' 项（最多20项）'}>
      <label htmlFor="work-reason">批量领取原因</label><Input.TextArea id="work-reason" value={reason} disabled={busy || uncertain} maxLength={2000} rows={2} onChange={e => { setReason(e.target.value); if (e.target.value || selected.length) callbacks.onDirty(); else callbacks.onClean(); }} />
      <Button disabled={busy || uncertain || selected.length === 0 || selected.length > 20 || !reason.trim()} onClick={() => { if (selected.some(i => !claimable(i) || i.cassetteId === null)) return; setPlanned({ batchId: crypto.randomUUID(), items: selected.map(i => ({ taskId: i.id, expectedVersion: i.version, confirmedCassetteId: i.cassetteId ?? '' })), reason }); }}>核对所选并批量领取</Button>
    </Card>
    {result && <Card title={'逐项结果 · 批次 ' + result.batchId}><Table rowKey="taskId" dataSource={result.items} pagination={false} scroll={{ x: 800 }} columns={[{ title: '原任务ID', dataIndex: 'taskId' }, { title: '结果', dataIndex: 'outcome' }, { title: 'HTTP语义', dataIndex: 'status' }, { title: '原因码', dataIndex: 'code' }, { title: '回执版本', dataIndex: 'version' }, { title: '原回执重放', key: 'replayed', render: (_, i) => i.replayed ? '是' : '否' }]} /></Card>}
    {trace && <TracePanel key={trace} id={trace} onExpired={callbacks.onExpired} />}
    <Modal open={!!planned} title="确认本页所选任务、患者和来源盒" okText="确认逐项领取" cancelText="返回核对" onCancel={() => setPlanned(undefined)} onOk={() => { if (planned) void execute(planned); }}><p>每项独立提交，部分失败不撤销已成功项。不会领取未选择或其他页任务。</p>{selected.map(i => <p key={i.id}>{i.requestNumber} / 患者 {i.patientId}<br />任务 {i.id} / v{i.version}<br />盒 {i.cassetteId}</p>)}</Modal>
    <Modal open={confirming} title="清除本页批选与原因后切换？" okText="清除并切换" cancelText="保留本页" onCancel={() => { next.current = undefined; setConfirming(false); }} onOk={() => { const action = next.current; next.current = undefined; setConfirming(false); action?.(); }}><p>不会跨页保存隐藏选择。</p></Modal>
  </>;
}
function TracePanel({ id, onExpired }: { id: string; onExpired: () => void }) {
  const [page, setPage] = useState(1); const [reader] = useState(() => new ReadController(async (p: number, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadTrace(id, p, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); if (e instanceof ApiError && [403, 404].includes(e.status)) return { status: 'forbidden' as const, message: '追踪对象不可用或未授权，不显示其存在性。' }; throw e; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(page); return reader.stop; }, [reader, page]);
  return <Card title={'现有流程追踪：' + id}><p>只展示当前授权域的追加事件，未展示不表示未发生。没有报告、WSI或AI虚构节点。</p><Button onClick={() => void reader.run(page)}>刷新事件追踪</Button><ReadPanel state={state}>{t => <Table rowKey={e => e.domain + ':' + e.eventId} dataSource={t.events} scroll={{ x: 1200 }} pagination={{ current: page, pageSize: 20, total: t.total, showSizeChanger: false, onChange: setPage }} columns={[{ title: '域', dataIndex: 'domain' }, { title: '动作', dataIndex: 'action' }, { title: '实体ID', dataIndex: 'entityId' }, { title: '版本', dataIndex: 'version' }, { title: '关联类型', dataIndex: 'relatedType' }, { title: '关联ID', dataIndex: 'relatedId' }, { title: '时间 UTC', dataIndex: 'occurredAt' }]} />}</ReadPanel></Card>;
}
