import { dueIn, IsoDate } from './dates';
import { Importance, isImportant, Task } from './task';

/**
 * The ranking: what order tasks take **inside** a band of visible work.
 *
 * Portal's `todo/task.comparator.ts`, adopted whole with one term removed
 * ([ADR-0018](../../../../docs/adr/0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md)).
 * It is a points model rather than a sort on one field, and RES-011 records where the shape comes
 * from: the **Eisenhower four-quadrant** model, which is also where the importance buckets come
 * from.
 *
 * **Band membership trumps the score** (`bands.ts`). A high score never promotes a task that has
 * not started, and a low one never hides a task that is overdue. These points only ever order what
 * the bands have already decided is on screen.
 *
 * The one deletion is `+ expectedDurationInHours / 4`, which dies with its field (TODO-001) — one
 * additive term inside an already-clamped result, set on 5% of tasks.
 *
 * Client-side only, over data the client already holds, so it works identically offline.
 */

const MAX_URGENCY = 50;

/**
 * Urgency for a task with no due date at all.
 *
 * 20 points *is* "due in 30 days" — `50 − 30` — so portal's comment is literally true: an important
 * thing with no date is assumed to want doing within the month. This is what stops a task captured
 * from the omnibox, which has no due date, from sinking out of sight; `IMPORTANT` being the default
 * importance is the other half of it, and the two answers interlock.
 */
const UNDATED_URGENCY = 20;

const IMPORTANCE_POINTS: Record<Importance, number> = {
  I_DO_NOT_REALLY_CARE: 0,
  NOT_SO_IMPORTANT: 15,
  IMPORTANT: 30,
  VERY_IMPORTANT: 50,
};

/**
 * The extra weight an overdue task carries, by importance.
 *
 * Kept rather than folded into the flat 50, because once eight overdue tasks are on screen the flat
 * part tells them apart not at all, and ordering *among* them is the only job left.
 */
const OVERDUE_BONUS: Record<Importance, number> = {
  I_DO_NOT_REALLY_CARE: 5,
  NOT_SO_IMPORTANT: 10,
  IMPORTANT: 25,
  VERY_IMPORTANT: 30,
};

/** What one task scores today. Higher is more urgent-and-important. */
export function rankingPoints(task: Task, today: IsoDate): number {
  return urgency(task, today) + IMPORTANCE_POINTS[task.importance] + overdueBonus(task, today);
}

function urgency(task: Task, today: IsoDate): number {
  const days = dueIn(task.dueDate, today);
  if (days === null) {
    return isImportant(task.importance) ? UNDATED_URGENCY : 0;
  }

  if (days < 0) {
    // Flat, deliberately: a task a year late is not fifty times more urgent than one a day late,
    // and the bonus above is where the remaining discrimination lives.
    return MAX_URGENCY;
  }
  return Math.min(MAX_URGENCY, Math.max(0, MAX_URGENCY - days));
}

function overdueBonus(task: Task, today: IsoDate): number {
  const days = dueIn(task.dueDate, today);
  return days !== null && days < 0 ? OVERDUE_BONUS[task.importance] : 0;
}

/**
 * The comparator, highest score first, **ties broken by the earliest creation date**.
 *
 * Curried on `today` rather than reading a clock, so a caller sorting a whole screenful cannot have
 * the date change underneath it half way down the list.
 */
export function byRank(today: IsoDate): (a: Task, b: Task) => number {
  return (a, b) => {
    const difference = rankingPoints(b, today) - rankingPoints(a, today);
    if (difference !== 0) {
      return difference;
    }
    return a.creationDateTime.localeCompare(b.creationDateTime);
  };
}
