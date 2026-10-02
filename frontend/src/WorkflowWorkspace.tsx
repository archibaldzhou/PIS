import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Modal, Select, Space, Tag, Typography } from 'antd';
import { ApiError } from './api';
import { RequestList } from './features/accession/RequestList';
import { Registration } from './features/accession/Registration';
import { accessionApi, type AccessionApi, type RequestDetail } from './features/accession/api';
import { ReadController, type RequestReader, type RequestSummary } from './shared/workflow';
import { ReadPanel } from './shared/WorkflowElements';
import './workflow.css';
import { Labels } from './features/labels/Labels';
import { Reception } from './features/specimen/Reception';

const pages = { requests: '申请单查询', registration: '病理申请录入', reception: '标本接收与异常', labels: '标签打印与重打' };
type Page = keyof typeof pages;
export function WorkflowWorkspace({ onClose, onLogout, onExpired, api = accessionApi }: {
  onClose: () => void; onLogout: () => void; onExpired: () => void; api?: AccessionApi;
}) {
  const [page, setPage] = useState<Page>('requests');
  const [scope, setScope] = useState('');
  const [record, setRecord] = useState<RequestSummary>();
  const [dirty, setDirty] = useState(false);
  const [notice, setNotice] = useState('');
  const [unresolved, setUnresolved] = useState(false);
  const [navWarning, setNavWarning] = useState('');
  const [confirming, setConfirming] = useState(false);
  const pending = useRef<(() => void) | undefined>(undefined);
  const [scopes] = useState(() => new ReadController(async (_: null, signal: AbortSignal) => {
    try { return { status: 'ready' as const, data: await api.scopes(signal) }; }
    catch (error) { if (error instanceof ApiError && error.status === 401) onExpired(); throw error; }
  }));
  const scopeState = useSyncExternalStore(scopes.subscribe, scopes.getSnapshot);
  const [details] = useState(() => new ReadController<RequestDetail, string>(async (id, signal) => {
    try { return { status: 'ready', data: await api.detail(id, signal) }; }
    catch (error) { if (error instanceof ApiError && error.status === 401) onExpired(); throw error; }
  }));
  const detailState = useSyncExternalStore(details.subscribe, details.getSnapshot);
  useEffect(() => { void scopes.run(null); return () => { scopes.stop(); details.stop(); }; }, [scopes, details]);
  useEffect(() => {
    if (!dirty) return;
    const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', prevent);
    return () => window.removeEventListener('beforeunload', prevent);
  }, [dirty]);
  function leave(action: () => void, exiting = false) {
    if (pending.current) return;
    if (unresolved && !exiting) { setNavWarning('原请求结果尚未确认，请先使用页面上的原请求确认结果。'); return; }
    if (!dirty) { action(); return; }
    pending.current = action; setConfirming(true);
  }
  function navigate(next: Page, selected?: RequestSummary) {
    if (page === next && record?.id === selected?.id) return;
    leave(() => {
      details.stop(); setDirty(false); setRecord(selected); setPage(next); setNotice('');
      if (selected && (next === 'registration' || next === 'labels')) void details.run(selected.id);
    });
  }
  const reader: RequestReader = { async search(filter, signal) {
    try { return await api.search(scope, filter, signal); }
    catch (error) { if (error instanceof ApiError && error.status === 401) onExpired(); throw error; }
  } };
  const registration = (detail?: RequestDetail) => <Registration key={detail ? `${detail.id}:${detail.version}` : scope}
    api={api} scopeId={scope} record={detail} onDirty={() => setDirty(true)} onExpired={onExpired}
    onPending={value => { setUnresolved(value); if (!value) setNavWarning(''); }}
    onSaved={() => { setDirty(false); setRecord(undefined); setPage('requests'); setNotice('服务器已确认操作；列表将重新查询。'); }} />;
  return <main className="workflow-shell">
    <aside className="workflow-sidebar"><strong>衡知病理 · PIS</strong><p>申请登记 · 开发工作区</p>
      <nav aria-label="申请登记页面">{Object.entries(pages).map(([key, label]) => {
        const target = key as Page;
        return <Button key={key} type={page === target ? 'primary' : 'text'} aria-current={page === target ? 'page' : undefined}
          onClick={() => navigate(target)}>{label}</Button>;
      })}</nav><p>仅合成数据开发<br />非临床使用</p>
    </aside>
    <section className="workflow-content">
      <header className="workflow-header"><Typography.Title level={1}>{pages[page]}</Typography.Title>
        <Space wrap><Button onClick={() => leave(onClose)}>返回工程验证</Button><Button onClick={() => leave(onLogout, true)}>退出登录</Button></Space>
      </header>
      <Alert type="warning" showIcon title="合成数据开发工作流 · 非临床使用"
        description="采用新编开发规格。申请功能需要服务端显式开启和独立授权。请勿输入真实患者资料。" />
      <div className="workflow-status" role="status"><Tag color={dirty ? 'orange' : 'default'}>{dirty ? '本地输入尚未保存' : '没有未保存的本地输入'}</Tag></div>
      {notice && <Alert className="workflow-notice" type="success" role="status" title={notice} />}
      {navWarning && <Alert className="workflow-notice" type="warning" role="alert" title={navWarning} />}
      <ReadPanel state={scopeState}>{values => <div className="scope-picker">
        <label htmlFor="workflow-scope">授权工作范围</label>
        <Select id="workflow-scope" value={scope || undefined} placeholder="请选择已授权范围" style={{ minWidth: 240, maxWidth: '100%' }}
          options={values.map(s => ({ value: s.id, label: s.name }))} onChange={value => leave(() => {
            details.stop(); setScope(value); setRecord(undefined); setDirty(false); setPage('requests'); setNotice('');
          })} />
        {values.length === 0 && <Alert type="warning" title="没有申请业务授权；病例权限不会自动授予申请权限" />}
      </div>}</ReadPanel>
      {scopeState.status !== 'ready' && <Button onClick={() => void scopes.run(null)}>重试加载范围</Button>}
      {scope && page === 'requests' && <RequestList key={scope} reader={reader} onSelect={selected => navigate('registration', selected)} />}
      {scope && page === 'registration' && (record ? <><Button onClick={() => navigate('reception', record)}>处理此申请接收与异常</Button><Button onClick={() => navigate('labels', record)}>处理此申请标签</Button><ReadPanel state={detailState}>{registration}</ReadPanel></> : registration())}
      {scope && page === 'reception' && (record ? <Reception key={record.id} id={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从申请列表查看申请，再进入接收与异常处理" />)}
      {scope && page === 'labels' && (record ? <ReadPanel state={detailState}>{data => <Labels key={data.id} containerIds={data.containers.map(c => c.id)} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} />}</ReadPanel> : <Alert type="info" title="请从申请详情选择标签任务" />)}
      {!scope && page === 'registration' && <Alert type="info" title="先选择授权工作范围，再登记申请" />}
    </section>
    <Modal title="放弃未保存的本地输入？" open={confirming} okText="放弃并继续" cancelText="继续编辑"
      onCancel={() => { pending.current = undefined; setConfirming(false); }}
      onOk={() => { const action = pending.current; pending.current = undefined; setConfirming(false); setDirty(false); action?.(); }}>
      <p>未保存内容仅在内存中，离开会清除。结果待确认的请求可能已在服务器完成，应先使用原请求确认结果。</p>
    </Modal>
  </main>;
}
