import { dueIn, IsoDate } from './dates';
import { isImportant, Task } from './task';

/**
 * The **importance bucket**: which quadrant a task falls in, drawn as the colour stripe down a
 * panel's left edge (FE-005).
 *
 * Portal's `task.model.ts`, with one rename and one deletion. Two of the names are about importance
 * and two about proximity — `long-game` is important but not near, `fit-in` is near but not
 * important — and telling *those two* apart is the whole reason the stripe exists.
 *
 * `goals` is renamed **`long-game`** (ADR-0006): `CONTEXT.md` reserves *goal* for a standing theme,
 * and the new name keeps the four reading as one family. Portal's `null` importance case is gone
 * with the nullable column — it was the case its two components disagreed about, the comparator
 * ranking it above `NOT_SO_IMPORTANT` while the buckets treated it as low.
 *
 * Client-side only: no column, no endpoint, no migration.
 */
export type ImportanceBucket = 'focus' | 'long-game' | 'fit-in' | 'back-burner';

/** How close a due date has to be to count as near. Portal's number, kept. */
const NEAR_DAYS = 7;

export function bucketOf(task: Task, today: IsoDate): ImportanceBucket {
  // An undated task is never near — there is no date to be near to — so it falls to the row below,
  // which for an important task is `long-game` and not the bottom of the screen.
  const days = dueIn(task.dueDate, today);
  const near = days !== null && days < NEAR_DAYS;

  if (isImportant(task.importance)) {
    return near ? 'focus' : 'long-game';
  }
  return near ? 'fit-in' : 'back-burner';
}
