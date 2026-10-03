import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Select, Space } from 'antd';
import OpenSeadragon from 'openseadragon';
import { ApiError } from '../../api';
import * as api from './api';

export function TileViewer({ requestId, scanId, onExpired }: { requestId: string; scanId: string; onExpired: () => void }) {
 const [choiceError, setChoiceError] = useState(''); const [selected, setSelected] = useState(scanId); const [choices, setChoices] = useState<{ id: string; label: string }[]>([]);
 useEffect(() => { const c = new AbortController(); void api.choices(requestId, c.signal).then(v => { if (!c.signal.aborted) setChoices(v); }).catch(e => { if (!c.signal.aborted) setChoiceError(e instanceof Error ? e.message : '扫描列表查询失败'); }); return () => c.abort(); }, [requestId]);
 return <section aria-label="合成数字阅片"><Alert type="warning" title="合成RGB几何图／非临床；真实WSI与厂商格式未支持，未校准，不提供物理比例尺或测量。" />{choiceError && <Alert type="error" title={choiceError} />}<label>选择精确扫描<Select virtual={false} aria-label="选择精确扫描" style={{ minWidth: 300 }} value={selected} options={choices.map(x => ({ value: x.id, label: x.label }))} onChange={setSelected} /></label><ImageView key={`${requestId}:${selected}`} requestId={requestId} scanId={selected} onExpired={onExpired} /></section>;
}
function ImageView({ requestId, scanId, onExpired }: { requestId: string; scanId: string; onExpired: () => void }) {
 const stop = useRef<(() => void) | undefined>(undefined);
 const element = useRef<HTMLDivElement>(null); const viewer = useRef<OpenSeadragon.Viewer | undefined>(undefined); const control = useRef<AbortController | undefined>(undefined); const [round, setRound] = useState(0); const [error, setError] = useState(''); const [status, setStatus] = useState('加载清单与授权'); const [manifest, setManifest] = useState<api.Manifest>(); const [pub, setPub] = useState<number>(); const [prepareNeeded, setPrepareNeeded] = useState(false); const [busy, setBusy] = useState(false); const [zoom, setZoom] = useState(''); const [position, setPosition] = useState(''); const key = useRef(crypto.randomUUID()); const write = useRef(false); const expired = useRef(onExpired); expired.current = onExpired;
 useEffect(() => {
  const root = new AbortController(); control.current = root; const jobs = new Map<OpenSeadragon.ImageJob, AbortController>(); let disposed = false; let instance: OpenSeadragon.Viewer | undefined; let timer: ReturnType<typeof setInterval> | undefined; let checking = false; let failed = false; let tileFailure: unknown;
  function destroy() { if (instance) { instance.destroy(); instance = undefined; viewer.current = undefined; } for (const c of jobs.values()) c.abort(); jobs.clear(); element.current?.replaceChildren(); }
  stop.current = () => { root.abort(); if (timer) clearInterval(timer); destroy(); };
  function fail(e: unknown) { if (disposed || root.signal.aborted || failed) return; failed = true; destroy(); setManifest(undefined); setStatus('图像已清空'); setError(e instanceof Error ? e.message : '瓦片加载失败；空白不能解释为阴性'); if (e instanceof ApiError && e.status === 401) expired.current(); }
  async function open() {
   try { const p = await api.publication(requestId, scanId, root.signal); if (root.signal.aborted) return; setPub(p); let m: api.Manifest; try { m = await api.load(requestId, scanId, p, root.signal); } catch (e) { if (!root.signal.aborted && e instanceof ApiError && e.code === 'VIEWER_NOT_PREPARED') { setPrepareNeeded(true); setStatus('需要显式生成合成瓦片清单'); return; } throw e; }
    if (root.signal.aborted || !element.current) return; setManifest(m); setPrepareNeeded(false); setError(''); setStatus('正在加载真实合成PNG瓦片');
    const source = new OpenSeadragon.TileSource({ width: m.content.width, height: m.content.height, tileSize: 128, tileOverlap: 0, minLevel: 0, maxLevel: m.content.maxLevel });
    source.getTileUrl = (level, x, y) => `${api.base(requestId, scanId)}/tiles/${level}/${x}/${y}?publicationVersion=${p}`;
    source.downloadTileStart = job => { const c = new AbortController(); jobs.set(job, c); const stop = () => c.abort(); root.signal.addEventListener('abort', stop, { once: true }); let timedOut = false; const timeout = setTimeout(() => { timedOut = true; stop(); }, 10000); const spec = m.content.tiles.find(t => source.getTileUrl(t.level, t.x, t.y) === job.src); if (!spec) { clearTimeout(timeout); root.signal.removeEventListener('abort', stop); jobs.delete(job); tileFailure = Error('瓦片缺失，停止显示'); job.fail('瓦片坐标不在清单', null); fail(tileFailure); return; } void api.binary(job.src, spec, c.signal).then(blob => { if (!disposed && !c.signal.aborted && !failed) job.finish(blob, null, 'rasterBlob'); }).catch(e => { if (!disposed && !root.signal.aborted && (!c.signal.aborted || timedOut)) { tileFailure = e; job.fail('瓦片失败或超时', null); fail(e); } }).finally(() => { clearTimeout(timeout); root.signal.removeEventListener('abort', stop); jobs.delete(job); }); };
    source.downloadTileAbort = job => { jobs.get(job)?.abort(); jobs.delete(job); };
    instance = OpenSeadragon({ element: element.current, tileSources: Object.assign(source, { url: '' }), showNavigationControl: false, showNavigator: true, navigatorPosition: 'BOTTOM_LEFT', navigatorSizeRatio: .2, drawer: 'canvas', maxImageCacheCount: 32, imageLoaderLimit: 4, timeout: 10000, blendTime: 0, animationTime: .2, maxZoomPixelRatio: 8, visibilityRatio: 1, constrainDuringPan: true }); viewer.current = instance;
    instance.addHandler('tile-loaded', () => { if (!disposed && !failed) setStatus('真实合成PNG已加载；不代表临床就绪'); });
    instance.addHandler('tile-load-failed', () => fail(tileFailure ?? Error('瓦片缺失或网络失败，已停止显示；请显式重试')));
    const verify = async () => { if (checking || failed || root.signal.aborted) return; checking = true; try { const current = await api.load(requestId, scanId, p, root.signal); if (current.content.manifestHash !== m.content.manifestHash) throw Error('清单变化，旧图已失效'); } catch (e) { fail(e); } finally { checking = false; } };
    instance.addHandler('viewport-change', () => { if (instance && !failed) { setZoom(instance.viewport.getZoom().toFixed(2)); const c = instance.viewport.getCenter(); setPosition(`${c.x.toFixed(3)},${c.y.toFixed(3)}`); void verify(); } });
    timer = setInterval(() => void verify(), 2000);
   } catch (e) { fail(e); }
  }
  void open(); return () => { disposed = true; root.abort(); if (timer) clearInterval(timer); destroy(); };
 }, [requestId, scanId, round]);
 async function prepare() { if (pub === undefined || write.current) return; write.current = true; setBusy(true); setError(''); const c = control.current; if (!c) { write.current = false; setBusy(false); return; } try { await api.prepare(requestId, scanId, pub, key.current, c.signal); if (!c.signal.aborted) { setPrepareNeeded(false); setRound(v => v + 1); } } catch (e) { if (!c.signal.aborted) setError(e instanceof Error ? e.message : '生成结果未确认，请原键重试'); } finally { write.current = false; setBusy(false); } }
 function move(action: string) { const v = viewer.current; if (!v) return; if (action === 'home') v.viewport.goHome(); else if (action === '+' || action === '-') v.viewport.zoomBy(action === '+' ? 1.5 : 1 / 1.5); else { const delta = .1 / v.viewport.getZoom(); v.viewport.panBy(new OpenSeadragon.Point(action === 'ArrowLeft' ? -delta : action === 'ArrowRight' ? delta : 0, action === 'ArrowUp' ? -delta : action === 'ArrowDown' ? delta : 0)); } v.viewport.applyConstraints(); }
 return <div><p>扫描 {scanId} / 发布版本 {pub ?? '待核验'} / 原件 {manifest?.content.objectId ?? '待核验'}</p><p role="status">{status}</p>{error && <Alert role="alert" type="error" title={error} />}
  <Space wrap>{prepareNeeded && <Button disabled={busy} onClick={() => void prepare()}>生成或原键确认合成瓦片</Button>}<Button onClick={() => { stop.current?.(); setManifest(undefined); setStatus('已停止加载并清空图像'); }}>停止加载图像</Button><Button disabled={busy} onClick={() => { setError(''); setManifest(undefined); setStatus('重新核验授权'); setRound(v => v + 1); }}>重新授权并加载</Button><Button disabled={!manifest} onClick={() => move('+')}>放大图像</Button><Button disabled={!manifest} onClick={() => move('-')}>缩小图像</Button><Button disabled={!manifest} onClick={() => move('home')}>适配窗口</Button></Space>
  <p>图像相对缩放 {zoom || '—'} / 导航坐标 {position || '—'}；键盘方向键平移，+/-缩放，Home适配。已交付像素的授权刷新间隔2秒。</p>
  <div ref={element} aria-label="合成瓦片画布" tabIndex={0} onKeyDown={e => { if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', '+', '-', 'Home'].includes(e.key)) { e.preventDefault(); move(e.key === 'Home' ? 'home' : e.key); } }} style={{ height: 520, width: '100%', background: '#152638', position: 'relative' }} />
 </div>;
}
