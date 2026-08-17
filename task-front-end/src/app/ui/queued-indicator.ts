import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatTooltip } from '@angular/material/tooltip';

import { SyncService } from '../sync/sync';
import { Glyph } from './glyph';

/**
 * **What is waiting to go**, on the appbar (FE-027,
 * [ADR-0015](../../../../docs/adr/0015-postpone-pushes-the-start-date-and-the-fold-speaks.md)).
 *
 * | state | appbar |
 * | --- | --- |
 * | online, nothing queued | *nothing at all* |
 * | online, `n` queued | `Syncing 12…` |
 * | offline, nothing queued | the cloud-off glyph alone, quiet |
 * | offline, `n` queued | cloud-off glyph, then `40 waiting` |
 *
 * It exists because of one measured portal failure: **forty offline changes looked exactly like
 * zero.** So the number is the point, and a dot was disqualified for being the same pixels at three
 * and at forty — the defect reproduced inside the affordance built to fix it.
 *
 * **The count is always a word, and that is the load-bearing half.** The same number *on* the glyph
 * as a badge borrows the vocabulary of unread notifications — a thing demanding action — while this
 * affordance exists precisely because nothing is wrong and there is nothing to act on, which is also
 * why it lives here rather than in a band.
 *
 * **The asymmetry is deliberate.** Applying the glyph to both states was drawn and rejected on a
 * prominence inversion: with `[cloud-off] 40 waiting` beside `[syncing] 12 waiting` the words go
 * identical and a 16px icon becomes the only thing separating *nothing is going out* from *it is
 * going out right now* — the varying fact in the quietest channel, the constant one in the loudest.
 * The two states are mutually exclusive, so nobody ever sees the picture and the sentence together
 * and has to reconcile them.
 *
 * It reads state the client already holds. There is no endpoint here and there could not be one:
 * the question is about a queue that exists precisely because the server cannot be reached.
 */
@Component({
  selector: 'app-queued-indicator',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Glyph, MatTooltip],
  template: `
    @if (offline()) {
      <span
        class="indicator offline"
        [class.quiet]="queued() === 0"
        aria-label="Offline"
        matTooltip="Offline"
        ><app-glyph name="offline" />
        @if (queued() > 0) {
          <span class="count">{{ queued() }} waiting</span>
        }
      </span>
    } @else if (queued() > 0) {
      <span class="indicator quiet" role="status">Syncing {{ queued() }}…</span>
    }
  `,
  styles: `
    .indicator {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 10px;
      border: 1px solid var(--app-queued-line);
      border-radius: 999px;
      background: var(--app-queued-bg);
      color: var(--app-queued);
      font-size: 12.5px;
      font-weight: 600;
      white-space: nowrap;
    }

    /*
     * Nothing is wrong in either quiet state — one is a healthy drain, the other a train tunnel —
     * so the pill goes and the words stay. The pill is for the state that is both offline *and*
     * holding something.
     */
    .indicator.quiet {
      border-color: transparent;
      background: transparent;
      color: var(--app-muted);
      font-weight: 400;
    }

    .indicator app-glyph svg {
      width: 16px;
      height: 16px;
      display: block;
    }
  `,
})
export class QueuedIndicator {
  private readonly sync = inject(SyncService);

  /** How many patches are waiting. `Outbox` counts them; nothing else in the app did. */
  protected readonly queued = this.sync.queued;

  protected readonly offline = computed(() => !this.sync.online());
}
