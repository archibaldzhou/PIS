import { Adapters } from './features/integration/Adapters';
import { SyntheticTasks } from './features/ai/SyntheticTasks';
import { AiRegistry } from './features/ai/AiRegistry';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Modal, Select, Space, Tag, Typography } from 'antd';
import { ApiError } from './api';
import { RequestList } from './features/accession/RequestList';
import { Registration } from './features/accession/Registration';
import { accessionApi, type AccessionApi, type RequestDetail } from './features/accession/api';
import { ReadController, type RequestReader, type RequestSummary } from './shared/workflow';
import { ReadPanel } from './shared/WorkflowElements';
import './workflow.css';
import { Scan } from './features/scan/Scan';
import { DigitalQc } from './features/digitalqc/DigitalQc';
import { TileViewer } from './features/viewer/TileViewer';
import { Storage } from './features/storage/Storage';
import { Statistics } from './features/statistics/Statistics';
import { Archive } from './features/archive/Archive';
import { Staining } from './features/materials/Staining';
import { Cytology } from './features/materials/Cytology';
import { Frozen } from './features/frozen/Frozen';
import { DeliveryEditor } from './features/report/DeliveryEditor';
import { AmendmentEditor } from './features/report/AmendmentEditor';
import { OutputEditor } from './features/report/OutputEditor';
import { ConsultationEditor } from './features/report/ConsultationEditor';
import { ReviewEditor } from './features/report/ReviewEditor';
import { ReportEditor } from './features/report/ReportEditor';
import { Diagnosis } from './features/diagnosis/Diagnosis';
import { Worklist } from './features/worklist/Worklist';
import { Quality } from './features/quality/Quality';
import { Materials } from './features/materials/Materials';
import { Technical } from './features/processing/Technical';
import { Grossing } from './features/grossing/Grossing';
import { Labels } from './features/labels/Labels';
import { Reception } from './features/specimen/Reception';

const pages = { adapters: '合成医院接口', requests: '申请单查询', registration: '病理申请录入', reception: '标本接收与异常', labels: '标签打印与重打', grossing: '取材记录与取材盒', technical: '技术任务与交接', materials: '蜡块与玻片谱系', quality: '技术QC与隔离', worklist: '工作列表与追踪', diagnosis: '诊断分配与领取', report: '报告草稿', review: '复核与模拟签署', output: '固定PDF与打印记录', amendments: '报告补充与更正', delivery: '本地投递与回执', frozen: '术中冰冻工作站', cytology: '细胞学制备工作站', staining: '特殊染色与IHC批次', consultation: '院内会诊与复阅', archive: '归档借阅与盘点', statistics: '工作量TAT与QC统计', storage: '原件版本与容量', scan: '扫描任务与导入', digitalqc: '数字扫描QC', viewer: '合成数字阅片器', ai: 'AI模型与适用契约', aitasks: '合成契约任务' };
type Page = keyof typeof pages;
export function WorkflowWorkspace({ onClose, onLogout, onExpired, api = accessionApi }: {
  onClose: () => void; onLogout: () => void; onExpired: () => void; api?: AccessionApi;
}) {
  const [page, setPage] = useState<Page>('requests');
  const [qcScan, setQcScan] = useState('');
  const [taskAssessment,setTaskAssessment]=useState('');
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
      if (selected && (next === 'registration' || next === 'labels' || next === 'cytology')) void details.run(selected.id);
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
      {scope && page === 'output' && <Diagnosis key={scope} editorKind="output" scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={v => { setUnresolved(v); if (!v) setNavWarning(''); }} onExpired={onExpired} renderEditor={(id, callbacks) => <OutputEditor id={id} {...callbacks} />} />}
      {scope && page === 'amendments' && <Diagnosis key={scope} editorKind="amendment" scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={v => { setUnresolved(v); if (!v) setNavWarning(''); }} onExpired={onExpired} renderEditor={(id, callbacks) => <AmendmentEditor id={id} {...callbacks} />} />}
      {scope && page === 'delivery' && <Diagnosis key={scope} editorKind="delivery" scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={v => { setUnresolved(v); if (!v) setNavWarning(''); }} onExpired={onExpired} renderEditor={(id, callbacks) => <DeliveryEditor id={id} {...callbacks} />} />}
      {scope && page === 'consultation' && <Diagnosis key={scope} editorKind="consultation" scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={v => { setUnresolved(v); if (!v) setNavWarning(''); }} onExpired={onExpired} renderEditor={(id, callbacks) => <ConsultationEditor id={id} {...callbacks} />} />}
      {scope && page === 'review' && <Diagnosis key={scope} editorKind="review" scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={v => { setUnresolved(v); if (!v) setNavWarning(''); }} onExpired={onExpired} renderEditor={(id, callbacks) => <ReviewEditor id={id} {...callbacks} />} />}
      {scope && page === 'report' && <Diagnosis key={scope} scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={v => { setUnresolved(v); if (!v) setNavWarning(''); }} onExpired={onExpired} renderEditor={(id, callbacks) => <ReportEditor id={id} {...callbacks} />} /> }
      {scope && page === 'diagnosis' && <Diagnosis key={scope} scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={value => { setUnresolved(value); if (!value) setNavWarning(''); }} onExpired={onExpired} />}
      {scope && page === 'statistics' && <Statistics key={scope} scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onExpired={onExpired} />}
      {scope && page === 'worklist' && <Worklist key={scope} scopeId={scope} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} />}
      {scope && page === 'requests' && <RequestList key={scope} reader={reader} onSelect={selected => navigate('registration', selected)} />}
      {scope && page === 'registration' && (record ? <><Button onClick={() => navigate('adapters', record)}>处理此申请接口</Button><Button onClick={() => navigate('scan', record)}>处理此申请扫描导入</Button><Button onClick={() => navigate('storage', record)}>处理此申请原件</Button><Button onClick={() => navigate('archive', record)}>处理此申请档案</Button><Button onClick={() => navigate('staining', record)}>处理此申请染色批次</Button><Button onClick={() => navigate('cytology', record)}>处理此申请细胞学</Button><Button onClick={() => navigate('frozen', record)}>处理此申请冰冻</Button><Button onClick={() => navigate('reception', record)}>处理此申请接收与异常</Button><Button onClick={() => navigate('labels', record)}>处理此申请标签</Button><Button onClick={() => navigate('grossing', record)}>处理此病例取材</Button><Button onClick={() => navigate('technical', record)}>处理此病例技术任务</Button><Button onClick={() => navigate('materials', record)}>处理此病例材料谱系</Button><Button onClick={() => navigate('quality', record)}>处理此病例技术QC</Button><ReadPanel state={detailState}>{registration}</ReadPanel></> : registration())}
      {scope && page === 'reception' && (record ? <Reception key={record.id} id={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从申请列表查看申请，再进入接收与异常处理" />)}
      {scope && page === 'labels' && (record ? <ReadPanel state={detailState}>{data => <Labels key={data.id} containerIds={data.containers.map(c => c.id)} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} />}</ReadPanel> : <Alert type="info" title="请从申请详情选择标签任务" />)}
      {scope && page === 'grossing' && (record ? <Grossing key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入取材" />)}
      {scope && page === 'technical' && (record ? <Technical key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从取材已完成的申请详情进入技术任务" />)}
      {scope && page === 'materials' && (record ? <Materials key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入材料谱系" />)}
      {scope && page === 'quality' && (record ? <Quality key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入技术QC" />)}
      {scope && page === 'frozen' && (record ? <Frozen key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入术中冰冻" />)}
      {scope && page === 'cytology' && (record ? <ReadPanel state={detailState}>{data => <Cytology key={data.id} requestId={data.id} containers={data.containers} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} />}</ReadPanel> : <Alert type="info" title="请从已接收申请详情进入细胞学制备" />)}
      {scope && page === 'viewer' && record && qcScan && <Button onClick={()=>{setTaskAssessment('');navigate('aitasks',record);}}>此扫描合成任务队列</Button>}
      {scope && page === 'aitasks' && record && qcScan && <Button onClick={()=>navigate('ai',record)}>返回当前扫描AI契约</Button>}
      {scope && page === 'viewer' && record && qcScan && <Button onClick={() => navigate('ai', record)}>核对此扫描AI适用契约</Button>}
      {scope && page === 'ai' && record && qcScan && <Button onClick={() => navigate('viewer', record)}>返回当前扫描阅片</Button>}
      {scope && page === 'aitasks' && (record && qcScan ? <SyntheticTasks key={`${record.id}:${qcScan}`} requestId={record.id} scanId={qcScan} assessmentId={taskAssessment||undefined} onDirty={()=>setDirty(true)} onClean={()=>setDirty(false)} onPending={setUnresolved} onExpired={onExpired}/> : <Alert type="info" title="请从当前扫描适用判定进入合成任务"/>)}
      {scope && page === 'ai' && <AiRegistry key={`${scope}:${record?.id}:${qcScan}`} scopeId={scope} requestId={record?.id} scanId={qcScan || undefined} onTasks={id=>{setTaskAssessment(id);navigate('aitasks',record);}} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} />}
      {scope && page === 'viewer' && (record && qcScan ? <TileViewer key={`${record.id}:${qcScan}`} requestId={record.id} scanId={qcScan} onExpired={onExpired} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} /> : <Alert type="info" title="请从当前已发布数字QC进入合成阅片" />)}
      {scope && page === 'digitalqc' && (record && qcScan ? <DigitalQc key={`${record.id}:${qcScan}`} requestId={record.id} scanId={qcScan} onViewer={() => leave(() => { setDirty(false); setPage('viewer'); })} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从精确扫描任务进入数字QC" />)}
      {scope && page === 'adapters' && (record ? <Adapters key={record.id} requestId={record.id} onDirty={()=>setDirty(true)} onClean={()=>setDirty(false)} onPending={setUnresolved} onExpired={onExpired}/> : <Alert type="info" title="请从已接收申请详情进入合成接口"/>)}
      {scope && page === 'scan' && (record ? <Scan key={record.id} requestId={record.id} onQc={id => leave(() => { setQcScan(id); setDirty(false); setPage('digitalqc'); })} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入扫描导入" />)}
      {scope && page === 'storage' && (record ? <Storage key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入原件存储" />)}
      {scope && page === 'archive' && (record ? <Archive key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入档案台账" />)}
      {scope && page === 'staining' && (record ? <Staining key={record.id} requestId={record.id} onDirty={() => setDirty(true)} onClean={() => setDirty(false)} onPending={setUnresolved} onExpired={onExpired} /> : <Alert type="info" title="请从已接收申请详情进入染色批次" />)}
      {!scope && page === 'registration' && <Alert type="info" title="先选择授权工作范围，再登记申请" />}
    </section>
    <Modal title="放弃未保存的本地输入？" open={confirming} okText="放弃并继续" cancelText="继续编辑"
      onCancel={() => { pending.current = undefined; setConfirming(false); }}
      onOk={() => { const action = pending.current; pending.current = undefined; setConfirming(false); setDirty(false); action?.(); }}>
      <p>未保存内容仅在内存中，离开会清除。结果待确认的请求可能已在服务器完成，应先使用原请求确认结果。</p>
    </Modal>
  </main>;
}
