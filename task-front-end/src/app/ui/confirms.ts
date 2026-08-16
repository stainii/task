import { inject, Injectable, signal } from '@angular/core';

import { IsoDate } from '../domain/dates';
import { Overlays } from './overlays';

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
 * The app's **one** *when did you do it?* confirm
 * ([ADR-0020](../../../../docs/adr/0020-one-overlay-layer-and-one-owner-of-escape.md)).
 *
 * ADR-0014 says the confirm *exists once* because the omnibox and the templates list differ only in
 * how the thing was chosen; until #67 that was one **class** rendered from two places, which is two
 * instances. The shell renders this slot, so the sentence is now literally true — and that is also
 * what lifts its scrim out of the appbar's stacking context, where `aria-modal="true"` was a promise
 * the paint could not keep.
 *
 * A sibling of `Toasts` rather than part of `Overlays`: the stack is about *a key*, this is about
 * *a slot*, and one class doing both was one class changing for two reasons.
 */
@Injectable({ providedIn: 'root' })
export class Confirms {
  private readonly overlays = inject(Overlays);

  private readonly slot = signal<Ask | null>(null);

  /** The question the shell is currently painting, or nothing. */
  readonly asking = this.slot.asReadonly();

  /**
   * Asks *when did you do it?* and waits.
   *
   * The caller `await`s a date or `null`, which is the whole of the confirm's contract: the two
   * capture paths converge here before anything is written, so neither can mint a different
   * `completedOn` for the same gesture.
   *
   * **A second ask cancels the first**, rather than painting over it. There is one slot, so without
   * this the standing question would lose its screen *and* keep its place on the Escape stack, and
   * its caller would wait for an answer that can no longer be given. One slot has to be one slot in
   * the code as well as in the comment — that is the whole lesson of the corner this shipped with.
   */
  ask(what: string, today: IsoDate): Promise<IsoDate | null> {
    this.cancel();
    return new Promise<IsoDate | null>((resolve) => {
      const answer = (on: IsoDate | null): void => {
        close();
        this.slot.set(null);
        resolve(on);
      };
      // Escape is a cancellation, like every other dismissal in the app — and now it is that by
      // being the topmost overlay rather than by a listener of its own.
      const close = this.overlays.open(() => answer(null));
      this.slot.set({ what, today, answer });
    });
  }

  /**
   * Withdraws the standing question, answering its caller with *nothing happened*.
   *
   * The shell calls this on every navigation. A confirm belongs to the moment it was raised, not to
   * the app: hardware back, ADR-0012's 07:30 push and any deep link can move the screen out from
   * under it, and what would otherwise be left is an `aria-modal` dialog painted over a screen that
   * never asked, with a caller awaiting an answer that can no longer come.
   */
  cancel(): void {
    this.slot()?.answer(null);
  }
}
