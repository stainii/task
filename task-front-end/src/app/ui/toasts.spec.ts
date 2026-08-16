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
    toasts.show({ kind: 'undo', what: 'Completed — Beddengoed wassen', undo: noop });
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
    toasts.show({ kind: 'undo', what: 'Completed — Beddengoed wassen', undo: noop });

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
    toasts.show({ kind: 'undo', what: 'Completed — Beddengoed wassen', undo: noop });
    vi.advanceTimersByTime(Toasts.HORIZON_MS - 1_000);

    toasts.show({ kind: 'undo', what: 'Completed — Bandenspanning', undo: noop });
    vi.advanceTimersByTime(1_000);

    expect(toasts.showing()?.kind).toBe('undo');
    expect(toasts.showing()).toMatchObject({ what: 'Completed — Bandenspanning' });
  });

  it('empties on demand, and stays empty', () => {
    toasts.show({ kind: 'undo', what: 'Completed — Beddengoed wassen', undo: noop });

    toasts.clear();

    expect(toasts.showing()).toBeNull();
    vi.advanceTimersByTime(Toasts.HORIZON_MS);
    expect(toasts.showing()).toBeNull();
  });
});
