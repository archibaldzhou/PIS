import { Alert, Empty, Spin } from 'antd';
import type { ReactNode } from 'react';
import type { ReadState } from './workflow';

export function ReadPanel<T>({ state, children }: { state: ReadState<T>; children: (data: T) => ReactNode }) {
  if (state.status === 'ready') return children(state.data);
  if (state.status === 'loading') return <div role="status" aria-label="正在查询" aria-busy="true"><Spin /><p>正在查询申请…</p></div>;
  const titles = { unavailable: '业务接口未接通', forbidden: '无权查看', stale: '数据版本已过期', error: '查询失败' };
  return <Alert showIcon role="alert" type={state.status === 'error' ? 'error' : 'warning'}
    title={titles[state.status]} description={state.message} />;
}
export function NoRecords() { return <Empty description="没有符合条件的申请；不会自动创建患者" />; }
