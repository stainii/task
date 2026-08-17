import { vi } from 'vitest';

/**
 * Lets every pending promise settle.
 *
 * Needed wherever a test resumes a suspended `await` inside a component rather than calling into
 * it — answering the shell's confirm, pressing a verb on a toast — because what resumes then records
 * patches before it reaches its next visible step. A macrotask drains that whole chain, where
 * `whenStable` alone does not: a bare promise is not work Angular knows is pending.
 *
 * Test-only, and in `src/` for the same reason the mothers are: it is imported by specs beside the
 * code they cover, and the production bundle never reaches it.
 */
export function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * The radio itself, rather than what the app was last told about it.
 *
 * `navigator.onLine` is the browser's live answer and is always current; the `online` event is the
 * notification about it and is not always delivered. A test that only dispatches the event cannot
 * tell the two apart, which is how [#69](https://github.com/stainii/task/issues/69)'s gap survived —
 * so both sync loops are exercised through this instead, and `vi.restoreAllMocks()` puts it back.
 */
export function radio(on: boolean): void {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(on);
}

/**
 * Waits for a loop to reach a point a test can drive, without guessing at a delay.
 *
 * The two sync loops are the callers: both retry on a backoff, so neither can be asked to reach a
 * state synchronously, and both would otherwise grow a private copy of this that drifts from the
 * other. The budget is raised only where a backoff tick has to elapse first — `MIN_BACKOFF_MS` is a
 * second, so a state a retry has to *discover* cannot arrive sooner. It is a budget for failing in,
 * not a sleep: the common case returns on the first pass.
 */
export async function until(what: string, condition: () => boolean, attempts = 200): Promise<void> {
  for (let attempt = 0; attempt < attempts; attempt++) {
    if (condition()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error(`${what} never reached the expected state.`);
}
