import { today } from '../domain/dates';
import { SyncFailure } from '../domain/sync';
import { Task, TaskPatch } from '../domain/task';
import { dateLabel } from './wording';

/**
 * A row of the rejected-changes band
 * ([ADR-0014](../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md), *A
 * rejected-change row names the act and the reason*).
 *
 * > **Boeken tandarts voor Elise — marked complete**
 * > Tuesday. You completed it on this device, so it has already left your list.
 * > `Fix and retry`  `Discard`
 *
 * **No HTTP status code**, and the reason is stronger than *it is technical*: `Rejected (400)` is
 * **constant** — a validation refusal is a 400 essentially always — so it would be a fixed phrase
 * repeated on every row of a band whose entire job is to say what went wrong *this time*, in the
 * position the eye reaches first. The technical detail stays recoverable on `/status`. Accepted
 * cost, put to the author and declined: nothing here separates *the server refused this* from *the
 * server never received it*.
 *
 * The band exists because of the case this file's `why` sentences are mostly about: **a rejected
 * completion has no task row anywhere**. The completion succeeded locally, the fold closed the task,
 * and the overview does not show closed tasks — so the thing the rejection belongs to has already
 * left the screen, and marking it on the task fails precisely where it matters most. Completions are
 * the commonest patch in the system.
 */
export interface RejectedChange {
  /** Identifies the row for both verbs; it is also what the outbox knows the failure by. */
  readonly patchId: string;
  readonly taskId: string;
  readonly name: string;
  /** What you did, as a person would say it: `marked complete`, `postponed`, `created`. */
  readonly act: string;
  /** When you did it, from the patch's own clock: `Today`, `Tuesday`, `6 Aug`. */
  readonly when: string;
  /** Why it matters that this one did not land — the half that differs per act. */
  readonly why: string;
}

/**
 * The band's contents: one row per refusal a human has to look at, oldest first.
 *
 * Two failures are deliberately not rows. A **`404` orphan** is a patch for a task the server has
 * never heard of, which ADR-0004 treats as a client bug rather than lost work — kept as evidence,
 * not worth interrupting a day over. And a failure whose **task has been pruned** cannot be spoken
 * about at all: the name and the act both live in the history that went with it, so there is no row
 * to make rather than a row with holes in it.
 */
export function rejectedChanges(
  failures: readonly SyncFailure[],
  tasks: readonly Task[],
  now: Date,
): RejectedChange[] {
  const byId = new Map(tasks.map((task) => [task.id, task]));

  return failures.flatMap((failure) => {
    if (failure.status === 404) {
      return [];
    }
    const task = byId.get(failure.taskId);
    const patch = task?.history.find((entry) => entry.id === failure.patchId);
    if (task === undefined || patch === undefined) {
      return [];
    }

    return [
      {
        patchId: patch.id,
        taskId: task.id,
        name: task.name,
        when: whenOf(patch, now),
        ...saidOf(patch, task.history[0]?.id === patch.id),
      },
    ];
  });
}

/**
 * **What the patch did and why its going missing matters — decided once, together.**
 *
 * One cascade rather than two, because the two halves branch on exactly the same cases: a fourth act
 * added to one and forgotten in the other is a row naming a completion and explaining an edit, and
 * nothing about that fails a build.
 *
 * The act is read off the changes rather than stored, for the reason every folded field is: the
 * patch is the fact, and a second copy of *what this patch meant* is a second thing that can be
 * wrong. **The order of the cases is load-bearing** — a void patch carries no changes at all, and a
 * creation is a name and a context, which is indistinguishable from an edit by its changes alone.
 */
function saidOf(patch: TaskPatch, created: boolean): { act: string; why: string } {
  const gone = (verb: string) => `You ${verb} it on this device, so it has already left your list.`;

  if (patch.voids !== null) {
    return { act: 'undone', why: STILL_HERE };
  }
  if (created) {
    return { act: 'created', why: 'It is on this device only — the server never took it.' };
  }
  if (patch.changes['status'] === 'COMPLETED') {
    return { act: 'marked complete', why: gone('completed') };
  }
  if (patch.changes['status'] === 'CANCELLED') {
    return { act: 'cancelled', why: gone('cancelled') };
  }
  if (Object.keys(patch.changes).length === 1 && 'startDate' in patch.changes) {
    return { act: 'postponed', why: STILL_HERE };
  }
  return { act: 'edited', why: STILL_HERE };
}

/** For everything that did **not** take the task off the screen: the row is still there, unchanged. */
const STILL_HERE = 'The change is on this device only — the server still has the old value.';

const WEEKDAYS = [
  'Sunday',
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
] as const;

/**
 * When you made the change, on the coarsening ladder `wording.ts` uses everywhere else.
 *
 * The patch's own `dateTime` rather than the moment of refusal: what you want to place is *the thing
 * you did*, and a change made on a train on Tuesday can be refused on Thursday when the radio comes
 * back — which is the ordinary case for this band, not an edge one.
 *
 * **Six days is the boundary, and it is the whole of the rung.** On the seventh the weekday names
 * come round again and *Thursday* means either of two days; the calendar date takes over there for
 * the same reason `dateLabel` adds a year as soon as one is not the current one.
 */
function whenOf(patch: TaskPatch, now: Date): string {
  const when = new Date(patch.dateTime);
  const days = daysBetween(when, now);
  if (days <= 0) {
    return 'Today';
  }
  if (days === 1) {
    return 'Yesterday';
  }
  if (days < 7) {
    return WEEKDAYS[when.getDay()];
  }
  return dateLabel(today(when), today(now));
}

/** Whole calendar days between two instants, in the browser's own zone. */
function daysBetween(from: Date, to: Date): number {
  const midnight = (at: Date) => Date.UTC(at.getFullYear(), at.getMonth(), at.getDate());
  return Math.round((midnight(to) - midnight(from)) / (24 * 60 * 60 * 1000));
}
