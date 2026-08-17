import { computed, effect, inject, Injectable } from '@angular/core';

import { LocalStore } from '../store/local-store';
import { Task, TaskPatch } from '../domain/task';
import { AuthService } from './auth';
import { Outbox } from './outbox';
import { PatchStream } from './stream';
import { SyncStatus } from './sync-status';
import { TemplateService } from './templates';

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

  /**
   * The template list, which is fetched rather than streamed.
   *
   * Templates are online-write-only and are not patched, so they have no place in the outbox or the
   * stream — but they are *read* offline by the reminding list, the omnibox's rows and ADR-0011's
   * mint, so the fetch rides with the same two moments the rest of sync starts on: boot, and the
   * radio coming back.
   */
  private readonly templates = inject(TemplateService);

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

  /** How many patches are waiting to go — FE-027's number, on the appbar (#58). */
  readonly queued = this.outbox.queued;

  /**
   * The browser's own answer about the radio, which says nothing about the server.
   *
   * Exposed beside {@link onlineButNotSyncing} rather than folded into it, because the appbar
   * indicator is about *this device* and the banner is about *the server*: a train tunnel is a
   * glyph and a dead server is a sentence, and they are two different facts.
   */
  readonly online = this.status.online;

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

  /** What the last reaction to the radio was about, so that only a *change* provokes another. */
  private radioWasOn = true;

  /**
   * Everything the radio coming back deserves, wherever the news came from.
   *
   * An effect on the fact rather than a call at each place that could discover it, and that is the
   * point: the browser's `online` event, the outbox's retry and the stream's retry can each be the
   * first to find out, and they race. Written as a hand-off from whichever one noticed, the other
   * two see a signal that is *already* true, conclude nothing changed, and the work silently
   * belongs to nobody — which is how the template fetch went missing in the first draft of
   * [#69](https://github.com/stainii/task/issues/69). The signal changing is the one event that
   * happens exactly once however it was learned.
   */
  private readonly radio = effect(() => {
    const on = this.status.online();
    const returned = on && !this.radioWasOn;
    this.radioWasOn = on;
    if (!returned || !this.started) {
      return;
    }
    this.stream.nudge();
    this.send();
    void this.templates.refresh();
  });

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
      // Both events do the same one thing, which is why they are registered together: **neither is
      // believed.** An event is the notice that the radio changed and `navigator.onLine` is what it
      // changed to, so each one is a prompt to go and ask. What to *do* about the answer is not
      // decided here either — see the `radio` effect above.
      for (const event of ['online', 'offline']) {
        window.addEventListener(event, () => this.status.refreshOnline(), {
          signal: listeners.signal,
        });
      }
    }

    this.stream.start();
    this.send();
    void this.templates.refresh();
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
    // Unawaited, and deliberately: the acknowledgement this method makes is *the patch is durably
    // in the outbox*, so a count that failed to be read may not turn a successful write into a
    // rejection. The drain sets the same number a moment later; this only makes the indicator move
    // at the moment of the act rather than one round trip after it.
    void this.outbox.refreshQueued();
    this.send();
    return task;
  }

  /**
   * Records several patches **in order**, and answers with the one an undo should name.
   *
   * The two callers are ADR-0014's two capture paths, and they mint the same patches by
   * construction — the templates list's ✓ and the omnibox's template rows both go through
   * `didItPatches`. Recording them was written out twice, comment and all, which is the duplication
   * the shared `DateConfirm` and `UndoToast` already argued against: the paths differ only in how
   * the thing was chosen.
   *
   * **The last patch is the one undo names**, and that is a rule rather than an off-by-one. Voiding
   * the *creation* of a task minted here would complete it instead — the fold cannot un-create — so
   * naming the completion is what actually takes it back.
   */
  async recordAll(patches: readonly TaskPatch[]): Promise<TaskPatch> {
    for (const patch of patches) {
      await this.record(patch);
    }
    return patches[patches.length - 1];
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

  /**
   * *Fix and retry*: queues a refused patch again and starts the pump.
   *
   * The pump is started here rather than in the outbox for the same reason every other trigger is:
   * this class owns when the app reaches the network, and the outbox only ever answers *what
   * happened when it did*.
   */
  async sendAgain(patchId: string): Promise<void> {
    await this.outbox.sendAgain(patchId);
    this.send();
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

      // A retry that trusts what the last `online` event said is not a retry: if that event was
      // missed, every pass refuses itself and the queue waits for ever (`SyncStatus#refreshOnline`).
      const before = this.status.online();
      this.status.refreshOnline();
      if (!before && this.status.online()) {
        // And the ladder climbed while the radio was off says nothing about a live link, so it is
        // dropped rather than carried. Without this, coming back at the cap costs a full minute of
        // waiting *before* the first attempt and another full minute if that attempt is unlucky,
        // which is not a ceiling anything can be derived from.
        backoff = SyncService.MIN_BACKOFF_MS;
      }
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
