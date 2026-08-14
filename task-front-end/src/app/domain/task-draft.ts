import { IsoDate } from './dates';
import { patchOn } from './patches';
import { Importance, Task, TaskPatch } from './task';

/**
 * The six fields the task dialog edits, and the diff that turns them back into a patch.
 *
 * **Six fields, flat** (ADR-0018): name, context, importance, due, *ask me from*, description.
 * Nothing else is here, because the dialog owns values and the panel owns verbs — repeating
 * complete and cancel inside a form would give `CANCELLED` a third entry point.
 *
 * The rules live here rather than in the component for the usual reason, and for one specific one:
 * *one Save writes one patch carrying every changed field* is a claim about the fold, not about a
 * form, and it can be stated without a DOM.
 */
export interface TaskDraft {
  readonly name: string;
  readonly context: string;
  readonly importance: Importance;
  readonly dueDate: IsoDate | null;
  /** The start date, called *ask me from* everywhere a person sees it. */
  readonly startDate: IsoDate;
  readonly description: string | null;
}

/** The task's current values, as the form's starting point. */
export function draftOf(task: Task): TaskDraft {
  return {
    name: task.name,
    context: task.context,
    importance: task.importance,
    dueDate: task.dueDate,
    startDate: task.startDate,
    description: task.description,
  };
}

/**
 * What actually changed, as patch changes.
 *
 * **Only the fields that moved.** The fold is last-writer-wins *per field*, so a Save that wrote
 * all six would make simply opening a task on a stale device and closing it again revert an edit
 * made on another one. `null` is a value here, exactly as it is on the wire: absent means *no
 * opinion*, present-and-null means *clear it*.
 */
export function changesOf(task: Task, draft: TaskDraft): Record<string, string | null> {
  const before = draftOf(task);
  const changes: Record<string, string | null> = {};
  for (const field of Object.keys(before) as (keyof TaskDraft)[]) {
    if (draft[field] !== before[field]) {
      changes[field] = draft[field];
    }
  }
  return changes;
}

/**
 * Whether this draft will not be asked about until after it is already due.
 *
 * **There is no start-before-due validation, on purpose** (ADR-0018). 4,678 of 11,579 real tasks
 * are in this state, and the reflex validation on a form like this would reject 40% of the author's
 * own history: it is the exact fingerprint of ADR-0015's *push start, never due*, where a postponed
 * overdue task is overdue **and** asleep. So the form **says** it and never forbids it.
 *
 * The same day is deliberately not this state — being asked on the day a thing is due is the
 * ordinary case, not the fingerprint.
 */
export function asksAfterItIsDue(draft: TaskDraft): boolean {
  return draft.dueDate !== null && draft.startDate > draft.dueDate;
}

/**
 * The whole of Save: **one patch carrying every changed field**, or nothing.
 *
 * One patch rather than one per field because a patch is an outbox entry, a sequence number and a
 * thing to undo — and the fold being last-writer-wins per field means six of them would merge to
 * exactly the same task for six times the traffic (ADR-0018).
 *
 * Returning `null` for an untouched form is the same argument at zero: an empty patch is a row in
 * the history saying that on this date somebody changed nothing.
 */
export function savePatch(task: Task, draft: TaskDraft, now: Date): TaskPatch | null {
  const changes = changesOf(task, draft);
  return Object.keys(changes).length === 0 ? null : patchOn(task.id, now, changes);
}
