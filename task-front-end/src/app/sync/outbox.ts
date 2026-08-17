import { computed, inject, Injectable, signal } from '@angular/core';

import { NOW } from '../clock';
import { SyncFailure } from '../domain/sync';
import { LocalStore } from '../store/local-store';
import { AuthService } from './auth';
import { SendOutcome, SyncApi } from './sync-api';
import { SyncStatus } from './sync-status';

/** Why a drain stopped. */
export type DrainOutcome =
  /** Nothing left to send. */
  | 'drained'
  /** Stalled on `401`/`403`, in order, with the queue intact. */
  | 'unauthenticated'
  /** Stalled on `5xx`, a dead link or no radio at all, in order, with the queue intact. */
  | 'unreachable';

/**
 * The write side: one ordered queue, drained strictly in order.
 *
 * **Strict order is what makes "the first patch is the create" safe** (ADR-0004) — and it has a
 * sharp edge, because one undeliverable patch would otherwise block everything behind it for ever
 * while the app still looks fine, local state being authoritative for display. Hence the rule this
 * class is mostly an expression of:
 *
 * | outcome | action |
 * | --- | --- |
 * | `2xx` | remove, advance |
 * | `400`/`404` | **drop, continue** — and put it where the user can see it |
 * | `401`/`403` | **stall, preserve order** — and when online, raise the login prompt |
 * | `5xx`/network | **stall, preserve order** — the caller retries with backoff |
 *
 * `401`/`403` are the exception that had to be carved out of *`4xx` is permanently wrong*: they say
 * nothing about the patch. Under the unamended rule, coming back online after a week away with an
 * expired refresh token discards the entire week of queued work into the failed-to-sync pile, one
 * patch at a time, without ever naming authentication as the cause.
 */
@Injectable({ providedIn: 'root' })
export class Outbox {
  private readonly store = inject(LocalStore);
  private readonly api = inject(SyncApi);
  private readonly auth = inject(AuthService);
  private readonly status = inject(SyncStatus);
  private readonly now = inject(NOW);

  /**
   * The patches the server refused permanently — FE-029's failed-to-sync list.
   *
   * ADR-0014 puts it on the overview as a band ([#58](https://github.com/stainii/task/issues/58)),
   * not behind `⋯`: a dropped patch is data loss, and the whole reason it is dropped rather than
   * retried is that no amount of retrying will help.
   */
  readonly failures = signal<readonly SyncFailure[]>([]);

  /**
   * The failures a human actually has to look at: the `400`s.
   *
   * A `400` is the client's own work going missing — a create that did not carry every field, a
   * value that does not parse — and nobody but the author can decide what it should have been. A
   * `404` is an orphan, which ADR-0004 treats as a client bug rather than a legitimate state: worth
   * keeping as evidence, not worth interrupting a day over. #58 renders this one.
   */
  readonly needsAttention = computed(() =>
    this.failures().filter((failure) => failure.status !== 404),
  );

  /**
   * **How many patches are waiting to go** — FE-027's number, and the appbar indicator's whole
   * input ([#58](https://github.com/stainii/task/issues/58)).
   *
   * New state, because nothing counted this before: `SyncStatus` holds `online`, `reachable`,
   * `lastSyncedAt` and `storeUnavailable`, and this class held `failures`. It lives here because
   * this is the class that knows when the queue changes, and it is read off the store rather than
   * incremented — a counter maintained by hand is a second answer that can drift from the queue it
   * is counting.
   *
   * FE-027 exists because forty offline changes looked exactly like zero in portal, which is why
   * this is a count and not a flag.
   */
  readonly queued = signal(0);

  private draining: Promise<DrainOutcome> | null = null;

  async restore(): Promise<void> {
    this.failures.set(await this.store.failures());
    this.queued.set(await this.store.pendingCount());
  }

  /**
   * Re-reads the queue's length.
   *
   * Called wherever something was written that a drain has not yet seen — a local patch — so the
   * indicator moves at the moment of the act rather than one network round trip later. **It reports
   * a store failure rather than throwing it**, per `SyncStatus`'s ordering rule: its callers are
   * fire-and-forget, and the app is already unusable when this is what fails.
   */
  async refreshQueued(): Promise<void> {
    try {
      this.queued.set(await this.store.pendingCount());
    } catch (error) {
      this.status.storeFailed(error);
    }
  }

  /** Forgets one failure. The patch stays in the task's history; only the notice goes. */
  async forget(patchId: string): Promise<void> {
    await this.store.forgetFailure(patchId);
    this.failures.set(await this.store.failures());
  }

  /**
   * *Fix and retry*: queues a refused patch again and takes its notice off the band.
   *
   * The notice goes because it has been **acted on**, not because the patch has succeeded — if the
   * server refuses it a second time it comes back through the ordinary drop path, which is the only
   * shape in which this cannot quietly lose the fact. Nothing here sends: `SyncService` owns the
   * pump, and this class never decides when to reach the network.
   */
  async sendAgain(patchId: string): Promise<void> {
    await this.store.sendAgain(patchId);
    await this.store.forgetFailure(patchId);
    this.failures.set(await this.store.failures());
    this.queued.set(await this.store.pendingCount());
  }

  /**
   * Sends what is queued, oldest first, until the queue is empty or something stops it.
   *
   * Single-flight, and re-reading the queue on every pass rather than taking a copy: a patch made
   * while a drain is running is picked up by that drain, so a write during a slow sync does not
   * have to wait for the next trigger.
   */
  drain(): Promise<DrainOutcome> {
    this.draining ??= this.drainOnce().finally(() => (this.draining = null));
    return this.draining;
  }

  private async drainOnce(): Promise<DrainOutcome> {
    for (;;) {
      // Read once and counted here rather than through a separate query: this is the one place that
      // sees the queue's length on every pass, so the indicator cannot disagree with the drain.
      const pending = await this.store.pending();
      this.queued.set(pending.length);
      const [next] = pending;
      if (next === undefined) {
        return 'drained';
      }

      // Offline is not a verdict on the patch, and it is not worth a request that cannot succeed.
      // Checked here rather than only reacting to the failure, so that a week offline is a week of
      // silence rather than a week of failed requests.
      if (!this.status.online()) {
        return 'unreachable';
      }

      const { outcome, status } = await this.api.send(next);
      if (outcome === 'accepted') {
        await this.store.stopSending(next.id);
        await this.status.succeeded();
        continue;
      }
      if (outcome === 'rejected' || outcome === 'orphan') {
        await this.drop(next.id, next.taskId, status);
        // The server answered, which is the fact the banners are about. It refused this patch; it
        // is not down.
        await this.status.succeeded();
        continue;
      }
      return this.stall(outcome);
    }
  }

  private async drop(patchId: string, taskId: string, status: number): Promise<void> {
    await this.store.stopSending(patchId);
    await this.store.recordFailure({ patchId, taskId, status, at: this.now().toISOString() });
    this.failures.set(await this.store.failures());
  }

  private stall(outcome: Extract<SendOutcome, 'unauthenticated' | 'unreachable'>): DrainOutcome {
    if (outcome === 'unauthenticated') {
      // The prompt is raised *because a sync needs it*, at the moment it is needed — and only when
      // there is something to prompt for. Offline the outbox simply waits.
      this.auth.loginRequired.set(this.status.online());
      return 'unauthenticated';
    }
    this.status.unreachable();
    return 'unreachable';
  }
}
