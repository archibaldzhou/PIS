import { ApiError, sessionApi, type Credentials, type CurrentUser, type HelloResponse, type SessionApi } from './api';

export type HelloState = { status: 'loading' } | { status: 'success'; data: HelloResponse }
  | { status: 'error'; message: string };
export type SessionState = { status: 'checking' } | { status: 'anonymous'; message?: string }
  | { status: 'authenticating' } | { status: 'logging-out' }
  | { status: 'authenticated'; user: CurrentUser; hello: HelloState; notice?: string }
  | { status: 'error'; message: string; recovery: 'check' | 'logout' };

function message(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请重试';
}
function unauthenticated(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

/** In-memory session view. Cookies are managed only by the browser/server. */
export class SessionController {
  private state: SessionState = { status: 'checking' };
  private listeners = new Set<() => void>();
  private generation = 0;
  private authRequest?: AbortController;
  private helloRequest?: AbortController;

  constructor(private api: SessionApi = sessionApi) {}

  getSnapshot = (): SessionState => this.state;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  private setState(state: SessionState): void {
    this.state = state;
    this.listeners.forEach(listener => listener());
  }
  stop = (): void => {
    this.generation++;
    this.authRequest?.abort();
    this.helloRequest?.abort();
  };
  private begin(): { generation: number; signal: AbortSignal } {
    this.stop();
    this.authRequest = new AbortController();
    return { generation: this.generation, signal: this.authRequest.signal };
  }
  private current(generation: number): boolean { return generation === this.generation; }
  private expired(): void {
    this.stop();
    this.setState({ status: 'anonymous', message: '登录已失效，请重新登录' });
  }
  private signedIn(user: CurrentUser, notice?: string): void {
    this.setState({ status: 'authenticated', user, hello: { status: 'loading' }, notice });
    void this.retryHello();
  }
  // After login/logout the server clears or rotates CSRF state. Always request a
  // fresh token; no token is serialized to storage, React state, or a URL.
  private async refreshCsrf(signal: AbortSignal): Promise<string | undefined> {
    try { await this.api.fetchCsrf(signal); }
    catch (error) {
      if (signal.aborted || unauthenticated(error)) throw error;
      return '安全令牌刷新失败；下次操作会重新获取。' + message(error);
    }
  }
  restore = async (): Promise<void> => {
    const { generation, signal } = this.begin();
    this.setState({ status: 'checking' });
    try {
      const user = await this.api.fetchCurrentUser(signal);
      if (this.current(generation)) this.signedIn(user);
    } catch (error) {
      if (!this.current(generation)) return;
      this.setState(unauthenticated(error) ? { status: 'anonymous' }
        : { status: 'error', message: message(error), recovery: 'check' });
    }
  };
  login = async (credentials: Credentials): Promise<void> => {
    if (this.state.status !== 'anonymous') return;
    const { generation, signal } = this.begin();
    this.setState({ status: 'authenticating' });
    let accepted = false;
    try {
      const csrf = await this.api.fetchCsrf(signal);
      if (!this.current(generation)) return;
      await this.api.login(credentials, csrf, signal);
      accepted = true;
      if (!this.current(generation)) return;
      const notice = await this.refreshCsrf(signal);
      if (!this.current(generation)) return;
      const user = await this.api.fetchCurrentUser(signal);
      if (this.current(generation)) this.signedIn(user, notice);
    } catch (error) {
      if (!this.current(generation)) return;
      if (accepted && unauthenticated(error)) { this.expired(); return; }
      this.setState(accepted
        ? { status: 'error', message: '登录状态确认失败：' + message(error), recovery: 'check' }
        : { status: 'anonymous', message: message(error) });
    }
  };
  logout = async (): Promise<void> => {
    if (this.state.status !== 'authenticated' &&
      !(this.state.status === 'error' && this.state.recovery === 'logout')) return;
    // Clear the user view immediately and invalidate every older request before
    // making the logout request, including transports that ignore AbortSignal.
    const { generation, signal } = this.begin();
    this.setState({ status: 'logging-out' });
    try {
      const csrf = await this.api.fetchCsrf(signal);
      if (!this.current(generation)) return;
      await this.api.logout(csrf, signal);
      if (!this.current(generation)) return;
      const notice = await this.refreshCsrf(signal);
      if (this.current(generation)) this.setState({ status: 'anonymous', message: notice });
    } catch (error) {
      if (!this.current(generation)) return;
      if (unauthenticated(error)) { this.expired(); return; }
      this.setState({ status: 'error', message: '退出登录未完成：' + message(error), recovery: 'logout' });
    }
  };
  retryHello = async (): Promise<void> => {
    if (this.state.status !== 'authenticated') return;
    this.helloRequest?.abort();
    const request = new AbortController();
    this.helloRequest = request;
    const generation = this.generation;
    this.setState({ ...this.state, hello: { status: 'loading' } });
    const active = () => this.current(generation) && this.helloRequest === request &&
      this.state.status === 'authenticated';
    try {
      const data = await this.api.fetchHello(request.signal);
      if (active() && this.state.status === 'authenticated') {
        this.setState({ ...this.state, hello: { status: 'success', data } });
      }
    } catch (error) {
      if (!active()) return;
      if (unauthenticated(error)) { this.expired(); return; }
      if (this.state.status === 'authenticated') {
        this.setState({ ...this.state, hello: { status: 'error', message: message(error) } });
      }
    }
  };
}
