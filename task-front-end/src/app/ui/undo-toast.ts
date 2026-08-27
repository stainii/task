import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
} from '@angular/core';

import { addDays } from '../domain/dates';
import { PAST_DAY_PRESETS } from '../domain/patches';
import { CompletionCorrection } from './toasts';
import { doneOnLabel } from './wording';

/**
 * *Completed — X* with an **Undo**, the toast behind a completion made **by name**.
 *
 * ADR-0015 puts a toast behind any action that removes a row, and the sharper reason is
 * [ADR-0011](../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)'s amendment:
 * undo-then-recomplete inside this toast is the **only** correction path `completedOn` has. The
 * overview shows no closed tasks and the omnibox refuses to offer one, so a wrong date is permanent
 * the moment this toast expires — a stated limit, taken knowingly, and this component is the whole
 * of the window in which it is not yet true.
 *
 * **It exists once**, for the same reason `DateConfirm` does: the omnibox and the templates list are
 * two capture paths for one action (ADR-0014), and a second toast with a second horizon would be two
 * different amounts of time in which the same mistake is correctable.
 *
 * **The *change day* row is issue #83's variant A.** A swipe-right and a plain Complete tap both
 * complete silently and dated today; this row is where that gets corrected within the horizon,
 * without a modal. It renders only when the owner passes a {@link correction} — the omnibox and the
 * templates list leave it null, because they already asked *when*.
 */
@Component({
  selector: 'app-undo-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="undoable app-toast" role="status">
      <span class="what">{{ what() }}</span>
      <button type="button" class="undo app-toast-action" (click)="undo.emit()">Undo</button>

      @if (correction(); as correction) {
        <div class="when">
          <span class="done"
            >done <b>{{ label() }}</b></span
          >
          @if (whenOpen()) {
            @for (preset of pastPresets; track preset.days) {
              <button
                type="button"
                class="change-day app-toast-action"
                (click)="correction.changeDay(on(preset))"
              >
                {{ preset.label }}
              </button>
            }
            <button
              type="button"
              class="change-day app-toast-action"
              (click)="correction.pickDay()"
            >
              In the past…
            </button>
          } @else {
            <button
              type="button"
              class="change-day-toggle app-toast-action"
              (click)="whenOpen.set(true)"
            >
              change day ▾
            </button>
          }
        </div>
      }
    </div>
  `,
  styles: `
    /* Its own line under the sentence: the sentence and Undo are the flex row .app-toast lays out,
       and this wraps below them (ADR-0015's toast stays one card, not two). */
    .when {
      flex-basis: 100%;
      display: flex;
      flex-wrap: wrap;
      gap: 8px 10px;
      align-items: center;
      margin-top: 8px;
      padding-top: 8px;
      border-top: 1px solid var(--app-line);
      font-size: 13px;
      color: var(--app-muted);
    }

    .when .done {
      margin-right: 2px;
    }
  `,
})
export class UndoToast {
  // How long the offer stands is `Toasts.HORIZON_MS` since #67, and it is not here any more: this
  // component neither places itself nor times itself, so a horizon on it was a number owned by
  // something that could not act on it. Three screens reached through this class for it while
  // running three timers of their own.

  /** What was completed, in words — a task's name, or the definition a template's ✓ minted. */
  readonly what = input.required<string>();

  /**
   * The just-made completion's date and the handlers that move it (issue #83), or `null` where this
   * is not a re-dateable completion — which is what keeps the whole row off the omnibox's and the
   * templates list's toasts.
   */
  readonly correction = input<CompletionCorrection | null>(null);

  readonly undo = output<void>();

  protected readonly pastPresets = PAST_DAY_PRESETS;

  /**
   * Whether the preset buttons are showing.
   *
   * A `linkedSignal` on {@link what} so it snaps shut when the slot is reused for the next
   * completion — the shell keeps one instance in this template position, so a plain signal would
   * carry the previous toast's open state into the new one.
   */
  protected readonly whenOpen = linkedSignal<string, boolean>({
    source: this.what,
    computation: () => false,
  });

  /** *done today* / *done yesterday* / *done 2 days ago* / *done 9 Aug*. */
  protected readonly label = computed(() => {
    const correction = this.correction();
    return correction === null ? null : doneOnLabel(correction.on, correction.today);
  });

  /** A preset's label resolved against the correction's own `today`. */
  protected on(preset: { days: number }): string {
    return addDays(this.correction()!.today, preset.days);
  }
}
