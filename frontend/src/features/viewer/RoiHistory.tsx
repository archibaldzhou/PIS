import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input } from 'antd';
import type { Manifest } from './api';
import * as api from './roi-api';
/** Explicit history never restores image pixels or editing after QC revocation. */
export function RoiHistory({ manifest }: { manifest: Manifest }) {
 const [id, setId] = useState(''), [error, setError] = useState(''); const [view, setView] = useState<api.View>(); const active = useRef<AbortController | undefined>(undefined); const timer = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
 function cancel() { active.current?.abort(); if (timer.current) clearInterval(timer.current); }
 useEffect(() => () => { active.current?.abort(); if (timer.current) clearInterval(timer.current); }, [manifest]);
 async function read() { cancel(); const c = new AbortController(); active.current = c; setView(undefined); setError(''); if (!/^[a-f0-9-]{36}$/i.test(id)) { setError('输入确切ROI ID'); return; } let checking = false; const verify = async () => { if (checking || c.signal.aborted) return; checking = true; try { const v = await api.history(manifest, id, c.signal); if (!c.signal.aborted) setView(v); } catch (e) { if (!c.signal.aborted) { setView(undefined); setError(e instanceof Error ? e.message : '历史查询失败'); cancel(); } } finally { checking = false; } }; await verify(); if (!c.signal.aborted) timer.current = setInterval(() => void verify(), 2000); }

 return <section aria-label="独立ROI授权历史"><p>仅显式授权历史，不恢复当前图像或编辑资格。</p><Input aria-label="历史ROI ID" value={id} onChange={e => { cancel(); setView(undefined); setId(e.target.value); }} /><Button onClick={() => void read()}>查询独立ROI历史</Button><Button onClick={() => { cancel(); setView(undefined); }}>关闭独立ROI历史</Button>{error && <Alert type="error" title={error} />}{view?.items.map(r => <p key={r.revision}>ROI {r.roiId} rev{r.revision} / {r.reason} / {r.deleted ? '软删除' : '历史记录'} / 面积{r.measurement.area} {r.measurement.unit}²</p>)}</section>;
}
