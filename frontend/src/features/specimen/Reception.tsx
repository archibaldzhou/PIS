import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Select, Space, Table, Tag } from 'antd';
import { ApiError } from '../../api';
import { CommandIntent } from '../../shared/command';
import { ReadController } from '../../shared/workflow';
import { ReadPanel } from '../../shared/WorkflowElements';
import { loadReception, receptionCommand, type ReceptionCommand } from './api';

interface Values { patientId: string; encounterNumber: string; containerIds: string; category: string; reason: string }
const labels: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '待接收', EXCEPTION: '接收异常', RECEIVED: '已接收', RETURNED: '已退回' };
export function Reception({ id, onDirty, onClean, onPending, onExpired }: {
  id: string; onDirty: () => void; onClean: () => void; onPending: (v: boolean) => void; onExpired: () => void;
}) {
  const [form] = Form.useForm<Values>();
  const [reader] = useState(() => new ReadController(async (id: string, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await loadReception(id, signal) }; }
    catch (error) { if (error instanceof ApiError && error.status === 401) onExpired(); throw error; }
  }));
  const state = useSyncExternalStore(reader.subscribe, reader.getSnapshot);
  const [intent] = useState(() => new CommandIntent<ReceptionCommand>());
  const [busy, setBusy] = useState(false); const [uncertain, setUncertain] = useState(false); const [message, setMessage] = useState('');
  const active = useRef(false); const alive = useRef(true); const original = useRef<ReceptionCommand | undefined>(undefined);
  useEffect(() => { alive.current = true; void reader.run(id); return () => { alive.current = false; reader.stop(); }; }, [id, reader]);
  async function execute(command: ReceptionCommand) {
    if (active.current) return; active.current = true; setBusy(true); onDirty(); onPending(true); setMessage(''); original.current = command;
    try {
      const result = await intent.run(command, (saved, key) => receptionCommand(id, saved, key));
      if (result && alive.current) { setUncertain(false); onPending(false); onClean(); form.resetFields(); setMessage('服务器已确认操作，请查看最新状态和处理轨迹。'); await reader.run(id); }
    } catch (error) {
      if (!alive.current) return;
      if (error instanceof ApiError && error.status === 401) { onExpired(); return; }
      const definite = error instanceof ApiError && ([400, 403, 404].includes(error.status) || (error.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(error.code)));
      if (definite) { intent.rejected(); onPending(false); }
      setUncertain(!definite); setMessage(definite ? error.message : '结果待确认，请保留原请求重试；不能视为已经接收。');
    } finally { active.current = false; if (alive.current) setBusy(false); }
  }
  async function act(action: ReceptionCommand['action']) {
    if (busy || uncertain || active.current || state.status !== 'ready') return;
    try {
      const values = await form.validateFields(action === 'receive' ? ['patientId', 'encounterNumber', 'containerIds'] : action === 'exception' ? ['reason', 'category'] : ['reason']);
      const expectedVersion = state.data.request.version;
      if (action === 'receive') await execute({ action, body: { expectedVersion, patientId: values.patientId, encounterNumber: values.encounterNumber,
        containerIds: values.containerIds.split(/\s+/).filter(Boolean) } });
      else if (action === 'exception') await execute({ action, body: { expectedVersion, category: values.category, reason: values.reason } });
      else await execute({ action, body: { expectedVersion, reason: values.reason } });
    } catch { /* Form validation is rendered inline. */ }
  }
  return <>
    {message && <Alert role="status" className="workflow-notice" type="info" title={message} />}
    {uncertain && <Button disabled={busy} onClick={() => { if (original.current) void execute(original.current); }}>重试原接收请求</Button>}
    <Button disabled={busy || uncertain} onClick={() => { onClean(); form.resetFields(); void reader.run(id); }}>放弃本地输入并刷新接收状态</Button>
    <ReadPanel state={state}>{data => <>
      <section className="identity-strip" aria-label="接收申请身份"><strong>{data.request.requestNumber}</strong><Tag>{labels[data.request.state]}</Tag>
        <span>{data.request.patientLabel}</span><span>患者标识：{data.request.patientId}</span><span>就诊号：{data.request.encounterNumber}</span><span>版本 {data.request.version}</span>
        {data.caseNumber && <strong>病理号：{data.caseNumber}</strong>}
      </section>
      {data.request.state === 'EXCEPTION' && <Alert type="error" role="alert" title="接收已阻断，进入异常处理；尚未生成病例" />}
      <Form<Values> form={form} layout="vertical" onValuesChange={onDirty} disabled={busy || uncertain} initialValues={{ category: 'INFORMATION' }}>
        <div className="workflow-columns"><Card title="标本接收核对">
          <Form.Item label="实物患者 UUID" name="patientId" rules={[{ required: true }, { pattern: /^[0-9a-fA-F-]{36}$/ }]}><Input maxLength={36} disabled={data.request.state !== 'SUBMITTED' || busy || uncertain} /></Form.Item>
          <Form.Item label="实物就诊号" name="encounterNumber" rules={[{ required: true }, { max: 255 }]}><Input disabled={data.request.state !== 'SUBMITTED' || busy || uncertain} /></Form.Item>
          <Form.Item label="逐个容器 UUID（空白分隔）" name="containerIds" rules={[{ required: true }]}><Input.TextArea rows={3} disabled={data.request.state !== 'SUBMITTED' || busy || uncertain} /></Form.Item>
          <Button type="primary" disabled={busy || uncertain || data.request.state !== 'SUBMITTED'} onClick={() => void act('receive')}>核对并接收</Button>
          <p>不符将记录身份或数量异常并阻断接收；重复扫描不会自动去重。仅合成数据，不连接扫码设备。</p>
        </Card><Card title="计划容器与异常处理">
          {data.request.containers.map(c => <p key={c.id}><code>{c.id}</code><br />{c.site} / {c.laterality} / 材料数量 {c.materialQuantity}<br />{c.fixative} / {c.fixedAt ?? '未记录固定时间'} UTC</p>)}
          <Form.Item label="异常类别" name="category" rules={[{ required: true }]}><Select options={['INFORMATION', 'IDENTITY', 'QUANTITY'].map(value => ({ value, label: value }))} /></Form.Item>
          <Form.Item label="异常事实 / 补充说明 / 退回原因" name="reason" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea rows={3} /></Form.Item>
          <Space wrap>
            <Button disabled={busy || uncertain || data.request.state !== 'SUBMITTED'} onClick={() => void act('exception')}>登记异常</Button>
            <Button disabled={busy || uncertain || data.request.state !== 'EXCEPTION' || data.events.find(e => e.action === 'EXCEPTION')?.category !== 'INFORMATION'} onClick={() => void act('resolve')}>补充后恢复待接收</Button>
            <Button danger disabled={busy || uncertain || data.request.state !== 'EXCEPTION'} onClick={() => void act('return')}>记录退回</Button>
            <Button disabled title="未批准条件接收策略">有条件接收</Button>
          </Space><p>退回仅记录软件状态，不代表实物已交接。身份/数量异常不能自行修正并恢复。</p>
        </Card></div>
      </Form>
      <Card title="处理轨迹（最近100条；完整历史保留）"><Table rowKey="id" dataSource={data.events} pagination={false} scroll={{ x: 700 }} columns={[
        { title: '版本', dataIndex: 'requestVersion' }, { title: '动作', dataIndex: 'action' }, { title: '类别', dataIndex: 'category' },
        { title: '说明', dataIndex: 'reason' }, { title: '操作者', dataIndex: 'actorId' }, { title: '时间 UTC', dataIndex: 'occurredAt' },
      ]} /></Card>
    </>}</ReadPanel>
  </>;
}
