import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table } from 'antd';
import { SyntheticDecision } from './SyntheticDecision';
import { ApiError } from '../../api';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { CommandIntent } from '../../shared/command';
import { load, history, send, type Detail, type Command } from './api';
type Props = { id: string; onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void; onSaved: () => void };
export function ReportEditor({ id, ...callbacks }: Props) {
  const [mode,setMode]=useState(false),[dirty,setDirty]=useState(false),[pending,setPending]=useState(false),[switchPrompt,setSwitchPrompt]=useState(false);
  const guarded={...callbacks,onDirty:()=>{setDirty(true);callbacks.onDirty();},onClean:()=>{setDirty(false);callbacks.onClean();},onPending:(v:boolean)=>{setPending(v);callbacks.onPending(v);}};
  function switchMode(){setMode(v=>!v);setSwitchPrompt(false);setDirty(false);callbacks.onClean();void reader.run(id);}
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await load(id, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) callbacks.onExpired(); if (e instanceof ApiError && [403, 404].includes(e.status)) return { status: 'forbidden' as const, message: '仅当前已领取的合格医生可访问报告草稿。' }; throw e; } }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(id); return reader.stop; }, [reader, id]);
  return <ReadPanel state={state}>{d => <>
    <section className="identity-strip" aria-label="报告草稿身份"><strong>{d.context.number}</strong><span>病例 {id}</span><span>患者 {d.context.patientId}</span><span>申请 {d.context.requestId}</span><span>分配版本 {d.context.assignmentVersion}</span><span>草稿版本 {d.current?.version ?? -1}</span></section>
    <Alert type="warning" title="仅人工输入合成草稿 · 无自动诊断、复核、签署或发送" />
    {!d.context.ready && <Alert type="error" title="QC或身份门禁未就绪，禁止保存草稿" />}
    <Button disabled={pending} onClick={()=>{if(dirty)setSwitchPrompt(true);else switchMode();}}>{mode?'返回报告草稿':'医生人工处理合成结果'}</Button>
    {mode?<SyntheticDecision key={id} id={id} {...guarded}/>:<DraftForm key={d.current?.id??id} detail={d} {...guarded} />}
    <Modal open={switchPrompt} title="清除未提交表单并切换？" okText="清除并切换面板" cancelText="保留表单" onCancel={()=>setSwitchPrompt(false)} onOk={switchMode}><p>未提交内容将清除，服务端历史不变。</p></Modal>
    <History id={id} onExpired={callbacks.onExpired} />
  </>}</ReadPanel>;
}
function History({ id, onExpired }: { id: string; onExpired: () => void }) {
  const [page, setPage] = useState(1); const [reader] = useState(() => new ReadController(async (p: number, signal: AbortSignal) => { try { return { status: 'ready' as const, data: await history(id, p, signal) }; } catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); throw e; } }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot); useEffect(() => { void reader.run(page); return reader.stop; }, [reader, page]);
  return <Card title="不可变草稿修订历史"><ReadPanel state={state}>{rows => <><Table rowKey="id" dataSource={rows} pagination={false} scroll={{ x: 1000 }} columns={[{ title: '修订版本', dataIndex: 'version' }, { title: '模板', key: 'template', render: (_, r) => r.templateCode + ' / v' + r.templateVersion }, { title: '作者', dataIndex: 'authorId' }, { title: '原因', dataIndex: 'reason' }, { title: '人工字段快照', key: 'fields', render: (_, r) => <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(r.fields, null, 2)}</pre> }, { title: '时间', dataIndex: 'createdAt' }]} /><Space><Button disabled={page === 1} onClick={() => setPage(p => p - 1)}>上一页修订</Button><span>第 {page} 页</span><Button disabled={rows.length < 20} onClick={() => setPage(p => p + 1)}>下一页修订</Button></Space></>}</ReadPanel></Card>;
}
interface Values { confirmedId: string; gross: string; microscopy: string; diagnosis: string; notes: string; sampleCount?: number; manualChecked?: boolean; reason: string }
function DraftForm({ detail, onDirty, onClean, onPending, onExpired, onSaved }: Omit<Props, 'id'> & { detail: Detail }) {
  const current = detail.current; const initial = current ? detail.templates.find(t => t.code === current.templateCode && t.version === current.templateVersion) : undefined;
  const [chosen, setChosen] = useState(initial ? initial.code + ':' + initial.version : ''), [next, setNext] = useState(''), [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [message, setMessage] = useState('');
  const [form] = Form.useForm<Values>(); const [intent] = useState(() => new CommandIntent<Command>()); const active = useRef(false), alive = useRef(true), original = useRef<Command | undefined>(undefined);
  const template = detail.templates.find(t => t.code + ':' + t.version === chosen);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  function switchTemplate(value: string) { setChosen(value); setNext(''); form.resetFields(); form.setFieldsValue({ gross: '', microscopy: '', diagnosis: '', notes: '', sampleCount: undefined, manualChecked: undefined, reason: '', confirmedId: '' }); onDirty(); }
  async function execute(c: Command) {
    if (active.current) return; active.current = true; original.current = c; setBusy(true); onDirty(); onPending(true); setMessage('');
    try { if (await intent.run(c, send) && alive.current) { onPending(false); onClean(); onSaved(); } }
    catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) { onExpired(); return; } const definite = e instanceof ApiError && ([400, 403, 404].includes(e.status) || e.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(e.code)); if (definite) { intent.rejected(); onPending(false); } setUncertain(!definite); setMessage(definite ? e.message : '草稿结果待确认，请以原病例、模板、字段和请求键确认。'); }
    finally { active.current = false; if (alive.current) setBusy(false); }
  }
  return <Card title="人工报告草稿">
    {message && <Alert role="status" title={message} type="info" />}{uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>确认原草稿请求</Button>}
    <label htmlFor="report-template">不可变模板版本</label><Select id="report-template" virtual={false} style={{ width: '100%' }} value={chosen || undefined} disabled={busy || uncertain} options={detail.templates.map(t => ({ value: t.code + ':' + t.version, label: t.title + ' / ' + t.code + ' v' + t.version }))} onChange={v => { if (chosen || form.isFieldsTouched()) setNext(v); else switchTemplate(v); }} />
    {detail.templates.length === 0 && <Alert type="warning" title="未配置合成模板，不能保存" />}
    <Form<Values> form={form} initialValues={current ? { ...current.fields } : { gross: '', microscopy: '', diagnosis: '', notes: '' }} layout="vertical" disabled={busy || uncertain} onValuesChange={onDirty} onFinish={v => {
      if (!template || !detail.context.ready || busy || uncertain) return;
      const fields: Command['body']['fields'] = { gross: v.gross ?? '', microscopy: v.microscopy ?? '', diagnosis: v.diagnosis ?? '', notes: v.notes ?? '' };
      if (template.schemaCode === 'SYN-STRUCTURED-2') { if (v.sampleCount === undefined || v.manualChecked === undefined) return; fields.sampleCount = v.sampleCount; fields.manualChecked = v.manualChecked; }
      void execute({ caseId: detail.context.caseId, body: { expectedVersion: current?.version ?? -1, assignmentVersion: detail.context.assignmentVersion, confirmedCaseId: v.confirmedId, templateCode: template.code, templateVersion: template.version, fields, reason: v.reason } });
    }}>
      {(['gross', 'microscopy', 'diagnosis', 'notes'] as const).map((name, n) => <Form.Item key={name} name={name} label={['大体描述（人工）', '镜下描述（人工）', '诊断草稿（人工）', '备注（人工）'][n]} rules={[{ max: 4000 }]}><Input.TextArea rows={3} maxLength={4000} /></Form.Item>)}
      {template?.schemaCode === 'SYN-STRUCTURED-2' && <><Form.Item name="sampleCount" label="合成样本计数" preserve={false} rules={[{ required: true }, { type: 'integer', min: 0, max: 1000 }]}><InputNumber min={0} max={1000} /></Form.Item><Form.Item name="manualChecked" label="合成字段已人工核对" preserve={false} rules={[{ required: true }]}><Select virtual={false} options={[{ value: true, label: '是' }, { value: false, label: '否' }]} /></Form.Item></>}
      <Form.Item name="confirmedId" label="核对报告病例 UUID" rules={[{ validator: (_, value: unknown) => value === detail.context.caseId ? Promise.resolve() : Promise.reject(new Error('请核对当前病例 UUID')) }]}><Input maxLength={36} /></Form.Item>
      <Form.Item name="reason" label="草稿修订原因" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={2} /></Form.Item>
      <Button htmlType="submit" type="primary" disabled={!template || !detail.context.ready || busy || uncertain}>保存人工草稿</Button>
    </Form>
    <Modal open={!!next} title="切换模板并清除本地字段？" okText="清除并切换模板" cancelText="保留当前草稿" onCancel={() => setNext('')} onOk={() => switchTemplate(next)}><p>不会自动迁移诊断文本。旧的已保存修订保持原模板与字段。</p></Modal>
  </Card>;
}
