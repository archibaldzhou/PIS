import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Input, Select, Space, Table, Typography } from 'antd';
import { ApiError } from '../../api';
import { createSnapshot, loadSnapshot, type Metric, type Query, type Sort, type View } from './api';
const labels: Record<Metric, string> = { RECEPTION: '接收', TECHNICAL: '技术任务（人工模拟）', REPORT: '报告（合成模拟签署）', QC: '材料QC' };
interface Intent { query: Query; key: string; id?: string; metric: Metric | null; sort: Sort; page: number }
export function Statistics({ scopeId, onDirty, onClean, onExpired }: { scopeId: string; onDirty: () => void; onClean: () => void; onExpired: () => void }) {
  const today = new Date().toISOString().slice(0, 10);
  const [query, setQuery] = useState<Query>({ from: today, to: today, zone: 'UTC' });
  const [view, setView] = useState<View>(); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [retry, setRetry] = useState(false);
  const active = useRef<AbortController | undefined>(undefined); const sequence = useRef(0); const intent = useRef<Intent | undefined>(undefined);
  useEffect(() => () => { ++sequence.current; active.current?.abort(); }, []);
  function edit(next: Query) { setQuery(next); setView(undefined); intent.current = undefined; setRetry(false); onDirty(); }
  async function run(next: Intent) {
    if (active.current) return;
    const controller = new AbortController(); active.current = controller; const epoch = ++sequence.current; intent.current = next;
    setBusy(true); setError(''); setRetry(false); setView(undefined);
    try {
      const id = next.id ?? await createSnapshot(scopeId, next.query, next.key, controller.signal);
      if (epoch !== sequence.current) return;
      next.id = id;
      const result = await loadSnapshot(scopeId, id, next.metric, next.sort, next.page, controller.signal);
      if (epoch !== sequence.current) return;
      if (result.from !== next.query.from || result.to !== next.query.to || result.zone !== next.query.zone) throw Error('统计筛选与快照不匹配');
      setView(result); onClean();
    } catch (e) {
      if (epoch !== sequence.current) return;
      if (e instanceof ApiError && e.status === 401) onExpired();
      setError(e instanceof ApiError && [403, 404].includes(e.status) ? '无统计权限或快照已不可访问；不展示旧总数' : e instanceof Error ? e.message : '统计查询失败'); setRetry(true);
    } finally { if (epoch === sequence.current) { active.current = undefined; setBusy(false); } }
  }
  function cancel() { ++sequence.current; active.current?.abort(); active.current = undefined; setBusy(false); setView(undefined); setError('已取消显示；服务器可能已保存快照，可用原键重试'); setRetry(true); }
  function drill(metric: Metric | null, sort: Sort, page: number) { if (intent.current?.id) void run({ ...intent.current, metric, sort, page }); }
  return <section aria-label="合成统计工作区">
    <Alert type="warning" title="合成演示统计／非临床指标" description="自然秒计时；开放病例等待时长单列，不计入已完成TAT。无医院SLA、自动临床动作或对外导出。" />
    <Space wrap><label>起始日期<Input aria-label="统计起始日期" type="date" value={query.from} disabled={busy} onChange={e => edit({ ...query, from: e.target.value })} /></label><label>结束日期<Input aria-label="统计结束日期" type="date" value={query.to} disabled={busy} onChange={e => edit({ ...query, to: e.target.value })} /></label><label>时区<Input aria-label="统计时区" value={query.zone} maxLength={64} disabled={busy} onChange={e => edit({ ...query, zone: e.target.value })} /></label>
      <Button disabled={busy || retry || !!view || !query.from || !query.to || !query.zone} onClick={() => void run({ query: { ...query }, key: crypto.randomUUID(), metric: null, sort: 'ENTITY', page: 1 })}>冻结并查询统计</Button>
      <Button disabled={busy} onClick={() => { edit({ ...query }); setError(''); }}>清空统计结果</Button>
      {busy && <Button onClick={cancel}>取消统计查询</Button>}{retry && <Button onClick={() => { if (intent.current) void run(intent.current); }}>原键重试统计</Button>}
    </Space>
    {error && <Alert type="error" title={error} />}{busy && <p role="status">正在读取已授权事件…</p>}
    {view && <>
      <Typography.Paragraph>已应用：{view.from} 至 {view.to}（{view.zone}）｜口径 {view.definition}｜截止 {view.cutoff}</Typography.Paragraph>
      <Typography.Paragraph>固定快照 {view.id}｜摘要 {view.factsHash}</Typography.Paragraph>
      <Space wrap align="start">{view.summaries.map(s => <Card key={s.metric} title={labels[s.metric]}>
        {s.availability !== 'DATA' ? <p>{s.availability === 'NOT_AUTHORIZED' ? '无此指标权限' : '无数据'}</p> : <>
          <p>入组 {s.cohort}｜{s.metric === 'QC' ? `有效评估 ${s.denominator}` : `完成 ${s.completed}｜开放 ${s.open}`}｜未知 {s.unknown}｜排除 {s.excluded}</p>
          <p>{s.metric === 'QC' ? '不合格 / 有效评估' : '完成 / 入组'}：{s.numerator} / {s.denominator}；{s.ratio === null ? '无数据' : `${(s.ratio * 100).toFixed(2)}%`}</p>
          {s.metric !== 'QC' && <p>已完成TAT中位数（自然秒）：{s.medianSeconds ?? '无有效时长样本'}</p>}
        </>}
        <Button disabled={!view.canDrill || s.availability === 'NOT_AUTHORIZED'} onClick={() => drill(s.metric, 'ENTITY', 1)}>查看{labels[s.metric]}来源</Button>
      </Card>)}</Space>
      <Card title="指标口径"><p>接收：首次提交→首次接收；技术：任务创建→人工模拟完成；报告：首次接收→首次模拟签署。修订、重试不重复计首次完成，返工独立任务单列来源。</p><p>QC分子为不合格，分母仅含有效PASS/FAIL；撤销、来源失效与未评估单列未知。分母为零显示无数据。筛选最多92天、5000事实，每页20条。</p></Card>
      {view.metric && <Card title={`${labels[view.metric]}固定快照来源（非实时列表）`}><Select aria-label="统计明细排序" value={view.sort} options={[{ value: 'ENTITY', label: '实体ID' }, { value: 'START_ASC', label: '起点升序' }, { value: 'START_DESC', label: '起点降序' }]} onChange={(sort: Sort) => drill(view.metric, sort, 1)} />
        <Table rowKey="entityId" dataSource={view.facts} pagination={{ current: view.page, pageSize: 20, total: view.total, showSizeChanger: false, onChange: page => drill(view.metric, view.sort, page) }} columns={[
          { title: '来源实体', dataIndex: 'entityId' }, { title: '申请ID', dataIndex: 'requestId' }, { title: '状态', dataIndex: 'status' }, { title: '起点事件', dataIndex: 'startEvent' }, { title: '终点/评估事件', dataIndex: 'endEvent' }, { title: '起点UTC', dataIndex: 'startAt' }, { title: '终点UTC', dataIndex: 'endAt' }, { title: '自然秒（OPEN为等待）', render: (_, f) => f.durationSeconds ?? '未知/不适用' }, { title: '来源依据', dataIndex: 'sourceBasis' },
        ]} scroll={{ x: 1300 }} /></Card>}
    </>}
  </section>;
}
