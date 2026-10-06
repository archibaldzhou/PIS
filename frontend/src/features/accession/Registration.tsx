import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Select, Space, Steps } from 'antd';
import { ApiError } from '../../api';
import { CommandIntent } from '../../shared/command';
import type { AccessionApi, ContainerInput, Draft, Encounter, RequestDetail } from './api';

interface Values { patientName: string; encounterNumber: string; clinicalHistory: string; sampledAt: string; containers: ContainerInput[] }
type Intention = { kind: 'save'; draft: Draft } | { kind: 'manual'; patientName: string; encounterNumber: string; draft: Draft } | { kind: 'submit' };
const blankContainer: ContainerInput = { site: '', laterality: 'UNKNOWN', materialQuantity: 1, fixative: '', fixedAt: null };
const localTime = (value: string | null) => value?.slice(0, 16) ?? '';
const utc = (value: string | null) => value ? new Date(value.endsWith('Z') ? value : value + ':00Z').toISOString() : null;
export function Registration({ api, scopeId, record, manual = false, canWrite = true, onDirty, onSaved, onExpired, onPending }: {
  api: AccessionApi; scopeId: string; record?: RequestDetail; manual?: boolean; canWrite?: boolean; onDirty: () => void; onSaved: () => void; onExpired: () => void; onPending: (pending: boolean) => void;
}) {
  const [form] = Form.useForm<Values>();
  const [encounter, setEncounter] = useState<Encounter | undefined>(record ? { id: record.encounterId, patientId: record.patientId, patientLabel: record.patientLabel, encounterNumber: record.encounterNumber } : undefined);
  const [number, setNumber] = useState('');
  const [matches, setMatches] = useState<Encounter[]>([]);
  const [finding, setFinding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [changed, setChanged] = useState(false);
  const [message, setMessage] = useState('');
  const [intent] = useState(() => new CommandIntent<Intention>());
  const search = useRef<AbortController | undefined>(undefined);
  const alive = useRef(true);
  const executing = useRef(false);
  const validating = useRef(false);
  useEffect(() => { alive.current = true; return () => { alive.current = false; search.current?.abort(); }; }, []);
  const dirty = () => { setChanged(true); onDirty(); };
  async function find() {
    search.current?.abort();
    const request = new AbortController(); search.current = request;
    setMatches([]); setEncounter(undefined); setFinding(true); setMessage('');
    try {
      const result = await api.encounters(scopeId, number, request.signal);
      if (search.current === request && alive.current) {
        setMatches(result); if (!result.length) setMessage('没有匹配就诊，不会自动创建患者');
      }
    } catch (error) {
      if (search.current !== request || !alive.current) return;
      if (error instanceof ApiError && error.status === 401) { onExpired(); return; }
      setMessage(error instanceof ApiError && error.status === 403 ? '无权查询此范围' : '就诊查询失败，请重试');
    } finally { if (search.current === request && alive.current) setFinding(false); }
  }
  async function execute(input: Intention) {
    if ((!encounter && !record && !manual) || executing.current) return;
    executing.current = true;
    onDirty();
    onPending(true);
    setBusy(true); setMessage('');
    try {
      const result = await intent.run(input, (saved, key) => {
        if (saved.kind === 'submit') {
          if (!record) throw new Error('Missing request');
          return api.submit(record.id, record.version, key);
        }
        if (saved.kind === 'manual') return api.createManual(scopeId, saved.patientName, saved.encounterNumber, saved.draft, key);
        return record ? api.edit(record.id, record.version, saved.draft, key)
          : api.create(scopeId, encounter?.id ?? '', saved.draft, key);
      });
      if (result && alive.current) { onPending(false); onSaved(); }
    } catch (error) {
      if (!alive.current) return;
      if (error instanceof ApiError && error.status === 401) { onExpired(); return; }
      const definite = error instanceof ApiError && ([400, 403, 404].includes(error.status)
        || (error.status === 409 && !['COMMAND_BUSY', 'HTTP_ERROR'].includes(error.code)));
      if (definite) { intent.rejected(); onPending(false); }
      setUncertain(!definite);
      setMessage(definite ? (error instanceof ApiError && error.code === 'ENCOUNTER_NUMBER_EXISTS'
        ? '该来源已有此就诊号，请通过“病理申请录入”选择已有就诊。' : error.message) + '；本地输入保留，未执行新的成功写入。'
        : '结果待确认：保留原意图和幂等键。请重试原请求确认结果，不要重新登记。');
    } finally { executing.current = false; if (alive.current) setBusy(false); }
  }
  async function save() {
    if (busy || validating.current) return;
    validating.current = true;
    try {
      const values = await form.validateFields();
      const draft: Draft = { clinicalHistory: values.clinicalHistory ?? '', sampledAt: utc(values.sampledAt),
        containers: values.containers.map(c => ({ site: c.site, laterality: c.laterality, materialQuantity: c.materialQuantity,
          fixative: c.fixative ?? '', fixedAt: utc(c.fixedAt) })) };
      await execute(manual && !record ? { kind: 'manual', patientName: values.patientName, encounterNumber: values.encounterNumber, draft }
        : { kind: 'save', draft });
    } catch { /* Ant Design renders validation failures beside fields; no request was made. */ }
    finally { validating.current = false; }
  }
  const readOnly = !canWrite || record !== undefined && record.state !== 'DRAFT';
  return <>
    <Steps className="workflow-steps" current={record?.state === 'SUBMITTED' ? 3 : encounter ? 1 : 0}
      items={[manual ? '手工登记患者与就诊' : '选择患者与就诊', '填写临床资料', '核对容器清单', '提交申请'].map(title => ({ title }))} />
    {message && <Alert className="workflow-notice" role="alert" type="warning" showIcon title={message} />}
    {uncertain && <Button disabled={busy} onClick={() => void execute({ kind: 'submit' })}>重试原请求确认结果</Button>}
    <Form<Values> form={form} layout="vertical" onValuesChange={dirty} disabled={busy || uncertain || readOnly}
      initialValues={{ clinicalHistory: record?.clinicalHistory ?? '', sampledAt: localTime(record?.sampledAt ?? null),
        containers: record ? record.containers.map(c => ({ ...c, fixedAt: localTime(c.fixedAt) })) : [{ ...blankContainer }] }}>
      <div className="workflow-columns">
        <Card title={record ? `申请 ${record.requestNumber} · 版本 ${record.version}` : '患者及申请信息'}>
          {!record && manual && <>
            <Alert type="info" showIcon title="手工登记新患者与就诊" description="保存时创建独立的合成患者、就诊和申请草稿。不会按姓名合并患者；已有就诊请使用病理申请录入。" />
            <Form.Item label="患者显示名" name="patientName" rules={[{ required: true, whitespace: true }, { max: 255 }, { pattern: /^\S(?:[\s\S]*\S)?$/, message: '请勿在名称首尾输入空格' }]}><Input maxLength={255} /></Form.Item>
            <Form.Item label="手工就诊号" name="encounterNumber" rules={[{ required: true, whitespace: true }, { max: 255 }, { pattern: /^\S(?:[\s\S]*\S)?$/, message: '请勿在就诊号首尾输入空格' }]}><Input maxLength={255} /></Form.Item>
          </>}
          {!record && !manual && <><Form.Item label="精确就诊号" htmlFor="encounter-number"><Space.Compact block><Input id="encounter-number" value={number} onChange={e => { setNumber(e.target.value); search.current?.abort(); search.current = undefined; setMatches([]); setEncounter(undefined); setFinding(false); dirty(); }} />
            <Button onClick={() => void find()} disabled={!number || finding || busy || uncertain}>查找就诊</Button></Space.Compact></Form.Item>
            <Form.Item label="选择已核对就诊" htmlFor="encounter-choice"><Select id="encounter-choice" value={encounter?.id} loading={finding}
              options={matches.map(item => ({ value: item.id, label: `${item.patientLabel} / ${item.encounterNumber}` }))}
              onChange={id => { setEncounter(matches.find(item => item.id === id)); dirty(); }} /></Form.Item></>}
          {(!manual || record) && <div className="field-pair"><Form.Item label="患者标识"><Input disabled value={encounter?.patientId ?? ''} /></Form.Item>
            <Form.Item label="患者 / 就诊"><Input disabled value={encounter ? `${encounter.patientLabel} / ${encounter.encounterNumber}` : ''} /></Form.Item></div>}
          <p>业务类型：常规组织（合成开发范围）；时间输入/展示均为 UTC。</p>
          <Form.Item label="临床诊断与病史" name="clinicalHistory" rules={[{ max: 4000 }]}><Input.TextArea rows={4} /></Form.Item>
          <Form.Item label="手术 / 采样时间（UTC）" name="sampledAt"><Input type="datetime-local" /></Form.Item>
          <Alert type="info" showIcon title="开发重复申请策略" description="同一就诊已有提交申请时阻断再次提交，不提供理由绕过。草稿可保存不完整资料；提交须补齐病史、采样与固定信息。" />
        </Card>
        <Card title="容器清单与校验">
          <Form.List name="containers">{(fields, { add, remove }) => <>{fields.map((field, index) => <section className="container-fields" key={field.key}>
            <strong>容器 {index + 1}{record ? ` · ${record.containers[index]?.id ?? ''}` : ''}</strong>
            <Form.Item label="部位" name={[field.name, 'site']} rules={[{ required: true, whitespace: true }, { max: 255 }]}><Input /></Form.Item>
            <Form.Item label="侧别" name={[field.name, 'laterality']}><Select options={['NONE', 'LEFT', 'RIGHT', 'BILATERAL', 'UNKNOWN'].map(value => ({ value, label: value }))} /></Form.Item>
            <Form.Item label="材料数量" name={[field.name, 'materialQuantity']} rules={[{ required: true }]}><InputNumber min={1} max={999} precision={0} /></Form.Item>
            <Form.Item label="固定液" name={[field.name, 'fixative']} rules={[{ max: 128 }]}><Input /></Form.Item>
            <Form.Item label="固定开始时间（UTC）" name={[field.name, 'fixedAt']}><Input type="datetime-local" /></Form.Item>
            {!record && fields.length > 1 && <Button onClick={() => { remove(field.name); dirty(); }}>移除未登记容器 {index + 1}</Button>}
          </section>)}{!record && <Button disabled={fields.length >= 20 || busy || uncertain} onClick={() => { add({ ...blankContainer }); dirty(); }}>添加未登记容器</Button>}</>}</Form.List>
          {record && <p>已登记容器保留身份，本版不可新增/移除条目。</p>}
          <Space wrap><Button type="primary" disabled={(!manual && !encounter) || busy || uncertain || readOnly} onClick={() => void save()}>保存草稿</Button>
            <Button disabled={!record || changed || busy || uncertain || readOnly} onClick={() => void execute({ kind: 'submit' })}>核对后提交</Button></Space>
        </Card>
      </div>
    </Form>
  </>;
}
