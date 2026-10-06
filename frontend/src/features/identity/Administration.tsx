import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Checkbox, Form, Input, Modal, Select, Space, Spin, Switch, Table, Tabs, Tag, Typography } from 'antd';
import { ApiError } from '../../api';
import { CommandIntent } from '../../shared/command';
import { change, changePassword, identityApi, type Assignment, type Catalog, type Change, type Event, type Hospital, type Scope, type User, type UserPage } from './api';
import '../../workflow.css';

const required = [{ required: true, message: '请填写此项' }];
const passwordRules = [...required, { validator: (_: unknown, value: unknown) => typeof value === 'string' && new TextEncoder().encode(value).length >= 16 && new TextEncoder().encode(value).length <= 72 ? Promise.resolve() : Promise.reject(new Error('密码须为16–72个UTF-8字节')) }];
function message(error: unknown) { return error instanceof Error ? error.message : '操作失败，请刷新核对'; }
function useDirty(dirty: boolean) {
  useEffect(() => { if (!dirty) return; const prevent = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; }; window.addEventListener('beforeunload', prevent); return () => window.removeEventListener('beforeunload', prevent); }, [dirty]);
}
export function PasswordChange({ onDone, onLogout }: { onDone: () => void; onLogout: () => void }) {
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const active = useRef(false);
  const [uncertain, setUncertain] = useState(false);
  return <main className="page"><Card title="首次登录或密码重置 · 修改密码" style={{ width: 480 }}>
    <p>修改成功后，请使用新密码重新登录。</p>
    {error && <Alert type="error" title={error} role="alert" />}
    <Form layout="vertical" disabled={busy || uncertain} onFinish={async (v: { oldPassword: string; newPassword: string }) => {
      if (active.current) return; active.current = true; setBusy(true); setError('');
      try { await changePassword(v.oldPassword, v.newPassword); onDone(); }
      catch (e) { setError(message(e)); if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500) setUncertain(true); }
      finally { setBusy(false); active.current = false; }
    }}>
      <Form.Item name="oldPassword" label="当前密码" rules={required}><Input.Password autoComplete="current-password" /></Form.Item>
      <Form.Item name="newPassword" label="新密码" rules={passwordRules}><Input.Password autoComplete="new-password" /></Form.Item>
      <Form.Item name="confirm" label="确认新密码" dependencies={['newPassword']} rules={[...required, ({ getFieldValue }) => ({ validator: (_, v) => v === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error('两次密码不一致')) })]}><Input.Password autoComplete="new-password" /></Form.Item>
      <Button type="primary" htmlType="submit" loading={busy}>修改密码</Button>
    </Form>
    {uncertain && <Alert type="warning" title="结果待确认，请退出后尝试使用新密码登录；不要重复修改。" />}
    <Button onClick={onLogout}>退出登录</Button>
  </Card></main>;
}
export function Administration({ currentUserId, onClose, onExpired }: { currentUserId: string; onClose: () => void; onExpired: () => void }) {
  const [hospitals, setHospitals] = useState<Hospital[]>(); const [selected, setSelected] = useState(''); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  const [dirty, setDirty] = useState(false); useDirty(dirty);
  const leave = (action: () => void) => { if (!dirty || window.confirm('有未保存输入或待确认操作。确认离开？')) { setDirty(false); action(); } };
  useEffect(() => { const controller = new AbortController(); void identityApi.hospitals(controller.signal).then(rows => { if (!controller.signal.aborted) { setHospitals(rows); setSelected(rows[0]?.id ?? ''); setError(''); } }).catch(e => { if (!controller.signal.aborted) { setError(message(e)); if (e instanceof ApiError && e.status === 401) onExpired(); } }); return () => controller.abort(); }, [reload, onExpired]);
  return <main className="workflow-content" style={{ minHeight: '100vh', background: '#eef5f8' }}>
    <header className="workflow-header"><Typography.Title level={1}>后台管理</Typography.Title><Button disabled={dirty} onClick={() => leave(onClose)}>返回工作区</Button></header>
    <Alert type="info" showIcon title="用户、权限和工作范围统一配置" description="配置保存后在服务端生效；修改账号权限会使该账号原有会话失效。仅供合成数据演练。" />
    {error && <Alert type="error" title={error} action={<Button onClick={() => setReload(v => v + 1)}>重试</Button>} />}
    {!hospitals && !error && <Spin />}
    {hospitals?.length === 0 && <Alert type="warning" title="没有后台管理或审计访问权限" />}
    {hospitals && hospitals.length > 0 && <Space style={{ margin: '16px 0' }}><span>管理医院</span><Select disabled={dirty} aria-label="管理医院" style={{ minWidth: 240 }} value={selected} options={hospitals.map(h => ({ value: h.id, label: h.name }))} onChange={id => leave(() => setSelected(id))} /></Space>}
    {hospitals?.filter(h => h.id === selected).map(h => <HospitalAdmin key={h.id} hospital={h} currentUserId={currentUserId} onDirty={setDirty} onExpired={onExpired} />)}
  </main>;
}
interface UserForm { username: string; displayName: string; employeeNumber: string; enabled: boolean; initialPassword?: string; defaultScopeId: string; assignments: Assignment[]; reason: string }
interface ScopeForm { campusId: string; departmentId: string; sourceId: string; name: string; enabled: boolean; reason: string }
interface OtherForm { kind?: 'CAMPUS' | 'DEPARTMENT' | 'SOURCE'; code?: string; name?: string; password?: string; reason: string }
function HospitalAdmin({ hospital, currentUserId, onDirty, onExpired }: { hospital: Hospital; currentUserId: string; onDirty: (v: boolean) => void; onExpired: () => void }) {
  const [catalog, setCatalog] = useState<Catalog>(); const [users, setUsers] = useState<UserPage>(); const [events, setEvents] = useState<Event[]>();
  const [query, setQuery] = useState(''); const [page, setPage] = useState(1); const [auditPage, setAuditPage] = useState(1); const [revision, setRevision] = useState(0);
  const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [loading, setLoading] = useState(false);
  const [editor, setEditor] = useState<'user' | 'scope' | 'organization' | 'password'>(); const [selectedUser, setSelectedUser] = useState<User>(); const [selectedScope, setSelectedScope] = useState<Scope>();
  const [form] = Form.useForm<UserForm>(); const [scopeForm] = Form.useForm<ScopeForm>(); const [otherForm] = Form.useForm<OtherForm>();
  const assignments = Form.useWatch('assignments', form) ?? [];
  const [intent] = useState(() => new CommandIntent<Change>()); const [busy, setBusy] = useState(false); const [pending, setPending] = useState(false);
  const detailAbort = useRef<AbortController | undefined>(undefined); const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; detailAbort.current?.abort(); }; }, []);
  useEffect(() => { const controller = new AbortController(); setLoading(true); setUsers(undefined); setEvents(undefined); setCatalog(undefined); setError('');
    void Promise.all([hospital.manage ? identityApi.catalog(hospital.id, controller.signal) : Promise.resolve(undefined), hospital.manage ? identityApi.users(hospital.id, query, page, controller.signal) : Promise.resolve(undefined), identityApi.events(hospital.id, auditPage, controller.signal)])
      .then(([c, u, e]) => { if (!controller.signal.aborted) { setCatalog(c); setUsers(u); setEvents(e); } })
      .catch(e => { if (!controller.signal.aborted) { setError(message(e)); if (e instanceof ApiError && e.status === 401) onExpired(); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); }); return () => controller.abort();
  }, [hospital.id, hospital.manage, query, page, auditPage, revision, onExpired]);
  async function submit(input: Change) {
    setBusy(true); setError(''); setNotice(''); onDirty(true);
    try { const result = await intent.run(input, change); if (result && mounted.current) { setEditor(undefined); form.resetFields(); otherForm.resetFields(); scopeForm.resetFields(); onDirty(false); setNotice('服务器已保存配置。账号权限变更后需重新登录。'); setRevision(v => v + 1); } }
    catch (e) { if (e instanceof ApiError && e.status >= 400 && e.status < 500 && !['COMMAND_BUSY', 'COMMAND_TIMEOUT'].includes(e.code)) intent.rejected(); if (mounted.current) { setError(message(e)); if (e instanceof ApiError && e.status === 401) onExpired(); } }
    finally { if (mounted.current) { setBusy(false); setPending(intent.unresolved); } }
  }
  async function openUser(id: string, kind: 'user' | 'password') {
    detailAbort.current?.abort(); const controller = new AbortController(); detailAbort.current = controller; setError('');
    try { const u = await identityApi.user(hospital.id, id, controller.signal); if (controller.signal.aborted) return; setSelectedUser(u); form.resetFields(); otherForm.resetFields(); form.setFieldsValue({ ...u, defaultScopeId: u.defaultScopeId ?? undefined, initialPassword: undefined, reason: '' }); setEditor(kind); }
    catch (e) { if (!controller.signal.aborted) setError(message(e)); }
  }
  function close() { if (pending || busy) return; if (window.confirm('关闭并丢弃本次未保存输入？')) { setEditor(undefined); form.resetFields(); otherForm.resetFields(); scopeForm.resetFields(); onDirty(false); } }
  const scopeOptions = catalog?.scopes.filter(s => s.enabled).map(s => ({ value: s.id, label: s.name })) ?? [];
  const optionList = (values: { id: string; name: string }[] = []) => values.map(v => ({ value: v.id, label: v.name }));
  const newUser = () => { detailAbort.current?.abort(); setSelectedUser(undefined); form.resetFields(); form.setFieldsValue({ enabled: true, assignments: [], reason: '' }); setEditor('user'); };
  return <>
    {error && <Alert type="error" title={error} role="alert" action={!editor && <Button onClick={() => setRevision(v => v + 1)}>重新加载</Button>} />}
    {notice && <Alert type="success" title={notice} role="status" />}
    <Tabs items={[
      ...(hospital.manage ? [{ key: 'users', label: '用户管理', children: <Card title="人员账号" extra={<Button type="primary" disabled={!catalog} onClick={newUser}>添加用户</Button>}>
        <Input.Search aria-label="查找用户" placeholder="姓名、工号或登录名" allowClear maxLength={128} onSearch={v => { setQuery(v); setPage(1); }} style={{ maxWidth: 360, marginBottom: 16 }} />
        <Table rowKey="id" loading={loading} dataSource={users?.items} pagination={{ current: page, pageSize: 20, total: users?.total ?? 0, showSizeChanger: false, onChange: setPage }} columns={[
          { title: '姓名', dataIndex: 'displayName' }, { title: '工号', dataIndex: 'employeeNumber' }, { title: '登录名', dataIndex: 'username' },
          { title: '状态', render: (_, u: User) => <Space><Tag color={u.enabled ? 'green' : 'default'}>{u.enabled ? '启用' : '停用'}</Tag>{u.passwordChangeRequired && <Tag>待修改密码</Tag>}</Space> },
          { title: '操作', render: (_, u: User) => <Space><Button disabled={u.id === currentUserId} onClick={() => void openUser(u.id, 'user')}>编辑权限</Button><Button disabled={u.id === currentUserId} onClick={() => void openUser(u.id, 'password')}>重置密码</Button>{u.id === currentUserId && <span>当前账号</span>}</Space> },
        ]} />
      </Card> }, { key: 'roles', label: '角色与权限', children: <Card title="病理岗位角色模板"><p>选择角色会填入推荐权限，可对每位用户按工作范围调整。专业操作还须显式核验资格，状态及职责分离规则继续生效。</p>
        <Table rowKey="code" loading={loading} dataSource={catalog?.roles} pagination={false} columns={[{ title: '角色', dataIndex: 'name' }, { title: '可分配权限', render: (_, r) => r.available ? r.permissions.map(p => catalog?.permissions.find(x => x.code === p)?.name ?? p).join('、') : '尚无业务模块，暂不开放' }]} /></Card> },
      { key: 'scopes', label: '组织与工作范围', children: <Card title="工作范围" extra={<Space><Button onClick={() => { otherForm.resetFields(); setEditor('organization'); }}>添加组织</Button><Button type="primary" disabled={!catalog} onClick={() => { setSelectedScope(undefined); scopeForm.resetFields(); scopeForm.setFieldsValue({ enabled: true }); setEditor('scope'); }}>添加工作范围</Button></Space>}>
        <p>工作范围由院区、科室和来源组成。人员的默认范围在用户编辑中设置；正常工作无需选择授权范围。</p>
        <Table rowKey="id" loading={loading} dataSource={catalog?.scopes} pagination={{ pageSize: 20 }} columns={[{ title: '名称', dataIndex: 'name' }, { title: '院区', render: (_, s) => catalog?.campuses.find(c => c.id === s.campusId)?.name }, { title: '科室', render: (_, s) => catalog?.departments.find(c => c.id === s.departmentId)?.name }, { title: '来源', render: (_, s) => catalog?.sources.find(c => c.id === s.sourceId)?.name }, { title: '状态', render: (_, s) => s.enabled ? '启用' : '停用' }, { title: '操作', render: (_, s: Scope) => <Button onClick={() => { setSelectedScope(s); scopeForm.resetFields(); scopeForm.setFieldsValue({ ...s, reason: '' }); setEditor('scope'); }}>编辑范围</Button> }]} />
      </Card> }] : []),
      { key: 'audit', label: '管理审计', children: <Card title="权限与账号变更记录"><Table rowKey="id" loading={loading} dataSource={events} expandable={{ expandedRowRender: event => <pre style={{ whiteSpace: 'pre-wrap' }}>{event.changeSet}</pre> }} pagination={false} scroll={{ x: 1000 }} columns={[{ title: '时间', dataIndex: 'occurredAt', render: (v: string) => new Date(v).toLocaleString() }, { title: '动作', dataIndex: 'action' }, { title: '操作者ID', dataIndex: 'actorId' }, { title: '对象ID', dataIndex: 'targetId' }, { title: '结果版本', dataIndex: 'version' }, { title: '变更原因', dataIndex: 'reason' }]} /><Space><Button disabled={auditPage === 1 || loading} onClick={() => setAuditPage(v => v - 1)}>上一页</Button><span>第 {auditPage} 页</span><Button disabled={events?.length !== 20 || loading} onClick={() => setAuditPage(v => v + 1)}>下一页</Button></Space></Card> },
    ]} />
    <Modal open={!!editor} title={editor === 'user' ? selectedUser ? '编辑用户权限' : '添加用户' : editor === 'scope' ? '配置工作范围' : editor === 'password' ? '重置用户密码' : '添加组织'} width={editor === 'user' ? 850 : 580} onCancel={close} closable={!pending && !busy} maskClosable={false} footer={null} destroyOnHidden>
      {error && <Alert type="error" title={error} role="alert" />}
      {pending && <Alert type="warning" title="原请求结果待确认，输入已锁定。请使用原请求重试。" action={<Button loading={busy} onClick={() => void submit({ path: '', method: 'POST', body: {} })}>确认原请求结果</Button>} />}
      {editor === 'user' && catalog && <Form form={form} layout="vertical" disabled={busy || pending} onValuesChange={() => onDirty(true)} onFinish={values => void submit({ path: `${hospital.id}/users${selectedUser ? '/' + selectedUser.id : ''}`, method: selectedUser ? 'PUT' : 'POST', body: { ...values, expectedVersion: selectedUser?.version ?? 0, initialPassword: selectedUser ? null : values.initialPassword, assignments: values.assignments.map(a => ({ ...a, validUntil: new Date(a.validUntil).toISOString(), qualificationVerified: a.qualificationVerified ?? false })) } })}>
        <Space align="start" wrap><Form.Item name="username" label="登录名" rules={[...required, { pattern: /^[a-z0-9][a-z0-9._-]{2,63}$/, message: '3–64位小写字母、数字、点、下划线或短横线' }]}><Input disabled={!!selectedUser || busy || pending} autoComplete="off" /></Form.Item><Form.Item name="displayName" label="姓名" rules={required}><Input maxLength={255} /></Form.Item><Form.Item name="employeeNumber" label="工号" rules={required}><Input maxLength={64} /></Form.Item><Form.Item name="enabled" label="账号启用" valuePropName="checked"><Switch /></Form.Item></Space>
        {!selectedUser && <Form.Item name="initialPassword" label="初始密码（首次登录必须修改）" rules={passwordRules}><Input.Password autoComplete="new-password" /></Form.Item>}
        <Form.List name="assignments" rules={[{ validator: (_, value: unknown[]) => value?.length > 0 ? Promise.resolve() : Promise.reject(new Error('至少配置一个工作范围')) }]}>{(fields, { add, remove }, { errors }) => <>
          {fields.map(field => <Card key={field.key} size="small" title={`工作范围 ${field.name + 1}`} style={{ marginBottom: 12 }} extra={<Button disabled={busy || pending} onClick={() => remove(field.name)}>移除此范围</Button>}>
            <Form.Item name={[field.name, 'scopeId']} label="工作范围" rules={required}><Select options={scopeOptions} /></Form.Item>
            <Form.Item name={[field.name, 'roles']} label="岗位角色" rules={required}><Select mode="multiple" options={catalog.roles.map(r => ({ value: r.code, label: r.name, disabled: !r.available }))} onChange={(roles: string[]) => { const permissions = [...new Set(['READ', ...catalog.roles.filter(r => roles.includes(r.code)).flatMap(r => r.permissions)])]; form.setFieldValue(['assignments', field.name, 'permissions'], permissions); form.setFieldValue(['assignments', field.name, 'qualificationVerified'], false); }} /></Form.Item>
            <Form.Item name={[field.name, 'permissions']} label="功能权限" rules={required}><Checkbox.Group options={catalog.permissions.map(p => ({ label: p.name + (p.professional ? '（专业）' : ''), value: p.code }))} /></Form.Item>
            <Form.Item name={[field.name, 'qualificationVerified']} valuePropName="checked"><Checkbox>已核验该人员的专业操作资格（合成演练）</Checkbox></Form.Item>
            <Form.Item name={[field.name, 'validUntil']} label="权限截止时间（含时区，如 2027-10-07T00:00:00+08:00）" rules={[...required, { validator: (_, v: string) => Number.isFinite(Date.parse(v)) && Date.parse(v) > Date.now() + 60000 ? Promise.resolve() : Promise.reject(new Error('请填写有效的未来时间')) }]}><Input /></Form.Item>
          </Card>)}
          <Form.ErrorList errors={errors} /><Button disabled={fields.length >= 10 || busy || pending} onClick={() => { add({ roles: ['RECEPTION'], permissions: catalog.roles.find(r => r.code === 'RECEPTION')?.permissions ?? ['READ'], qualificationVerified: false, validUntil: new Date(Date.now() + 365 * 86400000).toISOString() }); onDirty(true); }}>添加人员工作范围</Button>
        </>}</Form.List>
        <Form.Item name="defaultScopeId" label="默认工作范围" rules={required}><Select options={scopeOptions.filter(s => assignments.some(a => a?.scopeId === s.value))} /></Form.Item>
        <Form.Item name="reason" label="变更原因" rules={required}><Input.TextArea maxLength={1000} /></Form.Item><Button type="primary" htmlType="submit" loading={busy}>保存用户及权限</Button>
      </Form>}
      {editor === 'scope' && catalog && <Form form={scopeForm} layout="vertical" disabled={busy || pending} onValuesChange={() => onDirty(true)} onFinish={v => void submit({ path: `${hospital.id}/scopes${selectedScope ? '/' + selectedScope.id : ''}`, method: selectedScope ? 'PUT' : 'POST', body: { ...v, expectedVersion: selectedScope?.version ?? 0 } })}>
        <Form.Item name="name" label="范围名称" rules={required}><Input maxLength={255} /></Form.Item>
        <Form.Item name="campusId" label="院区" rules={required}><Select disabled={!!selectedScope || busy || pending} options={optionList(catalog.campuses)} /></Form.Item><Form.Item name="departmentId" label="科室" rules={required}><Select disabled={!!selectedScope || busy || pending} options={optionList(catalog.departments)} /></Form.Item><Form.Item name="sourceId" label="申请来源" rules={required}><Select disabled={!!selectedScope || busy || pending} options={optionList(catalog.sources)} /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item><Form.Item name="reason" label="变更原因" rules={required}><Input.TextArea maxLength={1000} /></Form.Item><Button type="primary" htmlType="submit" loading={busy}>保存工作范围</Button>
      </Form>}
      {editor === 'organization' && <Form form={otherForm} layout="vertical" disabled={busy || pending} onValuesChange={() => onDirty(true)} onFinish={v => void submit({ path: `${hospital.id}/organizations`, method: 'POST', body: v })}>
        <Form.Item name="kind" label="组织类型" rules={required}><Select options={[{ value: 'CAMPUS', label: '院区' }, { value: 'DEPARTMENT', label: '科室' }, { value: 'SOURCE', label: '申请来源系统' }]} /></Form.Item><Form.Item name="code" label="编码" rules={required}><Input maxLength={128} /></Form.Item><Form.Item name="name" label="名称" rules={required}><Input maxLength={255} /></Form.Item><Form.Item name="reason" label="创建原因" rules={required}><Input.TextArea maxLength={1000} /></Form.Item><Button type="primary" htmlType="submit" loading={busy}>保存组织</Button>
      </Form>}
      {editor === 'password' && selectedUser && <Form form={otherForm} layout="vertical" disabled={busy || pending} onValuesChange={() => onDirty(true)} onFinish={(v: OtherForm) => void submit({ path: `${hospital.id}/users/${selectedUser.id}/password`, method: 'POST', body: { ...v, expectedVersion: selectedUser.version } })}>
        <p>用户：{selectedUser.displayName}（{selectedUser.username}）</p><Form.Item name="password" label="临时密码（下次登录必须修改）" rules={passwordRules}><Input.Password autoComplete="new-password" /></Form.Item><Form.Item name="reason" label="重置原因" rules={required}><Input.TextArea maxLength={1000} /></Form.Item><Button type="primary" htmlType="submit" loading={busy}>重置密码并使旧会话失效</Button>
      </Form>}
    </Modal>
  </>;
}
