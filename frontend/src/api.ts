export interface HelloResponse { message: string; application: string }

export async function fetchHello(signal?: AbortSignal): Promise<HelloResponse> {
  const response = await fetch('/api/hello', { signal });
  if (!response.ok) throw new Error('后端请求失败（HTTP ' + response.status + '）');
  const data: unknown = await response.json();
  if (typeof data !== 'object' || data === null ||
      !('message' in data) || typeof data.message !== 'string' ||
      !('application' in data) || typeof data.application !== 'string') {
    throw new Error('后端返回格式不正确');
  }
  return { message: data.message, application: data.application };
}
