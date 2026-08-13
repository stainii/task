import { computed, inject, Injectable } from '@angular/core';

import { LocalStore } from '../store/local-store';
import { Task, TaskPatch } from '../domain/task';
import { AuthService } from './auth';
import { Outbox } from './outbox';
import { PatchStream } from './stream';
import { SyncStatus } from './sync-status';

/**
 * The half of the app that talks to the server, and what happens when it cannot.
 *
 * Everything underneath is deliberately separable: {@link Outbox} is the write side,
 * {@link PatchStream} the read side, {@link SyncStatus} what both of them know about the world.
 * This class starts them, feeds them the browser's own signals, and is the one surface the rest of
 * the app sees.
 *
 * **Nothing here is a precondition for the app being usable.** {@link start} is called after the
 * first paint, the store having already rendered whatever this device holds. An expired token, a
 * dead server and a train tunnel all degrade the client to offline mode; none of them is a login
 * screen (ADR-0004: *authenticate to sync, not to see*).
 */
@Injectable({ providedIn: 'root' })
export class SyncService {
  private static readonly MIN_BACKOFF_MS = 1_000;
  private static readonly MAX_BACKOFF_MS = 60_000;

  private readonly store = inject(LocalStore);
  private readonly outbox = inject(Outbox);
  private readonly stream = inject(PatchStream);
  private readonly status = inject(SyncStatus);
  private readonly auth = inject(AuthService);

  /** Bumped whenever sync changed the local store. #57's overview reacts to this and re-reads. */
  readonly revision = this.status.revision;

  /** ADR-0009's first fact: when a sync last actually worked. */
  readonly lastSyncedAt = this.status.lastSyncedAt;

  /**
   * ADR-0009's first banner: **online but not syncing.**
   *
   * The two halves are what makes it need no threshold — a train tunnel is `online === false` and
   * says nothing, while a server that will not answer a device with a working radio is wrong
   * *immediately*. Exactly the case ADR-0004 is built to conceal, since the outbox stalls silently
   * by design.
   */
  readonly onlineButNotSyncing = computed(() => this.status.online() && !this.status.reachable());

  /** Something is queued that a human has to see: FE-029's failed-to-sync list. */
  readonly failures = this.outbox.needsAttention;

  /** A sync needs authentication and the device is online, so there is something to prompt for. */
  readonly loginRequired = this.auth.loginRequired;

  /**
   * The local store could not be reached at all — a private window, a quota refusal, a corrupt
   * database. Nothing works in that state: this app *is* its store. #63 has the screen for it.
   */
  readonly storeUnavailable = this.status.storeUnavailable;

  private pumping = false;
  private pump: Promise<void> | null = null;
  private wake: (() => void) | null = null;
  private started = false;

  /**
   * Unregisters the browser listeners on {@link stop}.
   *
   * Not tidiness: a stopped service that still reacts to `online` keeps draining an outbox nobody
   * asked it to drain, and every `start()` after a `stop()` adds another listener beside the last.
   */
  private listeners: AbortController | null = null;

  /**
   * Opens the store, restores what was known before the tab, and starts both loops.
   *
   * Idempotent, and it never throws for a reason the network is responsible for: the caller is the
   * app shell, and there is nothing it could usefully do about a server that is down.
   */
  async start(): Promise<void> {
    if (this.started) {
      return;
    }
    this.started = true;

    try {
      await this.store.ready();
      await this.status.restore();
      await this.outbox.restore();
    } catch (error) {
      this.status.storeFailed(error);
      return;
    }
    this.status.changed();

    if (typeof window !== 'undefined') {
      // The browser's own answer about the radio. It is not a promise that the server is up, which
      // is why `reachable` exists beside it — but going *offline* is worth knowing at once, because
      // it is the difference between waiting quietly and reporting a fault.
      const listeners = new AbortController();
      this.listeners = listeners;
      window.addEventListener(
        'online',
        () => {
          this.status.online.set(true);
          this.stream.nudge();
          this.send();
        },
        { signal: listeners.signal },
      );
      window.addEventListener('offline', () => this.status.online.set(false), {
        signal: listeners.signal,
      });
    }

    this.stream.start();
    this.send();
  }

  /**
   * Records a patch this device made, and asks for it to be sent.
   *
   * **This resolves only once the patch is durably in the outbox** (ADR-0004): a failed IndexedDB
   * write rejects here, so the caller can decline to acknowledge the edit. Otherwise a quota error
   * or an evicted store looks exactly like a successful tick, and no error report can fix that —
   * which is the whole of ADR-0009's argument against front-end telemetry.
   *
   * Sending is *not* awaited. The local write is the acknowledgement; the network is weather.
   */
  async record(patch: TaskPatch): Promise<Task | null> {
    const task = await this.store.recordLocalPatch(patch);
    this.status.changed();
    this.send();
    return task;
  }

  /** Raises the login prompt the stall asked for, and resumes both loops behind it. */
  async login(): Promise<void> {
    await this.auth.login();
    this.auth.loginRequired.set(false);
    this.stream.nudge();
    this.send();
  }

  /** #63's log-out item. The app keeps working afterwards: it renders from IndexedDB, not from a session. */
  logout(): Promise<void> {
    return this.auth.logout();
  }

  /** Forgets one failed-to-sync entry — the user has seen it. */
  forget(patchId: string): Promise<void> {
    return this.outbox.forget(patchId);
  }

  async stop(): Promise<void> {
    this.listeners?.abort();
    this.listeners = null;
    this.pumping = false;
    this.wake?.();
    await this.pump;
    await this.stream.stop();
    this.started = false;
  }

  /**
   * Drains the outbox, retrying with backoff for as long as it is stalled.
   *
   * The loop ends when the queue is empty rather than idling on a timer, so an app with nothing to
   * send makes no requests at all. Every trigger that could have produced work — a local write,
   * the radio returning, a login — restarts it.
   */
  private send(): void {
    if (this.pumping) {
      this.wake?.();
      return;
    }
    this.pumping = true;
    this.pump = this.drainUntilEmpty().finally(() => {
      this.pumping = false;
      this.pump = null;
    });
  }

  private async drainUntilEmpty(): Promise<void> {
    let backoff = SyncService.MIN_BACKOFF_MS;
    while (this.pumping) {
      let outcome;
      try {
        outcome = await this.outbox.drain();
      } catch (error) {
        // Nothing above this frame is awaiting the pump, so an escaping rejection is an unhandled
        // one and no more. The only thing that reaches here is the store failing — a `5xx` is an
        // outcome, not a throw — and no amount of backoff fixes that.
        this.status.storeFailed(error);
        return;
      }
      if (outcome === 'drained') {
        return;
      }
      await this.sleep(backoff);
      backoff = Math.min(backoff * 2, SyncService.MAX_BACKOFF_MS);
    }
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
