import { useEffect, useState } from 'react';
import { Alert, Button, Card, Space, Spin, Tag, Typography } from 'antd';
import { fetchHello, type HelloResponse } from './api';

type State = { status: 'loading' } | { status: 'success'; data: HelloResponse }
  | { status: 'error'; message: string };

export default function App() {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<State>({ status: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setState({ status: 'loading' });
    fetchHello(controller.signal).then(
      data => { if (active) setState({ status: 'success', data }); },
      error => {
        if (active) setState({ status: 'error',
          message: error instanceof Error ? error.message : '无法连接后端' });
      },
    );
    return () => { active = false; controller.abort(); };
  }, [attempt]);

  return <main className="page">
    <Card className="hello-card">
      <Space orientation="vertical" size="large" style={{ width: '100%' }}>
        <div><Tag color="cyan">PIS · 工程验证</Tag>
          <Typography.Title level={1}>病理系统 · Hello World</Typography.Title>
          <Typography.Paragraph type="secondary">
            React + Ant Design 前端正在连接 Spring Boot 后端
          </Typography.Paragraph>
        </div>
        <section aria-live="polite" aria-busy={state.status === 'loading'}>
          {state.status === 'loading' && <Spin tip="正在连接后端"><div style={{ height: 80 }} /></Spin>}
          {state.status === 'success' && <Alert type="success" showIcon
            title={state.data.message} description={'应用：' + state.data.application + ' · API 连接成功'} />}
          {state.status === 'error' && <Alert type="error" showIcon
            title="连接失败" description={state.message + '。请确认后端已在 8080 端口启动。'} />}
        </section>
        <Button type="primary" loading={state.status === 'loading'}
          onClick={() => setAttempt(value => value + 1)}>重新请求</Button>
        <Typography.Text type="secondary">
          仅用于工程连通性验证，尚未实现病理业务或临床 AI
        </Typography.Text>
      </Space>
    </Card>
  </main>;
}
