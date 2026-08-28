/**
 * The client's model of a task and its patches — the TypeScript half of the two-sided contract in
 * [ADR-0004](../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md).
 *
 * **Everything here is the wire shape, carried verbatim.** Dates stay ISO-8601 strings rather than
 * becoming `Date` objects, because the client's job is to fold and to render, not to reinterpret:
 * `Date` cannot hold a `LocalDate` without inventing a time zone, and a round-trip through one is
 * how `2026-03-01` becomes `2026-02-28` for anybody west of Greenwich. The one place a date is
 * genuinely compared — fold order — parses on the spot.
 *
 * Two fields of the back-end's `Task` are deliberately absent. `version` is the server's optimistic
 * lock; ADR-0004 keeps `409` off the wire entirely, so the client has no use for it and no honest
 * value to put in it.
 */

/** Mirrors `be.stijnhooft.task.backend.task.Importance`, in the same order (least to most). */
export const IMPORTANCES = [
  'I_DO_NOT_REALLY_CARE',
  'NOT_SO_IMPORTANT',
  'IMPORTANT',
  'VERY_IMPORTANT',
] as const;

export type Importance = (typeof IMPORTANCES)[number];

/**
 * The line between the two important grades and the two that are not.
 *
 * One predicate rather than the same two-way comparison written wherever it is needed. Portal had
 * it in two places — `task.comparator.ts` and `task.model.ts` — and they **disagreed**: the same
 * task sorted as middling and coloured as unimportant. The nullable column that caused that
 * particular disagreement is gone, but the shape that let two answers exist is this expression
 * being written twice, and the ranking and the importance buckets are still the two callers.
 */
export function isImportant(importance: Importance): boolean {
  return importance === 'IMPORTANT' || importance === 'VERY_IMPORTANT';
}

/** Mirrors `be.stijnhooft.task.backend.task.domain.TaskStatus`. */
export const TASK_STATUSES = ['OPEN', 'COMPLETED', 'CANCELLED'] as const;

export type TaskStatus = (typeof TASK_STATUSES)[number];

/**
 * A change to a set of a task's fields, minted by the client that made it.
 *
 * Immutable and append-only: nothing ever edits a patch. Undo appends another one carrying
 * {@link voids}, and the fold recomputes.
 *
 * **Two clocks, and neither may do the other's job.** {@link dateTime} is minted here and orders
 * the *fold*; {@link sequence} is assigned by the server on receipt and orders *delivery*.
 */
export interface TaskPatch {
  /**
   * Minted by the client, because an offline device needs an id before any server exists to ask
   * for one. It is therefore also the idempotency key.
   */
  readonly id: string;

  readonly taskId: string;

  /** The client's clock, as an ISO-8601 instant. Orders the fold, never delivery. */
  readonly dateTime: string;

  /**
   * The server's clock. Orders delivery, never the fold. Null from the moment this device mints a
   * patch until the server has taken it — which is exactly why the fold may not read it.
   */
  readonly sequence: number | null;

  /**
   * The id of the patch this one undoes, or null. The fold drops the voided patch's contribution
   * entirely; it does not compute a compensating value.
   */
  readonly voids: string | null;

  /**
   * Field name to new value, always as a string, and **null is a value**: absent means this patch
   * had no opinion, present-but-null means clear the field. Nothing may drop the nulls on the way
   * in or out.
   */
  readonly changes: Readonly<Record<string, string | null>>;
}

/**
 * A task is the fold of its own patch history. The fields are never the truth; {@link history} is,
 * and every one of them is recomputed from it whenever a patch arrives.
 */
export interface Task {
  readonly id: string;
  readonly name: string;
  /** An ISO-8601 instant, like a patch's date-time: the two are the same clock. */
  readonly creationDateTime: string;
  /** An ISO-8601 local date, `YYYY-MM-DD`. */
  readonly startDate: string;
  readonly dueDate: string | null;
  readonly context: string;
  readonly importance: Importance;
  readonly description: string | null;
  readonly status: TaskStatus;
  /** *When did I do it?* — a domain value, not a third sync clock. */
  readonly completedOn: string | null;
  /**
   * *When did I give up on it?* — always the day it was cancelled, and never editable
   * ([ADR-0022](../../../../docs/adr/0022-a-min-max-round-starts-when-you-closed-it.md)).
   *
   * A min/max round starts at the day the last task was **closed**, and a cancellation had no such
   * date. *"I cancelled this on Tuesday"* means nothing, which is why this gets no backdating
   * affordance where {@link completedOn} has one.
   */
  readonly cancelledOn: string | null;
  readonly taskTemplateId: string | null;
  readonly occurrenceId: string | null;
  /** Held in fold order, so `history[0]` is always the creation patch. */
  readonly history: readonly TaskPatch[];
}
