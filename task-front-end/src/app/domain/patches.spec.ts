import { describe, expect, it } from 'vitest';

import {
  ASK_FROM_PRESETS,
  cancelPatch,
  completePatch,
  DUE_PRESETS,
  dueDatePatch,
  POSTPONE_PRESETS,
  postponePatch,
  undoPatch,
} from './patches';
import { today } from './dates';
import { aTask } from './task.mother';

/**
 * The four writes the overview can make. Every one of them is an ordinary patch on an ordinary
 * field — there is one write verb (ADR-0004), and *complete*, *cancel* and *postpone* are not three
 * endpoints but three sets of changes.
 */

/** 20:30 in Brussels on the 14th, which is still the 14th. */
const NOW = new Date('2026-08-14T18:30:00Z');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

describe('completePatch', () => {
  it('closes the task and records the day the work happened', () => {
    const patch = completePatch(aTask({ id: 'a' }), NOW);

    expect(patch.changes).toEqual({ status: 'COMPLETED', completedOn: '2026-08-14' });
  });

  it('dates the completion on the local calendar, not on UTC', () => {
    // 00:30 Brussels is 22:30 UTC the day before. `completedOn` is *when did I do it* — a domain
    // value, and the answer to that question is the date the person was standing on.
    const patch = completePatch(aTask(), new Date('2026-08-14T22:30:00Z'));

    expect(patch.changes['completedOn']).toBe('2026-08-15');
  });

  it('carries the task, a fresh id, the client’s clock and no sequence', () => {
    const patch = completePatch(aTask({ id: 'a' }), NOW);

    expect(patch.taskId).toBe('a');
    expect(patch.id).toMatch(UUID);
    expect(patch.dateTime).toBe(NOW.toISOString());
    // Assigned by the server on receipt. A client that filled it in would be posing as the server,
    // and the fold may never read it.
    expect(patch.sequence).toBeNull();
    expect(patch.voids).toBeNull();
  });

  it('takes the day the work happened when the user has said which day that was', () => {
    // *I ticked it off today but I did it last Tuesday.* Chosen by name — in the omnibox or the
    // templates list — always asks (ADR-0014), where a swipe on a row in front of you means *now*.
    const patch = completePatch(aTask({ id: 'a' }), NOW, '2026-08-11');

    expect(patch.changes).toEqual({ status: 'COMPLETED', completedOn: '2026-08-11' });
  });

  it('backdates the domain clock and never the write clock', () => {
    // The whole of ADR-0011's argument. `dateTime` orders the fold, so a backdated one would lose
    // to any later edit from another device: correcting a task's name on a laptop would silently
    // un-complete the chore.
    const patch = completePatch(aTask(), NOW, '2026-08-11');

    expect(patch.dateTime).toBe(NOW.toISOString());
  });

  it('mints a different id every time, because the id is the idempotency key', () => {
    const task = aTask();

    expect(completePatch(task, NOW).id).not.toBe(completePatch(task, NOW).id);
  });
});

describe('cancelPatch', () => {
  it('closes the task without claiming any of it was done', () => {
    // For years the only way to clear an abandoned task was to press Complete, which wrote a false
    // completion into the history ADR-0011 reads as the min/max clock anchor. So a cancellation
    // must never carry `completedOn` — `cancelledOn` is the date it does carry, and the two are
    // separate fields precisely so that this one cannot be read as *you did it* (ADR-0022).
    const patch = cancelPatch(aTask(), NOW);

    expect(patch.changes).toEqual({ status: 'CANCELLED', cancelledOn: today(NOW) });
    expect(patch.changes['completedOn']).toBeUndefined();
  });

  it('dates the cancellation today, with no way to say otherwise', () => {
    // A min/max round restarts at the day the last task was closed (#75), so declining a round you
    // were already three weeks late for still buys a full interval — measured from now, not from a
    // firing date that may be months old. There is deliberately no parameter: *"I cancelled this on
    // Tuesday"* means nothing, which is the whole reason `completedOn`'s affordance is not repeated
    // here.
    const patch = cancelPatch(aTask({ creationDateTime: '2026-06-01T09:00:00Z' }), NOW);

    expect(patch.changes['cancelledOn']).toBe(today(NOW));
  });
});

describe('postponePatch', () => {
  it('pushes the start date and never touches the due date', () => {
    // The app stops asking; it does not stop knowing. A boiler service 62 days overdue is still 62
    // days overdue when it comes back.
    const overdue = aTask({ dueDate: '2026-06-13', startDate: '2026-06-01' });

    const patch = postponePatch(overdue, 3, NOW);

    expect(patch.changes).toEqual({ startDate: '2026-08-17' });
  });

  it('measures the offset from today, not from the start date it is replacing', () => {
    // Postponing is a statement about now, not an increment on a date you have forgotten. A task
    // already sleeping until next month moves to *tomorrow*, not to the month after.
    const sleeping = aTask({ startDate: '2026-09-30' });

    expect(postponePatch(sleeping, 1, NOW).changes).toEqual({ startDate: '2026-08-15' });
  });

  it('offers the three presets the archive supports, and only forwards', () => {
    // Shaped against six years of real pushes rather than guessed: exactly +7 days was used 55
    // times, +2…6 days was used 1,283. `Today` is the un-postpone and lives on the edit dialog —
    // postpone only ever moves forward.
    expect(POSTPONE_PRESETS.map((preset) => [preset.label, preset.days])).toEqual([
      ['Tomorrow', 1],
      ['In 3 days', 3],
      ['Next week', 7],
    ]);
    expect(POSTPONE_PRESETS.every((preset) => preset.days > 0)).toBe(true);
  });
});

describe('ASK_FROM_PRESETS', () => {
  it('offers the four the archive supports, `Today` first', () => {
    // The measured distribution, not a guess: pushes to *today* 1,210, +1 day 552, +2…6 days 1,283
    // — which is what `In 3 days` is the median of — and exactly +7 days only 55, which is why
    // ADR-0015's proposed `+1 week` would have been the least-used control on the screen.
    expect(ASK_FROM_PRESETS.map((preset) => [preset.label, preset.days])).toEqual([
      ['Today', 0],
      ['Tomorrow', 1],
      ['In 3 days', 3],
      ['Next week', 7],
    ]);
  });

  it('is the postpone set plus the un-postpone, and nothing else', () => {
    // Stated against literals rather than by re-running the `filter` that defines `POSTPONE_PRESETS`
    // — that would restate the implementation and pass however either list changed. ADR-0018 words
    // the relationship as *the same set minus `Today`*, and this is that sentence as data.
    expect(ASK_FROM_PRESETS.map((preset) => preset.label)).toEqual([
      'Today',
      ...['Tomorrow', 'In 3 days', 'Next week'],
    ]);
    expect(POSTPONE_PRESETS.map((preset) => preset.label)).not.toContain('Today');
  });
});

describe('DUE_PRESETS', () => {
  it('offers the three the create toast names, and no further', () => {
    // ADR-0018 words the toast as `due today · tomorrow · in 3 days · Add details`. Derived from
    // the same set as everything else rather than written out a third time — the app has **one**
    // vocabulary of date offsets, which is what stops two of them drifting apart.
    expect(DUE_PRESETS.map((preset) => [preset.label, preset.days])).toEqual([
      ['Today', 0],
      ['Tomorrow', 1],
      ['In 3 days', 3],
    ]);
  });
});

describe('dueDatePatch', () => {
  it('sets the due date the toast offered, measured from today', () => {
    const patch = dueDatePatch('a', 1, NOW);

    expect(patch.taskId).toBe('a');
    expect(patch.changes).toEqual({ dueDate: '2026-08-15' });
  });
});

describe('undoPatch', () => {
  it('names the patch it voids and changes nothing itself', () => {
    // The fold drops the voided patch's contribution entirely rather than computing a compensating
    // value, so there is nothing for this patch to say.
    const completion = completePatch(aTask({ id: 'a' }), NOW);

    const undo = undoPatch(completion, NOW);

    expect(undo.voids).toBe(completion.id);
    expect(undo.changes).toEqual({});
    expect(undo.taskId).toBe('a');
    expect(undo.id).not.toBe(completion.id);
  });
});
