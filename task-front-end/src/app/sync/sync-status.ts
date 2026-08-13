import { inject, Injectable, signal } from '@angular/core';

import { NOW } from '../clock';
import { LocalStore } from '../store/local-store';

/**
 * What the sync loop knows about itself — the facts ADR-0009's banners are made of.
 *
 * A separate service from the loop because both halves of the loop write to it: the outbox learns
 * the server is unreachable by being refused a write, the stream learns it by being refused a
 * connection, and a banner that only one of them could raise would be silent for whichever failure
 * happened first.
 *
 * **No thresholds here, and none anywhere.** These are facts; the two banners
 * ([#63](https://github.com/stainii/task/issues/63)) are the reading of them. *Warn if not synced
 * for three days* cries wolf on a holiday and stays silent through a week of bad signal, so
 * `online && !reachable` — network fine, server will not answer — is the condition, and it needs no
 * number.
 */
@Injectable({ providedIn: 'root' })
export class SyncStatus {
  private readonly store = inject(LocalStore);
  private readonly now = inject(NOW);

  /** The browser's own answer, which is about the radio and says nothing about the server. */
  readonly online = signal(typeof navigator === 'undefined' || navigator.onLine);

  /**
   * Whether the server answered the last time this client tried it.
   *
   * Starts true: a client that has not tried yet is not a client that has failed.
   */
  readonly reachable = signal(true);

  /**
   * When a sync last actually worked, as an ISO-8601 instant, or null if it never has.
   *
   * Mirrors the durable value in the store, which is what survives the tab.
   */
  readonly lastSyncedAt = signal<string | null>(null);

  /**
   * Bumped whenever sync changed what is in the local store.
   *
   * A revision rather than the tasks themselves: the store is the source of truth and re-reading
   * it is cheap at this app's size, whereas a second in-memory copy of the task list is a second
   * thing that can be wrong. #57's overview reacts to this.
   */
  readonly revision = signal(0);

  async restore(): Promise<void> {
    this.lastSyncedAt.set(await this.store.lastSyncedAt());
  }

  /** The server answered. */
  async succeeded(): Promise<void> {
    this.reachable.set(true);
    const at = this.now().toISOString();
    this.lastSyncedAt.set(at);
    await this.store.setLastSyncedAt(at);
  }

  /** The server did not answer, or answered `5xx`. */
  unreachable(): void {
    this.reachable.set(false);
  }

  changed(): void {
    this.revision.update((revision) => revision + 1);
  }
}
