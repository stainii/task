import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

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
 */
@Component({
  selector: 'app-undo-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="undoable app-toast" role="status">
      <span class="what">{{ what() }}</span>
      <button type="button" class="undo app-toast-action" (click)="undo.emit()">Undo</button>
    </div>
  `,
})
export class UndoToast {
  // How long the offer stands is `Toasts.HORIZON_MS` since #67, and it is not here any more: this
  // component neither places itself nor times itself, so a horizon on it was a number owned by
  // something that could not act on it. Three screens reached through this class for it while
  // running three timers of their own.

  /** What was completed, in words — a task's name, or the definition a template's ✓ minted. */
  readonly what = input.required<string>();

  readonly undo = output<void>();
}
