import { describe, expect, it } from 'vitest';
import { ApiError } from '../api';
import { CommandIntent } from './command';
import { ReadController, type ReadResult } from './workflow';

function deferred<T>() { let resolve: (value: T) => void = () => { throw new Error('Uninitialized'); };
  const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
describe('workflow request isolation', () => {
  it('clears old data immediately and rejects late A after B, even if abort is ignored', async () => {
    const a = deferred<ReadResult<string>>(); const b = deferred<ReadResult<string>>();
    const signals: AbortSignal[] = [];
    const controller = new ReadController<string, string>((id, signal) => { signals.push(signal); return id === 'a' ? a.promise : b.promise; });
    const first = controller.run('a'); const second = controller.run('b');
    expect(signals[0].aborted).toBe(true); expect(controller.getSnapshot().status).toBe('loading');
    b.resolve({ status: 'ready', data: 'B' }); await second;
    a.resolve({ status: 'ready', data: 'A' }); await first;
    expect(controller.getSnapshot()).toEqual({ status: 'ready', data: 'B' });
  });
  it('does not restore results after unmount', async () => {
    const task = deferred<ReadResult<string>>(); const controller = new ReadController(() => task.promise);
    const active = controller.run(null); controller.stop(); task.resolve({ status: 'ready', data: 'secret' }); await active;
    expect(controller.getSnapshot().status).toBe('loading');
  });
  it('distinguishes empty success, forbidden, stale and transport failure', async () => {
    for (const [status, expected] of [[403, 'forbidden'], [409, 'stale'], [0, 'error']] as const) {
      const controller = new ReadController(async () => { throw new ApiError(status, 'TEST', 'synthetic'); });
      await controller.run(null); expect(controller.getSnapshot().status).toBe(expected);
    }
    const controller = new ReadController(async () => ({ status: 'ready' as const, data: [] }));
    await controller.run(null); expect(controller.getSnapshot()).toEqual({ status: 'ready', data: [] });
  });
});
describe('command intentions', () => {
  it('blocks concurrent clicks and retries the same immutable payload/key after a lost response', async () => {
    const intent = new CommandIntent<{ value: string }>(() => 'stable-key');
    const waiting = deferred<string>(); const input = { value: 'original' };
    const first = intent.run(input, () => waiting.promise);
    expect(await intent.run({ value: 'double-click' }, async () => 'unexpected')).toBeUndefined();
    waiting.resolve('done'); expect(await first).toBe('done'); expect(intent.unresolved).toBe(false);
    await expect(intent.run(input, async () => { throw new Error('lost'); })).rejects.toThrow('lost');
    input.value = 'mutated';
    expect(await intent.run(input, async (saved, key) => ({ saved, key }))).toEqual({ saved: { value: 'original' }, key: 'stable-key' });
  });
});
