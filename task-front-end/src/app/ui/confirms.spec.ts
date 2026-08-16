import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Confirms } from './confirms';
import { Overlays } from './overlays';

/**
 * The app's one *when did you do it?* confirm (ADR-0014, ADR-0020).
 *
 * ADR-0014 says it exists once because the omnibox and the templates list differ only in **how** the
 * thing was chosen and converge here before anything is written. Until #67 that was one class and
 * two instances; the slot is what makes the sentence true.
 */
describe('Confirms', () => {
  let confirms: Confirms;
  let overlays: Overlays;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    confirms = TestBed.inject(Confirms);
    overlays = TestBed.inject(Overlays);
  });

  it('puts the question in the slot and resolves with the day answered', async () => {
    const answered = confirms.ask('Beddengoed wassen', '2026-08-16');

    expect(confirms.asking()?.what).toBe('Beddengoed wassen');
    expect(confirms.asking()?.today).toBe('2026-08-16');

    confirms.asking()?.answer('2026-08-11');

    await expect(answered).resolves.toBe('2026-08-11');
    // The slot empties itself: the shell renders whatever is in it, so a question left behind is a
    // confirm that will not close.
    expect(confirms.asking()).toBeNull();
  });

  it('resolves to nothing when the confirm is cancelled', async () => {
    const answered = confirms.ask('Beddengoed wassen', '2026-08-16');

    confirms.asking()?.answer(null);

    await expect(answered).resolves.toBeNull();
    expect(confirms.asking()).toBeNull();
  });

  it('is the topmost overlay while it is up, so Escape cancels it and nothing else', async () => {
    const dismissed: string[] = [];
    overlays.open(() => dismissed.push('dialog'));
    const answered = confirms.ask('Beddengoed wassen', '2026-08-16');

    overlays.escape();

    await expect(answered).resolves.toBeNull();
    expect(dismissed).toEqual([]);
    // And the dialog underneath has the key back.
    overlays.escape();
    expect(dismissed).toEqual(['dialog']);
  });

  it('cancels the standing question rather than painting a second one over it', async () => {
    // One slot has to be one slot in the code as well as in the comment. Without this the first
    // question loses its screen but keeps its place on the Escape stack, and its caller waits for an
    // answer that can no longer be given — a promise that never settles and a key owner that never
    // goes away.
    const first = confirms.ask('Beddengoed wassen', '2026-08-16');
    const second = confirms.ask('Vuilbakken', '2026-08-16');

    await expect(first).resolves.toBeNull();
    expect(confirms.asking()?.what).toBe('Vuilbakken');

    // And exactly one overlay is left holding the key: Escape answers the second, and the stack is
    // then empty rather than still carrying the first.
    overlays.escape();
    await expect(second).resolves.toBeNull();
    expect(() => overlays.escape()).not.toThrow();
    expect(confirms.asking()).toBeNull();
  });

  it('withdraws on demand, answering its caller with nothing happened', async () => {
    const answered = confirms.ask('Beddengoed wassen', '2026-08-16');

    confirms.cancel();

    await expect(answered).resolves.toBeNull();
    expect(confirms.asking()).toBeNull();
    // Withdrawing nothing is not an error: the shell calls this on every navigation.
    expect(() => confirms.cancel()).not.toThrow();
  });
});
