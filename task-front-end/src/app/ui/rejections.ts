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

    const created = task.history[0]?.id === patch.id;
    return [
      {
        patchId: patch.id,
        taskId: task.id,
        name: task.name,
        act: actOf(patch, created),
        when: whenOf(patch, now),
        why: whyOf(patch, created),
      },
    ];
  });
}

/**
 * What the patch did, said the way you would say it out loud.
 *
 * Read off the changes rather than stored, for the reason every folded field is: the patch is the
 * fact, and a second copy of *what this patch meant* is a second thing that can be wrong. The order
 * matters — a void patch carries no changes at all, and a creation is a name and a context, which is
 * indistinguishable from an edit by its changes alone.
 */
function actOf(patch: TaskPatch, created: boolean): string {
  if (patch.voids !== null) {
    return 'undone';
  }
  if (created) {
    return 'created';
  }
  if (patch.changes['status'] === 'COMPLETED') {
    return 'marked complete';
  }
  if (patch.changes['status'] === 'CANCELLED') {
    return 'cancelled';
  }
  if (Object.keys(patch.changes).length === 1 && 'startDate' in patch.changes) {
    return 'postponed';
  }
  return 'edited';
}

/**
 * Why this one going missing is worth a band above the day's work.
 *
 * Three sentences for three shapes, and only the first two are about something you can no longer
 * see: a closed task has left the overview, and a task the server never took exists on one device.
 */
function whyOf(patch: TaskPatch, created: boolean): string {
  if (created) {
    return 'It is on this device only — the server never took it.';
  }
  if (patch.changes['status'] === 'COMPLETED') {
    return 'You completed it on this device, so it has already left your list.';
  }
  if (patch.changes['status'] === 'CANCELLED') {
    return 'You cancelled it on this device, so it has already left your list.';
  }
  return 'The change is on this device only — the server still has the old value.';
}

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
