import { Injectable, signal } from '@angular/core';

/**
 * One short-lived line of text, said by a screen that is about to stop existing.
 *
 * It exists because ADR-0018's redirect has nowhere else to speak: `/task/:id` for a closed task
 * sends you to the overview, and the component that knows *why* is unmounted by the very navigation
 * it is explaining. A screen that silently swaps itself out reads as a bug rather than a rule.
 *
 * Deliberately **not** the overview's undo toast. That one carries a verb, an ~8 second deadline
 * and a consequence for missing it; this one is a statement of fact you have already lived through,
 * with nothing to do about it. Merging them would put an `Undo` next to a sentence that cannot be
 * undone.
 */
@Injectable({ providedIn: 'root' })
export class Notices {
  /** Long enough to read a sentence, and the same horizon as the undo toast (ADR-0015). */
  private static readonly LINGER_MS = 8_000;

  private readonly said = signal<string | null>(null);

  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly message = this.said.asReadonly();

  say(message: string): void {
    this.dismiss();
    this.said.set(message);
    this.timer = setTimeout(() => this.said.set(null), Notices.LINGER_MS);
  }

  dismiss(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.said.set(null);
  }
}
