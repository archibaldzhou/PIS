import { useEffect, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Form, Input, Space, Spin, Tag, Typography } from 'antd';
import type { Credentials } from './api';
import { SessionController } from './session';
import { WorkflowWorkspace } from './WorkflowWorkspace';

export default function App() {
  const [session] = useState(() => new SessionController());
  const [workspaceUser, setWorkspaceUser] = useState<string>();
  const state = useSyncExternalStore(session.subscribe, session.getSnapshot);
  const [form] = Form.useForm<Credentials>();

  useEffect(() => {
    void session.restore();
    return session.stop;
  }, [session]);

  async function submit(values: Credentials) {
    try { await session.login(values); }
    finally { form.setFieldValue('password', ''); }
  }

  const loginScreen = state.status === 'anonymous' || state.status === 'authenticating';
  if (state.status === 'authenticated' && workspaceUser === state.user.id) {
    return <WorkflowWorkspace key={state.user.id} onClose={() => setWorkspaceUser(undefined)}
      onExpired={() => { setWorkspaceUser(undefined); void session.restore(); }}
      onLogout={() => { setWorkspaceUser(undefined); void session.logout(); }} />;
  }
  return <main className="page">
    <Card className={loginScreen ? 'hello-card login-card' : 'hello-card'}>
      <Space orientation="vertical" size="large" style={{ width: '100%' }}>
        <div><Tag color="cyan">PIS · 工程验证</Tag>
          <Typography.Title level={1}>
            {loginScreen ? '登录 PIS' : '病理系统 · Hello World'}
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            {loginScreen ? '请使用已分配的账号登录' : 'React + Ant Design 前端正在连接 Spring Boot 后端'}
          </Typography.Paragraph>
        </div>
        {(state.status === 'checking' || state.status === 'logging-out') &&
          <section aria-live="polite" aria-busy="true">
            <Spin tip={state.status === 'checking' ? '正在检查登录状态' : '正在退出登录'}>
              <div className="loading-placeholder" />
            </Spin>
          </section>}
        {loginScreen && <div>
          {state.status === 'anonymous' && state.message &&
            <Alert className="auth-message" type="error" showIcon title={state.message} role="alert" />}
          <Form form={form} layout="vertical" onFinish={submit}
            disabled={state.status === 'authenticating'} requiredMark={false}>
            <Form.Item label="用户名" name="username" rules={[{ required: true, whitespace: true, message: '请输入用户名' }]}>
              <Input name="username" autoComplete="username" autoCapitalize="none" spellCheck={false} />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password name="password" autoComplete="current-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" autoInsertSpace={false} aria-label="登录"
              aria-busy={state.status === 'authenticating'} block loading={state.status === 'authenticating'}>登录</Button>
          </Form>
        </div>}
        {state.status === 'error' && <Space orientation="vertical" style={{ width: '100%' }}>
          <Alert type="error" showIcon title="会话请求失败" description={state.message} role="alert" />
          <Button type="primary" onClick={state.recovery === 'logout' ? session.logout : session.restore}>
            {state.recovery === 'logout' ? '重试退出' : '重新检查登录状态'}
          </Button>
        </Space>}
        {state.status === 'authenticated' && <>
          <div className="session-bar">
            <Typography.Text>已登录：{state.user.displayName}（{state.user.username}）</Typography.Text>
            <Button onClick={session.logout}>退出登录</Button>
          </div>
          {state.notice && <Alert type="warning" showIcon title={state.notice} role="alert" />}
          <section aria-live="polite" aria-busy={state.hello.status === 'loading'}>
            {state.hello.status === 'loading' && <Spin tip="正在连接后端"><div className="loading-placeholder" /></Spin>}
            {state.hello.status === 'success' && <Alert type="success" showIcon
              title={state.hello.data.message} description={'应用：' + state.hello.data.application + ' · API 连接成功'} />}
            {state.hello.status === 'error' && <Alert type="error" showIcon
              title="连接失败" description={state.hello.message} role="alert" />}
          </section>
          <Button type="primary" loading={state.hello.status === 'loading'} onClick={session.retryHello}>重新请求</Button>
          <Button onClick={() => setWorkspaceUser(state.user.id)}>申请登记工作区</Button>
        </>}
        <Typography.Text type="secondary">
          仅用于工程连通性验证，尚未实现病理业务或临床 AI
        </Typography.Text>
      </Space>
    </Card>
  </main>;
}
