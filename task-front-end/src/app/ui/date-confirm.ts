import { A11yModule } from '@angular/cdk/a11y';
import { ChangeDetectionStrategy, Component, input, linkedSignal, output } from '@angular/core';

import { IsoDate } from '../domain/dates';

/**
 * *When did you do it?* — the one-field confirm both capture paths converge on
 * ([ADR-0014](../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md)).
 *
 * **It exists once**, and that is the point. The omnibox and #61's templates list differ only in
 * how the thing was chosen; they meet here before anything is written, so the two paths cannot mint
 * a different `completedOn` for the same gesture.
 *
 * **Neither path fires silently**, which ADR-0014 amended itself to say. The first draft counted
 * the ✓'s single tap as an advantage and that was the wrong saving to chase: the whole reason this
 * action exists is that you did the thing *away from the app*, so a path that can only mean *today*
 * forces the same lie ADR-0011 was written to prevent — and throws away the min/max anchor's
 * accuracy with it, since `lastCompletionOf` reads this date. Portal's own form had a **required**
 * datepicker labelled *"When did you do it?"*, in the one screen that lived with this for years.
 *
 * A swipe on a row in front of you still completes silently. The line is *chosen by name asks,
 * acted on in place does not*.
 *
 * **It owns no keys and picks no place.** Both were given up in
 * [#67](https://github.com/stainii/task/issues/67): the shell paints it, from `Overlays.ask`, so
 * Escape reaches it by its being the topmost overlay rather than by a `document:` binding of its
 * own — there were two of those, and `TaskPage`'s navigates away.
 */
@Component({
  selector: 'app-date-confirm',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [A11yModule],
  template: `
    <button type="button" class="scrim" aria-label="Close" (click)="cancelled.emit()"></button>

    <section
      class="confirm-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="date-confirm-title"
      cdkTrapFocus
      [cdkTrapFocusAutoCapture]="true"
    >
      <h2 id="date-confirm-title">When did you do it?</h2>
      <!--
        The name is a fact and gets words (ADR-0019). Without it the confirm is a bare date field
        over a list that has already closed, and the thing it is about is a memory of a keystroke.
      -->
      <p class="what">{{ what() }}</p>

      <label>
        <span class="visually-hidden">Date</span>
        <!-- You cannot have done it tomorrow: the picker only ever collects a completion date, so
             the ceiling is today on every path that reaches it (issue #83). -->
        <input type="date" [value]="on()" [max]="today()" (input)="pick($event)" />
      </label>

      <div class="actions">
        <button type="button" class="dismiss" (click)="cancelled.emit()">Cancel</button>
        <button type="button" class="confirm" (click)="confirmed.emit(on())">Done</button>
      </div>
    </section>
  `,
  styleUrl: './date-confirm.css',
})
export class DateConfirm {
  /** What is being marked done, in words: a task's name, or #61's template. */
  readonly what = input.required<string>();

  /** Passed in rather than read from a clock, so the default and the screen agree. */
  readonly today = input.required<IsoDate>();

  readonly confirmed = output<IsoDate>();
  readonly cancelled = output<void>();

  /**
   * The date on the field, starting at today.
   *
   * A `linkedSignal` rather than an `effect` that assigns: reopening the confirm for a different
   * thing on a later day must show that day, and an effect writing into a signal is the propagation
   * Angular's own guidance rules out — the same call #59 made for the task dialog's draft.
   */
  protected readonly on = linkedSignal<IsoDate, IsoDate>({
    source: this.today,
    computation: (today) => today,
  });

  protected pick(event: Event): void {
    const picked = (event.target as HTMLInputElement).value;
    // An emptied date field is not a date. Falling back to today keeps *Done* always meaningful
    // rather than writing an empty `completedOn` the fold would carry as "no idea when". A typed
    // value slips past the field's `max`, so today is also the ceiling here — ISO dates compare
    // lexically.
    this.on.set(picked === '' || picked > this.today() ? this.today() : picked);
  }
}
