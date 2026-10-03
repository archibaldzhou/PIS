import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Table } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { cases, load, send, names, type Action, type Command, type Detail } from './api';
type Callbacks = { onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void };
export function Frozen({ requestId, ...callbacks }: Callbacks & { requestId: string }) {
 const [id, setId] = useState(''), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [next, setNext] = useState<string | null>(null);
 const [reader] = useState(() => new ReadController(async (_: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await cases(requestId, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(requestId); return reader.stop; }, [reader, requestId]);
 function change(value: string) { setId(value); setDirty(false); callbacks.onClean(); }
 return <><Alert type="warning" title="术中冰冻 · 本地合成记录／非临床使用" description="独立材料与流程，不要求石蜡制作。人工时间不是医院TAT；不拨号、不发消息、不自动生成诊断。" />
 <ReadPanel state={state}>{items => <Card title="选择冰冻来源病例">{items.map(c => <Button key={c.id} style={{ maxWidth: '100%', height: 'auto', whiteSpace: 'normal', overflowWrap: 'anywhere' }} disabled={pending} onClick={() => { if (c.id !== id) { if (dirty) setNext(c.id); else change(c.id); } }}>{c.number} / {c.id}</Button>)}</Card>}</ReadPanel>
 <Button disabled={pending || dirty} onClick={() => void reader.run(requestId)}>重试冰冻病例列表</Button>
 {id && <Editor key={id} id={id} {...callbacks} onDirty={() => { setDirty(true); callbacks.onDirty(); }} onClean={() => { setDirty(false); callbacks.onClean(); }} onPending={v => { setPending(v); callbacks.onPending(v); }} />}
 <Modal open={next !== null} title="放弃冰冻本地输入并切换病例？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNext(null)} onOk={() => { if (next) change(next); setNext(null); }}><p>不撤销服务器操作，切换后重新核对病例和版本。</p></Modal></>;
}
function Editor({ id, ...callbacks }: Callbacks & { id: string }) {
 const [page, setPage] = useState(1), [mode, setMode] = useState<Action>('RECEIVE'), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [generation, setGeneration] = useState(0), [next, setNext] = useState<(() => void) | null>(null);
 const [reader] = useState(() => new ReadController(async (p: number, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await load(id, p, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(page); return reader.stop; }, [reader, page]);
 function clean() { setDirty(false); callbacks.onClean(); }
 function change(action: () => void) { if (pending) return; if (dirty) setNext(() => action); else action(); }
 const refresh = () => { clean(); setGeneration(v => v + 1); void reader.run(page); };
 return <><Button disabled={pending} onClick={() => change(refresh)}>重新读取冰冻版本</Button><ReadPanel state={state}>{d => <>
  <section className="identity-strip" aria-label="冰冻病例身份"><strong>{d.number}</strong><span>病例 {id}</span><span>冰冻申请 {d.head?.id ?? '尚未登记'}</span><span>独立材料 {d.head?.materialId ?? '尚未登记'}</span><span>阶段 {d.head?.stage === 'REVIEWED' && !d.reviewValid ? 'REVIEW_INVALIDATED' : d.head?.stage ?? '未登记'}</span><span>版本 {d.head?.version ?? -1} / QC {d.head?.qcState ?? 'NOT_ASSESSED'}</span></section>
  <p>人工接收 {d.receivedAt ?? '未记录'} · 制备 {d.preparedAt ?? '未记录'} · 两个记录间隔 {d.elapsedSeconds === null ? '未知' : d.elapsedSeconds + ' 秒'}（显示用途，非医院TAT；无自动升级）</p>
  <p style={{ overflowWrap: 'anywhere' }}>当前负责人 {d.head?.ownerId ?? '未指定'} · {d.head?.ownerActive ? '已领取' : '未领取'} · 当前人工修订 {d.head?.revisionId ?? '无'} · 当前复核 {d.head?.reviewId ?? '无'} · {d.reviewValid ? '当前合成复核有效' : '当前无有效合成复核'}</p>
  {!d.gateReady && <Alert type="error" title="来源接收／QC／身份隔离门禁未就绪" />}
  <label htmlFor="frozen-action">冰冻阶段操作</label><Select id="frozen-action" virtual={false} value={mode} disabled={pending} style={{ width: '100%' }} options={Object.entries(names).map(([value, label]) => ({ value, label }))} onChange={(v: Action) => change(() => { clean(); setMode(v); })} />
  <FrozenForm key={id + ':' + mode + ':' + generation} detail={d} action={mode} {...callbacks} onDirty={() => { setDirty(true); callbacks.onDirty(); }} onClean={clean} onPending={v => { setPending(v); callbacks.onPending(v); }} onSaved={refresh} />
  <Card title="不可覆盖的冰冻修订、时间及沟通历史"><p>COMMUNICATE 仅沟通记录；READBACK 与 CONFIRM 是接收者分别录入的合成证据，均不表示真实送达。常规关联保留当时确切版本，历史不会自动升级；历史复核事件不表示当前仍有效。</p>
   <Table rowKey="id" dataSource={d.events} pagination={false} scroll={{ x: 1500 }} columns={[{ title: '事件 / 版本', key: 'id', render: (_, e) => <span>{names[e.action]} / v{e.version}<br />{e.id}<br />{e.communicationMethod === 'LOCAL_SIMULATION' ? '方式：本地合成记录' : ''}</span> }, { title: '发生与记录时间', key: 'time', render: (_, e) => <span>{e.occurredAt} ({e.zoneId}, offset {e.offsetSeconds})<br />记录 {e.recordedAt}</span> }, { title: '人员 / 接收者', key: 'person', render: (_, e) => <span>{e.actorId}<br />{e.targetUserId ?? '—'}</span> }, { title: '确切修订与引用', key: 'binding', render: (_, e) => <span>{e.resultId ?? '—'}<br />引用 {e.relatedId ?? '—'}</span> }, { title: '人工文本及原因', key: 'content', render: (_, e) => <span style={{ whiteSpace: 'pre-wrap' }}>{e.content}<br />原因：{e.reason}</span> }, { title: '常规关联版本', key: 'routine', render: (_, e) => e.routineRevisionId ? <span>{e.comparison === 'DISCREPANCY' ? '存在不一致（保留差异）' : '人工记录一致'}<br />修订 {e.routineRevisionId}<br />模板 {e.routineTemplateCode} / {e.routineTemplateVersion}<br />模拟冻结 {e.routineSignatureId}</span> : '—' }]} />
   <Button disabled={pending || page === 1} onClick={() => change(() => setPage(v => v - 1))}>上一页冰冻历史</Button><span>第 {page} 页</span><Button disabled={pending || d.events.length < 20 || page >= 10000} onClick={() => change(() => setPage(v => v + 1))}>下一页冰冻历史</Button>
  </Card>
 </>}</ReadPanel><Modal open={!!next} title="放弃冰冻输入并切换阶段或历史？" okText="清除并继续" cancelText="继续编辑" onCancel={() => setNext(null)} onOk={() => { const action = next; setNext(null); clean(); setGeneration(v => v + 1); action?.(); }}><p>已提交操作不会撤销；重新核对精确修订。</p></Modal></>;
}
interface Values { confirmedId: string; occurredAt: string; zoneId: string; reason: string; containerId?: string; site?: string; relatedId?: string; targetUserId?: string; content?: string; routineSignatureId?: string; comparison?: string }
function FrozenForm({ detail: d, action, onDirty, onClean, onPending, onExpired, onSaved }: Callbacks & { detail: Detail; action: Action; onSaved: () => void }) {
 const [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState(''); const [intent] = useState(() => new CommandIntent<Command>()); const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined);
 useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
 async function execute(c: Command) {
  if (active.current) return; active.current = true; original.current = c; setBusy(true); onDirty(); onPending(true); setMessage('');
  try { if (await intent.run(c, send) && alive.current) { onPending(false); onClean(); onSaved(); } }
  catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; } const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)); if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '结果待确认；保留原病例、阶段、修订、输入与请求键重试。'); }
  finally { active.current = false; if (alive.current) setBusy(false); }
 }
 const textRequired = ['DRAFT', 'READBACK', 'CONFIRM', 'LINK_ROUTINE'].includes(action);
 return <Card title={names[action]}><Alert type="info" title="仅人工合成输入；没有自动临床动作" description="时间需显式填写 offset 和 IANA 时区；迟补保留真实服务器记录时间。转交、QC、时间更正和新修订会使旧复核失效。" />
 {message && <Alert role="status" title={message} />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>确认原冰冻请求</Button>}
 <Form<Values> layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={v => { if (busy || uncertain) return; void execute({ caseId: d.caseId, action, body: { reviewToken: d.reviewToken, confirmedCaseId: v.confirmedId, expectedVersion: d.head?.version ?? -1, occurredAt: v.occurredAt, zoneId: v.zoneId, reason: v.reason, containerId: v.containerId ?? null, site: v.site ?? null, resultId: d.head?.revisionId ?? null, relatedId: v.relatedId ?? null, targetUserId: v.targetUserId ?? null, content: v.content ?? '', routineSignatureId: v.routineSignatureId ?? null, comparison: v.comparison ?? null } }); }}>
  <Form.Item name="confirmedId" label="核对冰冻病例 UUID" rules={[{ validator: (_, v: unknown) => v === d.caseId ? Promise.resolve() : Promise.reject(new Error('请核对病例UUID')) }]}><Input maxLength={36} /></Form.Item>
  <Form.Item name="occurredAt" label="人工发生时间（含 offset）" rules={[{ required: true }, { pattern: /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/, message: '例如 2026-10-01T10:00:00+08:00' }]}><Input placeholder="2026-10-01T10:00:00+08:00" maxLength={40} /></Form.Item>
  <Form.Item name="zoneId" label="发生地 IANA 时区" rules={[{ required: true, whitespace: true }]}><Input placeholder="Asia/Shanghai" maxLength={80} /></Form.Item>
  {action === 'RECEIVE' && <><Form.Item name="containerId" label="核对来源容器" rules={[{ required: true }]}><Select virtual={false} options={d.sources.map(s => ({ value: s.id, label: s.site + ' / ' + s.id }))} /></Form.Item><Form.Item name="site" label="冰冻材料人工部位" rules={[{ required: true, whitespace: true }]}><Input maxLength={255} /></Form.Item></>}
  {['TRANSFER', 'COMMUNICATE'].includes(action) && <Form.Item name="targetUserId" label={action === 'TRANSFER' ? '合格接手人' : '目录核实的合成接收者'} rules={[{ required: true }]}><Select virtual={false} options={d.candidates.filter(c => c.record).map(c => ({ value: c.id, label: c.name + ' / ' + c.id }))} /></Form.Item>}
  {['CORRECT_TIME', 'READBACK', 'CONFIRM'].includes(action) && <Form.Item name="relatedId" label={action === 'CORRECT_TIME' ? '被更正的当前接收／制备事件 UUID' : '确切本地沟通事件 UUID'} rules={[{ required: true, whitespace: true }]}><Input maxLength={36} /></Form.Item>}
  {action === 'LINK_ROUTINE' && <><Form.Item name="routineSignatureId" label="常规报告确切模拟冻结 UUID" rules={[{ required: true }]}><Input maxLength={36} /></Form.Item><Form.Item name="comparison" label="人工版本比较" rules={[{ required: true }]}><Select virtual={false} options={[{ value: 'CONSISTENT', label: '人工记录一致' }, { value: 'DISCREPANCY', label: '存在差异（保留并解释）' }]} /></Form.Item></>}
  <Form.Item name="content" label={textRequired ? '人工结果／证据／差异解释（必填）' : '人工备注（可选）'} rules={[{ required: textRequired, whitespace: true }, { max: 8000 }]}><Input.TextArea rows={4} maxLength={8000} /></Form.Item>
  <Form.Item name="reason" label="本次记录／迟补／更正原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={2} maxLength={2000} /></Form.Item>
  <Button type="primary" htmlType="submit" disabled={busy || uncertain}>{names[action]}并保存</Button>
 </Form></Card>;
}
