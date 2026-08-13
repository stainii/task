import { fetchEventSource } from '@microsoft/fetch-event-source';
import { inject, Injectable, InjectionToken } from '@angular/core';

import { parseCursor, SyncCursor } from '../domain/sync';
import { TaskPatch } from '../domain/task';
import { LocalStore } from '../store/local-store';
import { AuthService } from './auth';
import { SyncApi } from './sync-api';
import { SyncStatus } from './sync-status';

/**
 * The transport, injectable so a test can drive one without a server.
 *
 * `@microsoft/fetch-event-source` rather than the platform's `EventSource`, and it is **load-bearing
 * rather than incidental**: native `EventSource` cannot send headers, so it can send neither
 * `Authorization: Bearer` nor a resume header, and ADR-0004 requires both.
 */
export const EVENT_SOURCE = new InjectionToken<typeof fetchEventSource>('event-source', {
  providedIn: 'root',
  factory: () => fetchEventSource,
});

/** Why one connection ended. */
type ConnectionOutcome =
  /** Ended cleanly — which for this server is the *normal* case, several times an hour. */
  | 'closed'
  /** The server said its cursor is unservable. Drop what came from it and re-snapshot. */
  | 'resync'
  | 'unauthenticated'
  | 'unreachable';

/**
 * The read side: the snapshot, and the stream that never stops being reopened.
 *
 * Three things about the boot path, all from ADR-0004:
 *
 * - **local state exists** → the app has already rendered from IndexedDB; open the stream from the
 *   stored cursor.
 * - **no local state** → snapshot, take its watermark, then stream from the watermark. Overlap is
 *   deliberately preferred over gaps: re-receiving a patch is a no-op by id, a gap is unrecoverable.
 * - **resync** → the same as first run, except the outbox survives it.
 *
 * **This class owns every reconnect, and the library's own retry is switched off** (`onerror`
 * throws). One reconnect path rather than two: the library retries in memory with the
 * `Last-Event-ID` it happens to hold, while this loop resumes from the cursor that was *persisted*,
 * which is the same cursor a cold boot would use and the only one that survives a browser kill.
 * Two mechanisms for one rule is how the two drift.
 *
 * The connection is expected to end every 15–30 minutes by design, so a clean close reconnects
 * immediately with a freshly-minted token. That is what makes the resume path self-testing.
 */
@Injectable({ providedIn: 'root' })
export class PatchStream {
  /** First retry after an unreachable server. */
  private static readonly MIN_BACKOFF_MS = 1_000;

  /**
   * The cap. A minute, not an hour: the app is used in bursts and the cost of being one minute late
   * to notice the server came back is a minute of a banner that is already true.
   */
  private static readonly MAX_BACKOFF_MS = 60_000;

  private readonly store = inject(LocalStore);
  private readonly api = inject(SyncApi);
  private readonly auth = inject(AuthService);
  private readonly status = inject(SyncStatus);
  private readonly eventSource = inject(EVENT_SOURCE);

  private running = false;
  private aborter: AbortController | null = null;
  private loop: Promise<void> | null = null;
  private wake: (() => void) | null = null;

  /**
   * Applied patches, one at a time.
   *
   * `onmessage` is synchronous and applying a patch is not, so two events arriving back to back
   * would otherwise refold the same task concurrently and write two cursors in whichever order the
   * transactions happened to commit — a cursor that goes *backwards* is a stream that re-delivers
   * what it already had, and one that goes forwards past an unapplied patch loses it for ever.
   */
  private applying: Promise<void> = Promise.resolve();

  /** Starts the loop. Idempotent — a second call is the nudge, not a second connection. */
  start(): void {
    if (this.running) {
      this.nudge();
      return;
    }
    this.running = true;
    // The loop is deliberately not awaited by anyone, so it may never reject: `run()` catches.
    this.loop = this.run();
  }

  /** Stops the loop and closes the connection. Awaits the loop, so a test can assert on what it left. */
  async stop(): Promise<void> {
    this.running = false;
    this.aborter?.abort();
    this.nudge();
    await this.loop;
    this.loop = null;
  }

  /** Retry now rather than at the end of the current backoff — the radio came back, or a login did. */
  nudge(): void {
    this.wake?.();
  }

  private async run(): Promise<void> {
    try {
      await this.reconnectForever();
    } catch (error) {
      // Only the store throws this far — a dead server is an *outcome* below, not an exception —
      // and nothing above this frame is awaiting the loop, so letting it out would produce an
      // unhandled rejection and no other effect at all.
      this.running = false;
      this.status.storeFailed(error);
    }
  }

  private async reconnectForever(): Promise<void> {
    let backoff = PatchStream.MIN_BACKOFF_MS;

    while (this.running) {
      const outcome = await this.connect();
      if (!this.running) {
        return;
      }

      if (outcome === 'resync') {
        // Everything below the cursor is now a different lineage of history. The outbox is kept:
        // see LocalStore#resetForResync.
        await this.store.resetForResync();
        this.status.changed();
        backoff = PatchStream.MIN_BACKOFF_MS;
        continue;
      }

      if (outcome === 'closed') {
        // The bounded-lifetime close. Reconnect at once, with a fresh token.
        backoff = PatchStream.MIN_BACKOFF_MS;
        continue;
      }

      await this.sleep(backoff);
      backoff = Math.min(backoff * 2, PatchStream.MAX_BACKOFF_MS);
    }
  }

  /** Opens one connection and stays on it until it ends. */
  private async connect(): Promise<ConnectionOutcome> {
    if (!this.status.online()) {
      return 'unreachable';
    }

    let cursor = await this.store.cursor();
    if (cursor === null) {
      const started = await this.snapshot();
      if (started !== 'ok') {
        return started;
      }
      cursor = await this.store.cursor();
      if (cursor === null) {
        return 'unreachable';
      }
    }

    return this.tail(cursor);
  }

  /**
   * First run and after a resync: read the whole open set, and the watermark it was read at.
   *
   * The watermark is written **before** the stream opens, so a crash between the two resumes from
   * the snapshot rather than from nothing.
   */
  private async snapshot(): Promise<'ok' | 'unauthenticated' | 'unreachable'> {
    try {
      const snapshot = await this.api.snapshot();
      await this.store.receivePatches(snapshot.tasks.flatMap((task) => task.history));
      await this.store.setCursor({ epoch: snapshot.epoch, sequence: snapshot.watermark });
      await this.status.succeeded();
      this.status.changed();
      return 'ok';
    } catch (error) {
      return this.failureOf(error);
    }
  }

  private tail(cursor: SyncCursor): Promise<ConnectionOutcome> {
    return new Promise((resolve) => {
      void (async () => {
        const token = await this.auth.token();
        const aborter = new AbortController();
        this.aborter = aborter;

        // A box rather than a `let`, because every assignment below happens inside a callback and
        // TypeScript's narrowing cannot see through one: on a plain variable it would conclude the
        // value is still `'closed'` and reject the checks that follow.
        const ended: { outcome: ConnectionOutcome } = { outcome: 'closed' };

        try {
          await this.eventSource(
            `/api/task-patches?since=${cursor.sequence}&epoch=${cursor.epoch}`,
            {
              signal: aborter.signal,
              // `?since=` and `?epoch=` always travel together — the server answers a bare cursor
              // with a `400` rather than defaulting the epoch to its own, which would produce a
              // cursor that can never be found stale.
              headers: token === null ? {} : { Authorization: `Bearer ${token}` },
              onopen: async (response) => {
                if (response.status === 401 || response.status === 403) {
                  ended.outcome = 'unauthenticated';
                  throw new Error('Not authenticated for the stream.');
                }
                if (!response.ok) {
                  ended.outcome = 'unreachable';
                  throw new Error(`The stream answered ${response.status}.`);
                }
                await this.status.succeeded();
              },
              onmessage: (event) => {
                if (event.event === 'resync') {
                  ended.outcome = 'resync';
                  aborter.abort();
                  return;
                }
                if (event.event === 'patch') {
                  this.applying = this.applying.then(() => this.receive(event.data, event.id));
                }
                // A heartbeat carries no id and no meaning beyond "still here". Deliberately not
                // treated as a sync: it proves the socket, not that anything was delivered.
              },
              // One reconnect path. Throwing here disables the library's own retry, so every
              // reconnection goes through `run()` with the persisted cursor and this class's
              // backoff.
              onerror: (error) => {
                throw error;
              },
            },
          );
        } catch {
          if (ended.outcome === 'closed') {
            ended.outcome = 'unreachable';
          }
        }

        // Whatever arrived is applied before this connection is called finished, so the next one
        // resumes from a cursor that describes what is actually in the store.
        await this.applying.catch(() => undefined);

        if (ended.outcome === 'unauthenticated') {
          this.auth.loginRequired.set(this.status.online());
        }
        if (ended.outcome === 'unreachable') {
          this.status.unreachable();
        }
        this.aborter = null;
        resolve(ended.outcome);
      })();
    });
  }

  /**
   * Applies one patch and advances the cursor.
   *
   * The cursor moves **after** the patch is committed, never before: the other order loses the
   * patch permanently if the write fails, and looks exactly the same from here.
   */
  private async receive(data: string, id: string): Promise<void> {
    const patch = JSON.parse(data) as TaskPatch;
    await this.store.receivePatches([patch]);
    const cursor = parseCursor(id);
    if (cursor !== null) {
      await this.store.setCursor(cursor);
    }
    await this.status.succeeded();
    this.status.changed();
  }

  private failureOf(error: unknown): 'unauthenticated' | 'unreachable' {
    const status =
      typeof error === 'object' && error !== null && 'status' in error ? error.status : undefined;
    if (status === 401 || status === 403) {
      this.auth.loginRequired.set(this.status.online());
      return 'unauthenticated';
    }
    this.status.unreachable();
    return 'unreachable';
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => {
      const timer = setTimeout(finish, ms);
      this.wake = finish;

      const clear = () => (this.wake = null);

      function finish(): void {
        clearTimeout(timer);
        clear();
        resolve();
      }
    });
  }
}
