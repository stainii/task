import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Toasts } from './toasts';

/**
 * The one bottom corner (#67).
 *
 * `app.css` documented the corner as deliberately shared — *"only one is ever up"* — and that was
 * false: the overview owned one toast, the omnibox owned another, the templates list a third, and
 * nothing arbitrated. Complete a task on the overview, capture within eight seconds, and two of
 * them coexisted with the newer one painting underneath.
 *
 * The invariant is now the slot's rather than a comment's: there is one, and showing a toast
 * evicts whatever was in it.
 */
/** A verb nothing in these tests presses: they are about the slot, not about what the offer does. */
function noop(): void {
  // Deliberately empty.
}

describe('Toasts', () => {
  let toasts: Toasts;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({});
    toasts = TestBed.inject(Toasts);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('holds one toast at a time, and the newer one takes the corner', () => {
    toasts.show({ kind: 'undoable', what: 'Completed — Beddengoed wassen', undo: noop });
    toasts.show({
      kind: 'created',
      name: 'Bandenspanning',
      context: 'car',
      due: noop,
      details: noop,
    });

    expect(toasts.showing()?.kind).toBe('created');
  });

  it('takes the corner back when the horizon passes', () => {
    toasts.show({ kind: 'undoable', what: 'Completed — Beddengoed wassen', undo: noop });

    vi.advanceTimersByTime(Toasts.HORIZON_MS - 1);
    expect(toasts.showing()).not.toBeNull();

    vi.advanceTimersByTime(1);
    expect(toasts.showing()).toBeNull();
  });

  it('does not let an evicted toast take its replacement down with it', () => {
    // The failure this is written against: the first toast's timer fires while the second is up and
    // clears the slot, cutting the newer offer short by however long the older one had already
    // stood. Undo is the only correction path a wrong `completedOn` has, so the horizon is not
    // decoration.
    toasts.show({ kind: 'undoable', what: 'Completed — Beddengoed wassen', undo: noop });
    vi.advanceTimersByTime(Toasts.HORIZON_MS - 1_000);

    toasts.show({ kind: 'undoable', what: 'Completed — Bandenspanning', undo: noop });
    vi.advanceTimersByTime(1_000);

    expect(toasts.showing()?.kind).toBe('undoable');
    expect(toasts.showing()).toMatchObject({ what: 'Completed — Bandenspanning' });
  });

  it('is dismissed by the screen that raised it, and stays dismissed', () => {
    const mine = { kind: 'undoable', what: 'Completed — Beddengoed wassen', undo: noop } as const;
    toasts.show(mine);

    toasts.dismiss(mine);

    expect(toasts.showing()).toBeNull();
    vi.advanceTimersByTime(Toasts.HORIZON_MS);
    expect(toasts.showing()).toBeNull();
  });

  it('refuses to let one screen take down another screen\u2019s offer', () => {
    // The failure this is written against: the omnibox lives on the appbar and outlives every
    // screen, so a screen tidying up on its way out cleared offers it had never raised. Capture from
    // the omnibox, tap Templates, and the due chips went a beat after appearing.
    const overviews = {
      kind: 'undoable',
      what: 'Completed — Beddengoed wassen',
      undo: noop,
    } as const;
    toasts.show(overviews);

    const omniboxs = {
      kind: 'created',
      name: 'Ramen lappen',
      context: 'house',
      due: noop,
      details: noop,
    } as const;
    toasts.show(omniboxs);

    // The overview is destroyed by the navigation and says its piece on the way out.
    toasts.dismiss(overviews);

    expect(toasts.showing()).toBe(omniboxs);
  });
});
