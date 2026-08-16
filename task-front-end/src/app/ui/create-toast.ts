import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { DUE_PRESETS } from '../domain/patches';

/**
 * *Added “X” in Y*, with the due date the capture deliberately did not get (ADR-0018).
 *
 * A captured task has **no due date** on purpose — a made-up one lies about when the thing was
 * needed, and would make ADR-0012's 07:30 push announce tasks the author never dated. Due date is
 * the most-edited field in six years (1,397 edits), so one tap here is what keeps the edit dialog
 * shut for the ordinary case.
 *
 * **Its own component since [#67](https://github.com/stainii/task/issues/67)**, for the same reason
 * `UndoToast` is one: the corner is a single slot painted by the shell, and the screen that raised
 * the toast keeps the verbs without keeping the coordinate. While this markup lived in the omnibox
 * it was painted inside `.appbar`, whose stacking context clamped it under the overview's own toast.
 */
@Component({
  selector: 'app-create-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="created" role="status">
      <span class="what">Added “{{ name() }}” in {{ context() }}</span>
      @for (chip of chips; track chip.days) {
        <button
          type="button"
          class="due"
          [attr.data-days]="chip.days"
          (click)="due.emit(chip.days)"
        >
          {{ chip.label }}
        </button>
      }
      <button type="button" class="details" (click)="details.emit()">Add details</button>
    </div>
  `,
  styleUrl: './create-toast.css',
})
export class CreateToast {
  /** What was captured, and where it landed — the two halves of the sentence. */
  readonly name = input.required<string>();
  readonly context = input.required<string>();

  /** How many days out, straight from the presets. */
  readonly due = output<number>();
  readonly details = output<void>();

  /**
   * `due today · tomorrow · in 3 days`, worded as ADR-0018 words them.
   *
   * The first carries the word that makes the row a sentence; the rest inherit it. Lower-cased
   * against the presets rather than written out again — the labels are the same vocabulary the
   * dialog and the panel use, and a second copy is a second thing to keep in step.
   *
   * A plain field rather than a `computed`: `DUE_PRESETS` is a constant, so there is nothing here
   * that can change and nothing to invalidate.
   */
  protected readonly chips = DUE_PRESETS.map((preset) => ({
    days: preset.days,
    label: preset.days === 0 ? 'due today' : preset.label.toLowerCase(),
  }));
}
