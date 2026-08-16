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
