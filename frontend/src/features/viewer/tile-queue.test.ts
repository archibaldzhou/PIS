import { expect, it } from 'vitest';
import { TileQueue } from './tile-queue';
it('shares a fixed concurrency cap across main and navigator jobs and removes cancelled waiters', async () => {
 const queue = new TileQueue(2, 2); let active = 0, peak = 0, started = 0;
 const release: (() => void)[] = [];
 const work = () => { started++; active++; peak = Math.max(peak, active); return new Promise<number>(r => release.push(() => { active--; r(started); })); };
 const first = queue.run(work, new AbortController().signal), second = queue.run(work, new AbortController().signal);
 const cancel = new AbortController(); const cancelled = queue.run(work, cancel.signal);
 const assertion = expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
 const fourth = queue.run(work, new AbortController().signal);
 await expect(queue.run(work, new AbortController().signal)).rejects.toThrow('上限');
 cancel.abort(); await assertion; expect(started).toBe(2);
 release.shift()?.(); await first; await Promise.resolve(); await Promise.resolve();
 expect(started).toBe(3); expect(peak).toBe(2);
 while (release.length) release.shift()?.(); await Promise.all([second, fourth]); expect(active).toBe(0);
});
it('releases capacity after failure and never starts an already aborted job', async () => {
 const queue = new TileQueue(1, 2), c = new AbortController(); c.abort(); let called = false;
 await expect(queue.run(async () => { called = true; }, c.signal)).rejects.toMatchObject({ name: 'AbortError' }); expect(called).toBe(false);
 const failed = queue.run(async () => { throw Error('Synthetic transport failure'); }, new AbortController().signal);
 const success = queue.run(async () => 42, new AbortController().signal);
 await expect(failed).rejects.toThrow('Synthetic transport failure'); await expect(success).resolves.toBe(42);
});
