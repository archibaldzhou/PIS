import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Select, Space, Table, Typography } from 'antd';
import { ApiError } from '../../api';
import * as api from './api';
export function Storage({ requestId, onDirty, onClean, onPending, onExpired }: { requestId: string; onDirty: () => void; onClean: () => void; onPending: (value: boolean) => void; onExpired: () => void }) {
 const [view, setView] = useState<api.View>(); const [page, setPage] = useState(1); const [size, setSize] = useState(65536); const [selected, setSelected] = useState<api.Version>();
 const [abandon, setAbandon] = useState(false);
 const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false); const [pending, setPending] = useState(false);
 const [capacity, setCapacity] = useState<api.Capacity>(); const [preview, setPreview] = useState(''); const intent = useRef<api.Intent | undefined>(undefined); const active = useRef<AbortController | undefined>(undefined); const serial = useRef(0); const locked = useRef(false);
 const expired = useRef(onExpired); expired.current = onExpired;
 useEffect(() => { const c = new AbortController(); const n = ++serial.current; setBusy(true); locked.current = true;
  void api.list(requestId, page, c.signal).then(v => { if (serial.current === n && !c.signal.aborted) setView(v); }).catch(e => { if (!c.signal.aborted && serial.current === n) { setError(e instanceof Error ? e.message : '读取失败'); if (e instanceof ApiError && e.status === 401) expired.current(); } }).finally(() => { if (serial.current === n && !c.signal.aborted) { setBusy(false); locked.current = false; } }); active.current = c;
  return () => { c.abort(); active.current?.abort(); };
 }, [requestId, page]);
 async function run(work: (signal: AbortSignal) => Promise<void>) { if (locked.current) return; locked.current = true; setBusy(true); setError(''); const c = new AbortController(); active.current = c; const n = ++serial.current;
  try { await work(c.signal); } catch (e) { if (!c.signal.aborted && serial.current === n) { setError(e instanceof Error ? e.message : '操作失败'); if (e instanceof ApiError && [401, 403, 404].includes(e.status)) { setView(undefined); setCapacity(undefined); setPreview(''); } if (e instanceof ApiError && e.status === 401) onExpired(); } } finally { if (serial.current === n) { setBusy(false); locked.current = false; } }
 }
 const current = (signal: AbortSignal) => !signal.aborted;
 async function refresh(signal: AbortSignal) { setView(undefined); setPreview(''); const v = await api.list(requestId, page, signal); if (current(signal)) setView(v); }
 async function save(signal: AbortSignal) {
  if (!view) return; let i = intent.current;
  if (!i) { const bytes = api.syntheticBytes(size); i = { reserveKey: crypto.randomUUID(), finishKey: crypto.randomUUID(), assetId: selected?.assetId ?? null, expectedHead: selected?.ordinal ?? -1, caseId: view.caseId, bytes, hash: await api.digest(bytes) }; if (!current(signal)) return; intent.current = i; setPending(true); onPending(true); onDirty(); }
  if (!i.id) i.id = await api.reserve(requestId, i, signal); if (!current(signal)) return;
  let v = await api.detail(requestId, i.caseId, i.id, signal); if (!current(signal)) return;
  if (v.state === 'RESERVED') v = await api.upload(requestId, v, i.bytes, signal); if (!current(signal)) return;
  if (v.state === 'STAGED') { i.finishVersion ??= v.version; v = await api.finish(requestId, v.id, i, signal); }
  else if (v.state === 'FINALIZING' && i.finishVersion !== undefined) v = await api.finish(requestId, v.id, i, signal);
  if (!current(signal)) return;
  if (v.state !== 'READY' && v.state !== 'FAILED') { await refresh(signal); throw Error('状态尚未完成，请按确切版本执行人工恢复核对，再用原键确认。'); }
  intent.current = undefined; setPending(false); onPending(false); onClean(); setSelected(undefined); setNotice(v.state === 'READY' ? '已校验并登记固定合成原件；不表示WSI或临床有效。' : '上传失败；未发布原件。新尝试必须新建版本。'); await refresh(signal);
 }
 function cancel() { ++serial.current; active.current?.abort(); locked.current = false; setBusy(false); setNotice('已停止等待；服务端操作可能已发生，请使用原键确认或刷新后恢复。'); }
 function choose(v?: api.Version) { if (pending || busy) return; setSelected(v); setPreview(''); setNotice(''); onDirty(); }
 return <section aria-label="私有合成原件存储"><Typography.Title level={2}>原件版本与容量</Typography.Title>
  <Alert type="warning" title="仅确定性合成字节；非WSI格式验证、非临床原件。S3未配置／未验证。" />
  {error && <Alert role="alert" type="error" title={error} />}{notice && <Alert role="status" type="info" title={notice} />}
  <p>本地 provider：{view?.provider ?? '尚未读取'}。就绪登记后每次读取仍重新校验哈希。容量为配置根所在文件系统实测值。</p>
  <Space wrap><Button disabled={busy} onClick={() => void run(refresh)}>刷新确切版本</Button><Button disabled={busy} onClick={() => void run(async s => { const c = await api.capacity(requestId, s); if (current(s)) setCapacity(c); })}>测量根目录容量</Button>
   <Button disabled={busy} onClick={() => void run(async s => { const ids = await api.cleanup(requestId, s); if (current(s)) setNotice(`仅预检本人失败暂存对象 ${ids.length} 项；未删除任何文件。`); })}>暂存清理预检（不删除）</Button><Button disabled={!busy} onClick={cancel}>停止等待</Button></Space>
  {capacity && <p>文件系统总量：{capacity.volumeTotal ?? '未配置'} 字节；可用：{capacity.volumeUsable ?? '未配置'} 字节；本院合成预留：{capacity.reservedBytes} / {capacity.syntheticQuota} 字节；测量时间：{capacity.measuredAt ?? '未知'}。不代表医院规划容量。</p>}
  <p>当前申请 {requestId}；病例 {view?.caseId ?? '未读取'}；{selected ? `新版本来源：${selected.assetId}，预期头 ${selected.ordinal}` : '新原件身份'}</p>
  <Space wrap><Select aria-label="合成原件大小" value={size} disabled={pending || busy} onChange={v => { setSize(v); onDirty(); }} options={[{ value: 65536, label: '64 KiB 合成字节' }, { value: 2097152, label: '2 MiB 合成字节' }]} />
   <Button disabled={busy || pending} onClick={() => choose()}>选择新原件</Button><Button disabled={busy || view?.provider !== 'CONFIGURED_LOCAL'} onClick={() => void run(save)}>{pending ? '原键确认上传与完成' : '生成并上传合成原件'}</Button>
   <Button disabled={busy || !pending} onClick={() => setAbandon(true)}>停止跟踪原请求</Button><Button disabled={busy || pending} onClick={() => { setSelected(undefined); setSize(65536); onClean(); }}>取消本地选择</Button></Space>
  <Table<api.Version> rowKey="id" dataSource={view?.versions ?? []} pagination={false} scroll={{ x: true }} columns={[
   { title: '固定版本ID', dataIndex: 'id' }, { title: '原件版本', dataIndex: 'ordinal' }, { title: '状态/CAS', render: (_, v) => `${v.state} / ${v.version}` }, { title: '字节', dataIndex: 'byteSize' }, { title: 'SHA-256', dataIndex: 'sha256' },
   { title: '操作', render: (_, v) => <Space wrap><Button disabled={busy || pending} onClick={() => choose(v)}>以此头新建版本</Button>
    <Button disabled={busy || v.rootId !== view?.providerRootId || !['UPLOADING', 'FINALIZING', 'READY'].includes(v.state)} onClick={() => void run(async s => { await api.reconcile(requestId, v, s); if (current(s)) await refresh(s); })}>人工恢复核对</Button>
    <Button disabled={busy || v.state !== 'READY' || v.rootId !== view?.providerRootId} onClick={() => void run(async s => { const b = await api.bytes(requestId, v, 'PREVIEW', s); if (current(s)) setPreview(`${v.id}：${Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, '0')).join(' ')}`); })}>预览前128字节</Button>
    <Button disabled={busy || v.state !== 'READY' || v.rootId !== view?.providerRootId} onClick={() => void run(async s => { const b = await api.bytes(requestId, v, 'DOWNLOAD', s); if (!current(s)) return; const url = URL.createObjectURL(new Blob([b], { type: 'application/octet-stream' })); const a = document.createElement('a'); a.href = url; a.download = `synthetic-${v.id}${v.byteSize > 1048576 ? '-first-1MiB' : ''}.bin`; a.click(); URL.revokeObjectURL(url); })}>{v.byteSize > 1048576 ? '下载前1MiB片段' : '下载固定原件'}</Button></Space> },
  ]} />
  {preview && <pre aria-label="固定版本十六进制预览" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{preview}</pre>}
  <Space><Button disabled={busy || pending || page === 1} onClick={() => { setView(undefined); setSelected(undefined); setPreview(''); setPage(page - 1); }}>上一页</Button><span>第 {page} 页</span><Button disabled={busy || pending || page === 50 || view?.versions.length !== 20} onClick={() => { setView(undefined); setSelected(undefined); setPreview(''); setPage(page + 1); }}>下一页</Button></Space>
  <Modal title="停止本地跟踪？" open={abandon} okText="保留服务器记录并停止跟踪" cancelText="继续确认原请求" onCancel={() => setAbandon(false)} onOk={() => { intent.current = undefined; setPending(false); onPending(false); setSelected(undefined); onClean(); setAbandon(false); setNotice('服务器记录与配额均保留，没有撤销上传。请刷新确切版本并按状态人工恢复；新建是独立原件。'); }}><p>结果可能已经保存。停止跟踪不会删除、撤销或覆盖服务器版本，也不会释放已预留配额。</p></Modal>
 </section>;
}
