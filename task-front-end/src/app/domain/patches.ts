import { addDays, IsoDate, today } from './dates';
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

/**
 * How far a preset moves a start date, from today.
 *
 * Named for what it is rather than for its first caller: the same shape serves *ask me from*, whose
 * `Today` is precisely **not** a postpone — `CONTEXT.md` keeps the two words apart on purpose, and
 * a type called `DatePreset` sitting under `ASK_FROM_PRESETS` would put them back together.
 */
export interface DatePreset {
  readonly label: string;
  readonly days: number;
}

/**
 * The *ask me from* offsets, **measured against six years of the author's real pushes** rather than
 * guessed (ADR-0018): pushes to *today* 1,210, `+1 day` 552, `+2…6 days` 1,283 — which `In 3 days`
 * is the median of — and exactly `+7 days` only 55, which is what retired ADR-0015's proposed
 * `Tomorrow / +1 week / pick a date`.
 *
 * **`Today` is the un-postpone**, and the only one of the four that moves a date backwards. It is
 * the whole point of the edit dialog: postpone is forward-only, so pulling a sleeping task back
 * into the day's work has no other surface in the app.
 */
export const ASK_FROM_PRESETS: readonly DatePreset[] = [
  { label: 'Today', days: 0 },
  { label: 'Tomorrow', days: 1 },
  { label: 'In 3 days', days: 3 },
  { label: 'Next week', days: 7 },
];

/**
 * The panel's postpone offsets: **the same set minus `Today`**, exactly as ADR-0018 words it.
 *
 * Derived rather than written out again, because two literals is the shape that let portal's
 * `task.comparator.ts` and `task.model.ts` disagree for years about what a missing importance
 * means. `Today` is dropped because postpone moves forward only, where it would be a no-op.
 */
export const POSTPONE_PRESETS: readonly DatePreset[] = ASK_FROM_PRESETS.filter(
  (preset) => preset.days > 0,
);

/**
 * The create toast's due-date offsets: ADR-0018's `due today · tomorrow · in 3 days`.
 *
 * Derived from the same set as the other two rather than written out a third time — the app has one
 * vocabulary of date offsets, and three literals is three chances for two of them to drift. `Next
 * week` is dropped because the toast is the moment you have just typed the thing and know when it
 * is wanted; a week out is what the edit dialog is for.
 *
 * The toast exists because a captured task has **no due date at all**, which is deliberate — a
 * default one lies about when the thing was needed — and due date is the most-edited field in the
 * author's history at 1,397 edits. One tap here means the dialog never opens.
 */
export const DUE_PRESETS: readonly DatePreset[] = ASK_FROM_PRESETS.filter(
  (preset) => preset.days < 7,
);

/** Giving a captured task a due date, from the toast that followed it. */
export function dueDatePatch(taskId: string, days: number, now: Date): TaskPatch {
  return patchOn(taskId, now, { dueDate: addDays(today(now), days) });
}

/**
 * Completing: the status, plus **the day the work happened**.
 *
 * `completedOn` is a domain value and not a third sync clock (ADR-0011) — it is what a min/max
 * template's anchor reads, so it is the date the person was standing on rather than a UTC one.
 *
 * **`completedOn` defaults to today and is otherwise given**, which is ADR-0014's boundary as a
 * parameter: *chosen by name asks, acted on in place does not*. A swipe on a row in front of you
 * means now; typing a name into the omnibox is recording something that already happened, so that
 * path passes the date the confirm collected. Either way the **write** clock stays `now` — a
 * backdated `dateTime` would lose the fold to any later edit from another device.
 */
export function completePatch(task: Task, now: Date, completedOn: IsoDate = today(now)): TaskPatch {
  return patchOn(task.id, now, { status: 'COMPLETED', completedOn });
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

/**
 * Mints a patch. **The one place a patch is created**, so that the id, the two clocks and the
 * `voids` default cannot be got right in one caller and wrong in the next — the dialog's Save
 * (`domain/task-draft.ts`) mints through here too.
 */
export function patchOn(
  taskId: string,
  now: Date,
  changes: Record<string, string | null>,
): TaskPatch {
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
