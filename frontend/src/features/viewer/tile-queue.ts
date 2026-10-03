/** Bounded transport work. Aborted active jobs retain capacity until their transport settles. */
export class TileQueue {
 private active = 0;
 private waiting: (() => void)[] = [];
 constructor(private readonly limit = 4, private readonly capacity = 40) {}
 run<T>(work: () => Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
   if (signal.aborted) { reject(signal.reason); return; }
   if (this.active >= this.limit && this.waiting.length >= this.capacity) { reject(Error('瓦片等待队列达到上限')); return; }
   const cancel = () => { this.waiting = this.waiting.filter(x => x !== start); reject(signal.reason); };
   const start = () => {
    signal.removeEventListener('abort', cancel);
    if (signal.aborted) { reject(signal.reason); return; }
    this.active++;
    void Promise.resolve().then(work).then(resolve, reject).finally(() => {
     this.active--;
     while (this.active < this.limit && this.waiting.length) this.waiting.shift()?.();
    });
   };
   if (this.active < this.limit) start();
   else { this.waiting.push(start); signal.addEventListener('abort', cancel, { once: true }); }
  });
 }
}

// Shared across views and replacement instances: cancellation is not synchronous transport completion.
// Pending closures are removed on cancellation/settlement; no tile or authorization cache.
export const viewerTransport = new TileQueue(4, 80);
