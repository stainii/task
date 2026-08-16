import { Injectable, signal } from '@angular/core';

import { IsoDate } from '../domain/dates';

/**
 * A question standing in the confirm slot, carrying its own way of being answered.
 *
 * The answer rides on the question rather than on the service, so there is no ambient *answer the
 * confirm* method that a second caller could reach for while somebody else's question is up.
 */
export interface Ask {
  /** What is being marked done, in words: a task's name, or a template's definition. */
  readonly what: string;
  /** The day the asking screen is measured against, so the default and the screen agree. */
  readonly today: IsoDate;
  /** The day it was done, or `null` for *cancelled*. */
  readonly answer: (on: IsoDate | null) => void;
}

/**
 * The app's **one** Escape owner ([#67](https://github.com/stainii/task/issues/67)).
 *
 * Before this, `TaskPage` and `DateConfirm` each bound `(document:keydown.escape)` unconditionally,
 * and `TaskPage`'s **navigates away** — so a confirm open over the task dialog took one press to
 * cancel the confirm *and* leave the dialog. It was unreachable only because the task-page scrim
 * happens to cover the appbar, which is an accident of paint rather than a rule.
 *
 * What replaces them is a stack. An overlay says it is open and gets a closer back; `App` binds the
 * only `document:keydown.escape` in the application and asks the topmost overlay to dismiss itself.
 * *Two unconditional listeners for one key is what breaks the next time an overlay is added* — so
 * adding one now costs a registration and nothing else.
 *
 * **Removal is by identity, not by popping.** Nothing guarantees the stack unwinds top-first: a
 * route can drop a screen while something opened over it is still up.
 */
@Injectable({ providedIn: 'root' })
export class Overlays {
  private layers: readonly (() => void)[] = [];

  /**
   * Says an overlay is open, and hands back the closer.
   *
   * The closer is what a component calls from `DestroyRef.onDestroy` or an `effect` cleanup, so an
   * overlay that goes away by any route — a dismissal, a navigation, a signal turning false — stops
   * owning the key without having to remember to.
   */
  open(dismiss: () => void): () => void {
    this.layers = [...this.layers, dismiss];
    return () => {
      this.layers = this.layers.filter((layer) => layer !== dismiss);
    };
  }

  /** One press, one overlay: the topmost, or nothing at all. */
  escape(): void {
    this.layers.at(-1)?.();
  }

  private readonly slot = signal<Ask | null>(null);

  /**
   * The question the shell is currently painting, or nothing.
   *
   * **One slot, in the shell.** ADR-0014 says the confirm *exists once* because the omnibox and the
   * templates list differ only in how the thing was chosen; until #67 that was one component
   * rendered from two places, which is one *class* and two instances. Rendering it here makes the
   * sentence literally true — and is what lifts its scrim out of the appbar's stacking context,
   * where `aria-modal="true"` was a promise the paint could not keep.
   */
  readonly asking = this.slot.asReadonly();

  /**
   * Asks *when did you do it?* and waits.
   *
   * The caller `await`s a date or `null`, which is the whole of the confirm's contract: the two
   * capture paths converge here before anything is written, so neither can mint a different
   * `completedOn` for the same gesture.
   */
  ask(what: string, today: IsoDate): Promise<IsoDate | null> {
    return new Promise<IsoDate | null>((resolve) => {
      const answer = (on: IsoDate | null): void => {
        close();
        this.slot.set(null);
        resolve(on);
      };
      // Escape is a cancellation, like every other dismissal in the app — and now it is that by
      // being the topmost overlay rather than by a listener of its own.
      const close = this.open(() => answer(null));
      this.slot.set({ what, today, answer });
    });
  }
}
