import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Table } from 'antd';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { actions, paths, load, send, type Action, type Path, type Command, type Detail } from './cytologyApi';
type Callbacks = { onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void };
export function Cytology({ requestId, containers, ...cb }: Callbacks & { requestId: string; containers: { id: string; site: string }[] }) {
 const [id, setId] = useState(''), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [next, setNext] = useState<string | null>(null);
 function change(v: string) { setId(v); setDirty(false); cb.onClean(); }
 return <><Alert type="warning" title="细胞学 · 合成制备／非临床使用" description="SYN_PORTION 仅为合成份数，不代表体积、细胞量或临床产率。无自动诊断；细胞蜡块为可选路径。" />
 <Card title="选择已接收来源容器">{containers.map(c => <Button key={c.id} style={{ maxWidth: '100%', height: 'auto', whiteSpace: 'normal', overflowWrap: 'anywhere' }} disabled={pending} onClick={() => { if (c.id !== id) { if (dirty) setNext(c.id); else change(c.id); } }}>{c.site} / {c.id}</Button>)}</Card>
 {id && <Editor key={id} rid={requestId} cid={id} {...cb} onDirty={() => { setDirty(true); cb.onDirty(); }} onClean={() => { setDirty(false); cb.onClean(); }} onPending={v => { setPending(v); cb.onPending(v); }} />}
 <Modal open={next !== null} title="放弃细胞学输入并切换容器？" okText="放弃并切换" cancelText="继续编辑" onCancel={() => setNext(null)} onOk={() => { if (next) change(next); setNext(null); }}>切换后重新核对申请、容器和制备版本；已提交操作不会撤销。</Modal></>;
}
function Editor({ rid, cid, ...cb }: Callbacks & { rid: string; cid: string }) {
 const [page, setPage] = useState(1), [action, setAction] = useState<Action>('REGISTER'), [path, setPath] = useState<Path>('DIRECT_SMEAR'), [dirty, setDirty] = useState(false), [pending, setPending] = useState(false), [generation, setGeneration] = useState(0), [next, setNext] = useState<(() => void) | null>(null);
 const [reader] = useState(() => new ReadController(async (p: number, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await load(rid, cid, p, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) cb.onExpired(); throw e; } }));
 const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(page); return reader.stop; }, [reader, page]);
 function clean() { setDirty(false); cb.onClean(); }
 function change(fn: () => void) { if (pending) return; if (dirty) setNext(() => fn); else { clean(); setGeneration(v => v + 1); fn(); } }
 function refresh() { clean(); setGeneration(v => v + 1); void reader.run(page); }
 return <><Button disabled={pending} onClick={() => change(refresh)}>重新读取细胞学版本</Button><ReadPanel state={state}>{d => <>
 <section className="identity-strip" aria-label="细胞学身份"><strong>{d.caseNumber}</strong><span>患者 {d.patientId}</span><span>申请 {rid}</span><span>病例 {d.caseId}</span><span>容器 {cid}</span><span>标本 {d.specimen?.id ?? '未登记'}</span></section>
 <p>来源 QC {d.specimen?.qcState ?? '未登记'} / 代次 {d.specimen?.qcVersion ?? -1} · 标本版本 {d.specimen?.version ?? -1} · 剩余 {d.specimen?.remaining ?? '未知'} / 初始 {d.specimen?.initialQuantity ?? '未知'} SYN_PORTION</p>
 <label htmlFor="cyto-action">细胞学操作</label><Select id="cyto-action" virtual={false} value={action} disabled={pending} style={{ width: '100%' }} options={Object.entries(actions).map(([value, label]) => ({ value, label }))} onChange={(v: Action) => change(() => setAction(v))} />
 {action === 'PREPARE' && <><label htmlFor="cyto-path">制备路径</label><Select id="cyto-path" virtual={false} value={path} disabled={pending} style={{ width: '100%' }} options={Object.entries(paths).map(([value, label]) => ({ value, label }))} onChange={(v: Path) => change(() => setPath(v))} /></>}
 <Entry key={cid + ':' + action + ':' + path + ':' + generation} d={d} action={action} path={path} {...cb} onDirty={() => { setDirty(true); cb.onDirty(); }} onClean={clean} onPending={v => { setPending(v); cb.onPending(v); }} onSaved={refresh} />
 <Card title="独立制备与材料来源"><Table rowKey="id" dataSource={d.preparations} pagination={false} scroll={{ x: 900 }} columns={[{ title: '制备 / 路径', render: (_, p) => <span>{p.id}<br />{paths[p.path]} / v{p.version}</span> }, { title: '状态及来源', render: (_, p) => <span>{p.state} / 预留 {p.transferred}<br />{d.specimen?.qcState === 'PASS' && p.sourceQcVersion === d.specimen.qcVersion ? '来源代次一致（仍需材料QC）' : '来源失效，禁止后续消费'}<br />重复制备来源 {p.repeatOf ?? '无'}</span> }, { title: '人工制备元数据', dataIndex: 'metadata' }]} />
 <Table rowKey="id" dataSource={d.materials} pagination={false} scroll={{ x: 900 }} columns={[{ title: '独立材料 / 条码', render: (_, m) => <span>{m.kind} / {m.id}<br />{m.barcode}</span> }, { title: '制备 ID', dataIndex: 'cytologyPreparationId' }, { title: '可选蜡块 ID', render: (_, m) => m.kind === 'BLOCK' ? '本行是细胞蜡块' : m.blockId ?? '无蜡块直制' }, { title: '状态', dataIndex: 'state' }]} /></Card>
 <Card title="追加式数量与原因历史"><Table rowKey="id" dataSource={d.events} pagination={false} scroll={{ x: 1100 }} columns={[{ title: '事件 / 版本', render: (_, e) => <span>{actions[e.action]} / v{e.version}<br />{e.id}</span> }, { title: '转入 / 消耗 / 废弃 / 退回 / 玻片', render: (_, e) => [e.transferred, e.consumed, e.discarded, e.returned, e.slides].join(' / ') }, { title: '原因', dataIndex: 'reason' }, { title: '记录人 / 时间', render: (_, e) => <span>{e.actorId}<br />{e.recordedAt}</span> }]} />
 <Button disabled={pending || page === 1} onClick={() => change(() => setPage(v => v - 1))}>上一页细胞学历史</Button><span>第 {page} 页</span><Button disabled={pending || d.events.length < 20 || page >= 10000} onClick={() => change(() => setPage(v => v + 1))}>下一页细胞学历史</Button></Card>
 </>}</ReadPanel><Modal open={!!next} title="放弃细胞学输入并切换操作或路径？" okText="清除并继续" cancelText="继续编辑" onCancel={() => setNext(null)} onOk={() => { const fn = next; setNext(null); clean(); setGeneration(v => v + 1); fn?.(); }}>清除当前路径字段，重新核对制备来源与数量。</Modal></>;
}
interface Values { confirmedId: string; reason: string; metadata?: string; transferred?: number; repeatOf?: string; preparationId?: string; consumed?: number; discarded?: number; returned?: number; slides?: number }
function Entry({ d, action, path, onDirty, onClean, onPending, onExpired, onSaved }: Callbacks & { d: Detail; action: Action; path: Path; onSaved: () => void }) {
 const [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState(''); const [intent] = useState(() => new CommandIntent<Command>()); const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined);
 useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
 async function execute(c: Command) {
  if (active.current) return; active.current = true; original.current = c; setBusy(true); onDirty(); onPending(true); setMessage('');
  try { if (await intent.run(c, send) && alive.current) { onPending(false); onClean(); onSaved(); } }
  catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; } const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)); if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '结果待确认；保留原容器、制备、数量与请求键重试。'); }
  finally { active.current = false; if (alive.current) setBusy(false); }
 }
 const accounting = action === 'COMPLETE' || action === 'FAIL';
 return <Card title={actions[action]}>{message && <Alert role="status" title={message} />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>确认原细胞学请求</Button>}
 <Form<Values> layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={v => { if (busy || uncertain) return; const p = d.preparations.find(p => p.id === v.preparationId); void execute({ requestId: d.requestId, containerId: d.containerId, action, body: { confirmedContainerId: v.confirmedId, expectedVersion: d.specimen?.version ?? -1, reason: v.reason, metadata: v.metadata ?? '', path: action === 'PREPARE' ? path : null, transferred: v.transferred ?? 0, repeatOf: v.repeatOf ?? null, preparationId: p?.id ?? null, preparationVersion: p?.version ?? -1, consumed: v.consumed ?? 0, discarded: v.discarded ?? 0, returned: v.returned ?? 0, slides: v.slides ?? 0 } }); }}>
 <Form.Item name="confirmedId" label="核对来源容器 UUID" rules={[{ validator: (_, v: unknown) => v === d.containerId ? Promise.resolve() : Promise.reject(new Error('请核对来源容器UUID')) }]}><Input maxLength={36} /></Form.Item>
 {(action === 'REGISTER' || action === 'PREPARE') && <Form.Item name="metadata" label={action === 'REGISTER' ? '人工合成标本说明' : path === 'DIRECT_SMEAR' ? '人工涂片方法及固定记录' : path === 'LIQUID_BASED' ? '人工液基介质及制备方法记录' : '人工细胞蜡块制备及包埋记录'} rules={[{ required: true, whitespace: true }]}><Input.TextArea maxLength={1000} /></Form.Item>}
 {action === 'PREPARE' && <><Form.Item name="transferred" label="预留合成份数" rules={[{ required: true }]}><InputNumber min={1} max={99} precision={0} /></Form.Item><Form.Item name="repeatOf" label="重复制备来源（可选，必须填写原因）"><Select allowClear virtual={false} options={d.preparations.map(p => ({ value: p.id, label: paths[p.path] + ' / ' + p.id }))} /></Form.Item></>}
 {accounting && <><Form.Item name="preparationId" label="核对待完成制备" rules={[{ required: true }]}><Select virtual={false} options={d.preparations.filter(p => p.state === 'RESERVED').map(p => ({ value: p.id, label: paths[p.path] + ' / ' + p.id + ' / 预留 ' + p.transferred }))} /></Form.Item><p>预留 = 消耗 + 废弃 + 退回。失败不产出玻片，也不恢复失效来源资格。</p>{(action === 'COMPLETE' ? ['consumed', 'discarded', 'returned', 'slides'] as const : ['discarded', 'returned'] as const).map(name => <Form.Item key={name} name={name} label={{ consumed: '消耗合成份数', discarded: '废弃合成份数', returned: '退回合成份数', slides: '新建玻片数量' }[name]} rules={[{ required: true }]}><InputNumber min={0} max={name === 'slides' ? 20 : 99} precision={0} /></Form.Item>)}</>}
 <Form.Item name="reason" label="操作／重复制备原因" rules={[{ required: true, whitespace: true }]}><Input.TextArea maxLength={2000} /></Form.Item><Button type="primary" htmlType="submit" disabled={busy || uncertain}>{actions[action]}并保存</Button></Form></Card>;
}
