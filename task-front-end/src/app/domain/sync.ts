/**
 * The sync contract's own shapes: where a client has got to, and what it could not send.
 *
 * The task and patch shapes live in [`task.ts`](./task.ts); these are the wrappers around them —
 * the cursor the stream resumes from and the snapshot it may have to start from.
 */

import { Task } from './task';

/**
 * Where a client has got to: which lineage of history it is on, and how far along it.
 *
 * **Both halves travel together, always.** `sequence` is only monotonic within one epoch, because
 * restoring a database backup moves the server's history *backwards*
 * ([ADR-0004](../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)). A bare
 * sequence is a cursor with no lineage, and the server answers one it cannot account for with a
 * resync rather than a stream that silently skips whatever the cursor could not name.
 *
 * On the wire it is the SSE event id, formatted `epoch:sequence`.
 */
export interface SyncCursor {
  readonly epoch: number;
  readonly sequence: number;
}

/** Parses an `epoch:sequence` event id, or null if it is not one. */
export function parseCursor(raw: string): SyncCursor | null {
  const separator = raw.indexOf(':');
  if (separator < 0) {
    return null;
  }
  const left = raw.slice(0, separator);
  const right = raw.slice(separator + 1);
  // **`Number('')` is 0**, not `NaN`, so an empty half has to be refused before it is parsed —
  // otherwise `3:` reads as sequence 0 and the client resumes from the beginning of history,
  // re-receiving everything, looking for all the world like it worked. `Number('1x')` is `NaN`,
  // which the integer check catches.
  if (left === '' || right === '') {
    return null;
  }
  const epoch = Number(left);
  const sequence = Number(right);
  if (!Number.isSafeInteger(epoch) || !Number.isSafeInteger(sequence)) {
    return null;
  }
  return { epoch, sequence };
}

/**
 * What `GET /api/tasks` returns: the open tasks with their history, **and the cursor they were read
 * at**.
 *
 * The watermark is the point. Without it a client does snapshot-then-stream and every patch landing
 * between the response completing and the stream attaching is lost forever — invisibly, since both
 * calls succeed. Only the histories are read: the folded fields beside them are the server's answer
 * to the same fold this client runs, and storing them would be storing a second opinion.
 */
export interface Snapshot {
  readonly epoch: number;
  readonly watermark: number;
  readonly tasks: readonly Task[];
}

/**
 * A patch the server refused permanently, kept so the user can see it.
 *
 * `400` is the one that matters: the client's own work went missing and a human has to decide what
 * to do about it. `404` is an orphan — a patch for a task the server has never heard of — which
 * ADR-0004 treats as a client bug rather than a legitimate state, so it is recorded rather than
 * merely dropped.
 */
export interface SyncFailure {
  readonly patchId: string;
  readonly taskId: string;
  readonly status: number;
  /** ISO-8601 instant, like every other date the client holds. */
  readonly at: string;
}
