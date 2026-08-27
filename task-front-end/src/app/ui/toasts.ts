import { Injectable, signal } from '@angular/core';

import { IsoDate } from '../domain/dates';

/**
 * A just-made completion's date, and the two ways to move it within the horizon — issue #83's
 * variant A, all four fields or none.
 *
 * Present only behind a completion the overview panel made (a swipe, or a plain Complete tap): the
 * omnibox and the templates list already asked *when*, so their toasts carry no correction and the
 * *change day* row does not render. Bundled into one object so *both, or neither* is the type rather
 * than a comment — `on` alone would label the toast against a `today` it does not have.
 */
export interface CompletionCorrection {
  /** The day the completion is currently filed under. */
  readonly on: IsoDate;
  /** The day it is measured against, for the *done yesterday* / *done 9 Aug* label. */
  readonly today: IsoDate;
  /** *Yesterday* / *2 days ago* — a preset day, already resolved to a date. */
  readonly changeDay: (on: IsoDate) => void;
  /** *In the past…* — opens the shell's one confirm for anything older. */
  readonly pickDay: () => void;
}

/**
 * What the corner can be about.
 *
 * Two kinds rather than one, because the two offers are genuinely different: a capture is still
 * missing the due date it deliberately did not get, and a completion is still takeable back. Each
 * carries its own verbs, so the shell renders a toast without knowing which screen raised it.
 */
export type Toast =
  /** A capture, offering the due date ADR-0018 leaves off on purpose. */
  | {
      readonly kind: 'created';
      readonly name: string;
      readonly context: string;
      readonly due: (days: number) => void;
      readonly details: () => void;
    }
  /** Anything that removed a row from a screen that does not show closed tasks (ADR-0015). */
  | {
      readonly kind: 'undoable';
      readonly what: string;
      readonly undo: () => void;
      /** Issue #83, variant A — set only behind a completion the overview panel made. */
      readonly correction?: CompletionCorrection;
    };

/**
 * The app's **one** bottom corner ([#67](https://github.com/stainii/task/issues/67)).
 *
 * `app.css` already documented the corner as shared — *"only one is ever up"* — and it was not
 * true: the overview, the omnibox and the templates list each owned a toast, each positioned
 * itself, and nothing arbitrated between them. Complete a task on the overview, capture within
 * eight seconds, and two of them stood in the same place with the newer painting underneath.
 *
 * A comment cannot hold an invariant that no code enforces, so the slot does: there is one, showing
 * a toast evicts whatever was in it, and the shell paints whatever is in it. The screens keep the
 * verbs — undo is still the screen's business — and give up the corner.
 */
@Injectable({ providedIn: 'root' })
export class Toasts {
  /**
   * How long an offer stands.
   *
   * **Untested end to end, and named as such** — nothing in the suite waits eight seconds, and #60
   * paid for that once already: a due chip that "did nothing" was a toast that had quietly expired
   * between a screenshot and a click. It is one number because the slot is one slot; before #67 it
   * was one number reached through `UndoToast.HORIZON_MS` by a component that did not render it.
   */
  static readonly HORIZON_MS = 8_000;

  private readonly slot = signal<Toast | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly showing = this.slot.asReadonly();

  /**
   * Empties the corner **only if it still holds the toast you are talking about**.
   *
   * The reason it takes one rather than clearing whatever is there: the omnibox lives on the appbar
   * and outlives every screen, so a screen tidying up on its way out was clearing offers it had
   * never raised. Capture from the omnibox, tap Templates, and the due chips vanished a beat after
   * appearing — the corner is shared, so *clear the corner* is never something one caller may say.
   */
  dismiss(toast: Toast): void {
    if (this.slot() === toast) {
      this.clear();
    }
  }

  /**
   * Takes the corner, evicting whatever was there.
   *
   * The eviction cancels the old toast's timer as well as clearing its content. Without that, the
   * first toast's horizon would fire while the second is up and cut the newer offer short — and
   * undo inside the horizon is the only correction path a wrong `completedOn` has (ADR-0011).
   */
  show(toast: Toast): void {
    this.clear();
    this.slot.set(toast);
    this.timer = setTimeout(() => this.dismiss(toast), Toasts.HORIZON_MS);
  }

  /** Empties the corner whatever is in it. Eviction, and the horizon, and nothing else. */
  private clear(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.slot.set(null);
  }
}
