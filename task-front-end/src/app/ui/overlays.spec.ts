import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Overlays } from './overlays';

/**
 * The app's one Escape owner (#67).
 *
 * Before this there were two unconditional `document:keydown.escape` bindings — `TaskPage`'s, which
 * *navigates away*, and `DateConfirm`'s — so one press could cancel a confirm and leave the dialog
 * underneath it in the same breath. What replaces them is a stack: an overlay says it is open, and
 * Escape dismisses whichever is on top.
 */
describe('Overlays', () => {
  let overlays: Overlays;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    overlays = TestBed.inject(Overlays);
  });

  it('dismisses the topmost overlay, and only it', () => {
    const dismissed: string[] = [];
    overlays.open(() => dismissed.push('dialog'));
    overlays.open(() => dismissed.push('confirm'));

    overlays.escape();

    // The whole defect in one line: the confirm goes, the dialog underneath stays.
    expect(dismissed).toEqual(['confirm']);
  });

  it('hands Escape back to the overlay underneath once the top one closes', () => {
    const dismissed: string[] = [];
    overlays.open(() => dismissed.push('dialog'));
    const closeConfirm = overlays.open(() => dismissed.push('confirm'));

    closeConfirm();
    overlays.escape();

    expect(dismissed).toEqual(['dialog']);
  });

  it('does nothing when nothing is open', () => {
    expect(() => overlays.escape()).not.toThrow();
  });

  describe('the one confirm', () => {
    it('puts the question in the slot and resolves with the day answered', async () => {
      const answered = overlays.ask('Beddengoed wassen', '2026-08-16');

      expect(overlays.asking()?.what).toBe('Beddengoed wassen');
      expect(overlays.asking()?.today).toBe('2026-08-16');

      overlays.asking()?.answer('2026-08-11');

      await expect(answered).resolves.toBe('2026-08-11');
      // The slot empties itself: the shell renders whatever is in it, so a question left behind is
      // a confirm that will not close.
      expect(overlays.asking()).toBeNull();
    });

    it('resolves to nothing when the confirm is cancelled', async () => {
      const answered = overlays.ask('Beddengoed wassen', '2026-08-16');

      overlays.asking()?.answer(null);

      await expect(answered).resolves.toBeNull();
      expect(overlays.asking()).toBeNull();
    });

    it('is the topmost overlay while it is up, so Escape cancels it and nothing else', async () => {
      const dismissed: string[] = [];
      overlays.open(() => dismissed.push('dialog'));
      const answered = overlays.ask('Beddengoed wassen', '2026-08-16');

      overlays.escape();

      await expect(answered).resolves.toBeNull();
      expect(dismissed).toEqual([]);
      // And the dialog underneath has the key back.
      overlays.escape();
      expect(dismissed).toEqual(['dialog']);
    });
  });

  it('lets an overlay close out of order without stranding the key', () => {
    // Nothing guarantees the stack unwinds top-first: a route can drop a screen while a confirm
    // opened over it is still up. Removing by identity rather than by popping is what keeps the
    // remaining overlays reachable.
    const dismissed: string[] = [];
    const closeDialog = overlays.open(() => dismissed.push('dialog'));
    overlays.open(() => dismissed.push('confirm'));

    closeDialog();
    overlays.escape();

    expect(dismissed).toEqual(['confirm']);
  });
});
