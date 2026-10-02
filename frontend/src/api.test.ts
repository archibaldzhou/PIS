import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchHello } from './api';

afterEach(() => vi.unstubAllGlobals());

describe('fetchHello', () => {
  it('returns a validated API response and forwards cancellation', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'Hello World', application: 'PIS' })));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    await expect(fetchHello(controller.signal)).resolves.toEqual({ message: 'Hello World', application: 'PIS' });
    expect(fetchMock).toHaveBeenCalledWith('/api/hello', { signal: controller.signal });
  });
  it('rejects HTTP failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })));
    await expect(fetchHello()).rejects.toThrow('HTTP 500');
  });
  it('rejects malformed data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 123 }))));
    await expect(fetchHello()).rejects.toThrow('后端返回格式不正确');
  });
});
