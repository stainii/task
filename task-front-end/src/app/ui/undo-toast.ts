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
    <div class="undoable" role="status">
      <span class="what">{{ what() }}</span>
      <button type="button" class="undo" (click)="undo.emit()">Undo</button>
    </div>
  `,
  styleUrl: './undo-toast.css',
})
export class UndoToast {
  /**
   * How long the offer stands.
   *
   * **Untested, and named as such** — nothing in the suite waits eight seconds, and #60 paid for it
   * once already: a due chip that "did nothing" turned out to be a toast that had quietly expired
   * between a screenshot and a click. It is one number here rather than one per surface precisely so
   * that when it does prove wrong it is wrong in one place.
   */
  static readonly HORIZON_MS = 8_000;

  /** What was completed, in words — a task's name, or the definition a template's ✓ minted. */
  readonly what = input.required<string>();

  readonly undo = output<void>();
}
