/** One intention keeps its key and immutable input until a definitive response. Memory only. */
export class CommandIntent<T> {
  private active = false;
  private pending?: { key: string; input: T };
  constructor(private key: () => string = () => crypto.randomUUID()) {}
  get unresolved() { return this.pending !== undefined; }
  async run<R>(input: T, execute: (input: T, key: string) => Promise<R>): Promise<R | undefined> {
    if (this.active) return undefined;
    this.pending ??= { key: this.key(), input: structuredClone(input) };
    this.active = true;
    try {
      const result = await execute(this.pending.input, this.pending.key);
      this.pending = undefined;
      return result;
    } finally { this.active = false; }
  }
  /** Caller may discard only after a definite rejection, never a network/timeout/unknown failure. */
  rejected() { if (!this.active) this.pending = undefined; }
}
