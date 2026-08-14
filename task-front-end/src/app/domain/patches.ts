import { addDays, today } from './dates';
import { Task, TaskPatch } from './task';

/**
 * The writes the overview can make, as patches.
 *
 * **There is one write verb** ([ADR-0004](../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)):
 * complete, cancel, postpone and undo are not four endpoints, they are four sets of changes on the
 * same `POST /api/task-patches`. That is what lets every one of them work offline with nothing
 * special written for it.
 *
 * Ids are **minted here**, because an offline device needs one before any server exists to ask —
 * which also makes the id the idempotency key, and the name undo has to call the patch it voids.
 */

/**
 * A patch a panel made, with the words for the toast that follows it.
 *
 * The two travel together because every one of these actions **removes a row from the screen**, and
 * ADR-0015 answers that with an ~8 second toast carrying *Undo*: complete, cancel and postpone all
 * make a task leave, the overview does not show closed tasks, and without this a mis-swipe vanishes
 * with no trace at all. It is also the only path that exercises ADR-0004's void patch in normal use.
 */
export interface PanelAction {
  readonly patch: TaskPatch;
  /** What happened, in the past tense: *Completed*, *Cancelled*, *Postponed to tomorrow*. */
  readonly done: string;
}

/** How far each preset pushes a start date. */
export interface PostponePreset {
  readonly label: string;
  readonly days: number;
}

/**
 * The postpone offsets, **measured against six years of the author's real pushes** rather than
 * guessed (ADR-0018): exactly `+7 days` was used 55 times while `+2…6 days` was used 1,283, which
 * is what retired the original `Tomorrow / +1 week / pick a date`.
 *
 * All three move **forward**. Pulling a task back into today is a real act and a common one — 1,210
 * of those 3,726 pushes set the start date to the current day — but it is not postponing, and it
 * lives on the edit dialog as *ask me from*'s `Today`.
 */
export const POSTPONE_PRESETS: readonly PostponePreset[] = [
  { label: 'Tomorrow', days: 1 },
  { label: 'In 3 days', days: 3 },
  { label: 'Next week', days: 7 },
];

/**
 * Completing: the status, plus **the day the work happened**.
 *
 * `completedOn` is a domain value and not a third sync clock (ADR-0011) — it is what a min/max
 * template's anchor reads, so it is the date the person was standing on rather than a UTC one.
 */
export function completePatch(task: Task, now: Date): TaskPatch {
  return patchOn(task.id, now, { status: 'COMPLETED', completedOn: today(now) });
}

/**
 * Cancelling: the status, and deliberately **no `completedOn`**.
 *
 * Nothing in portal could set `CANCELLED`, so for years the only way to clear an abandoned task was
 * to press Complete — writing a false completion into the very history a template reads as its
 * clock. A cancellation that carried a completion date would reintroduce that by the back door.
 */
export function cancelPatch(task: Task, now: Date): TaskPatch {
  return patchOn(task.id, now, { status: 'CANCELLED' });
}

/**
 * Postponing: the start date, `days` from **today**, and never the due date.
 *
 * From today rather than from the date being replaced, because postponing is a statement about now
 * and not an increment on a date you have forgotten. The due date is left alone so the task comes
 * back still carrying its true overdue count: the app stops asking, it does not stop knowing.
 */
export function postponePatch(task: Task, days: number, now: Date): TaskPatch {
  return patchOn(task.id, now, { startDate: addDays(today(now), days) });
}

/**
 * Undoing: a patch that **names** the one it voids and says nothing else.
 *
 * The fold drops the voided patch's contribution entirely rather than computing a compensating
 * value, so there is nothing for this one to change — and an empty `changes` beside a `voids` is
 * exactly what the server's door allows.
 */
export function undoPatch(patch: TaskPatch, now: Date): TaskPatch {
  return { ...patchOn(patch.taskId, now, {}), voids: patch.id };
}

function patchOn(taskId: string, now: Date, changes: Record<string, string | null>): TaskPatch {
  return {
    id: crypto.randomUUID(),
    taskId,
    // The client's clock, which orders the fold. `sequence` is the server's and orders delivery;
    // it stays null until the server has taken this patch, and the fold may never read it.
    dateTime: now.toISOString(),
    sequence: null,
    voids: null,
    changes,
  };
}
