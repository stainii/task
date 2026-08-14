import { CAP } from './bands';
import { IsoDate, today } from './dates';
import { patchOn } from './patches';
import { byRank } from './ranking';
import { Task, TaskPatch } from './task';

/**
 * The omnibox's rules, away from the appbar that presents them.
 *
 * See [ADR-0014](../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md).
 */

/**
 * The open tasks whose name contains what was typed, most urgent first.
 *
 * **Open only.** The store holds closed tasks for a day so undo works cold, so they are really here
 * to be offered — and ADR-0011's amendment refused offering them by name, as functionality the
 * author does not want. The filter is therefore a decision, not a convenience.
 *
 * **Ordered by `byRank`**, the overview's own comparator, rather than by how well the name matches.
 * The app already has one answer to *which of these matters most today*, and a second one here
 * would be the shape that let portal's comparator and its buckets disagree for years — with the
 * added cost that the two would be visible a keystroke apart.
 *
 * **Capped at `bands.CAP`**, the overview's own number, *imported* rather than repeated: a second
 * literal `5` is exactly what quality-bar §5 means by a rule existing in more than one place, and
 * the two would drift green. The cap is what makes the ordering load-bearing rather than cosmetic
 * — on six years of real history a two-letter query matches hundreds of rows, and whichever fall
 * off the end are invisible. *ADR-0006 states the number for the overview; borrowing it for the
 * omnibox is decided by recommendation.*
 */
export function matchingTasks(tasks: readonly Task[], query: string, today: IsoDate): Task[] {
  const needle = query.trim().toLowerCase();

  // An empty needle is contained in every string, so without this the box opens onto the whole task
  // list before a key is pressed — and the omnibox is a thing you *type* into, not a thing you
  // browse. That is the templates list's job (#61).
  if (needle === '') {
    return [];
  }

  return tasks
    .filter((task) => task.status === 'OPEN' && task.name.toLowerCase().includes(needle))
    .sort(byRank(today))
    .slice(0, CAP);
}

/**
 * Capture: **one patch that is a whole task**, from the name alone.
 *
 * ADR-0015 settled that Enter mints the task rather than opening a form — routing a capture into a
 * form gives back the keystroke the omnibox exists for. So every field but the name and the context
 * is a default, and each of the three that could have been otherwise is a decision:
 *
 * - **No due date.** A default one lies about when the thing was needed, and would make ADR-0012's
 *   07:30 push announce tasks the author never dated. The write-only-inbox risk that follows is
 *   accepted and named for [#39](https://github.com/stainii/task/issues/39).
 * - **`IMPORTANT`.** The other half of the same answer: `ranking.ts` scores an undated task 20 only
 *   if it is important, so this is what stops a capture sinking out of sight the moment it is made.
 * - **Starting today**, so it is in the day's work rather than behind `Starting in the future…`.
 *
 * The patch carries every field the fold requires *and* `creationDateTime`, which is the server's
 * discriminator for a creating patch: without it the same body is an orphan and a `404`.
 */
export function capturePatch(name: string, context: string, now: Date): TaskPatch {
  return patchOn(crypto.randomUUID(), now, {
    name: name.trim(),
    creationDateTime: now.toISOString(),
    startDate: today(now),
    dueDate: null,
    context,
    importance: 'IMPORTANT',
    description: null,
    status: 'OPEN',
    completedOn: null,
  });
}

/**
 * The contexts the chips offer: every one this device has a task in, alphabetically.
 *
 * **Derived from the tasks rather than fetched**, which is what makes the chips present on a cold
 * boot with the radio off — the whole app renders from the local store, and a chip row that waited
 * on a response would be the one part of the capture path that did not.
 *
 * Closed tasks count. They are held for a day, and clearing the last open task in a context must
 * not make the chip you were reaching for vanish under your finger.
 */
export function contextsOf(tasks: readonly Task[]): string[] {
  return [...new Set(tasks.map((task) => task.context))].sort((a, b) => a.localeCompare(b));
}

/**
 * The context of the most recently created task, or null on a device holding nothing.
 *
 * *The last one you used*, answered from the data rather than from a literal. Found by driving the
 * real app: a device holding four contexts and no capture history offered an invented default,
 * because the only fallback was a constant — and the very first capture is the one most likely to
 * be made before any preference has been recorded.
 *
 * *Decided by recommendation.* ADR-0018's fallback chain stops at *the last one used*, and says
 * nothing about a device that has never used one.
 */
export function lastUsedContext(tasks: readonly Task[]): string | null {
  let latest: Task | null = null;
  for (const task of tasks) {
    if (latest === null || task.creationDateTime > latest.creationDateTime) {
      latest = task;
    }
  }
  return latest?.context ?? null;
}
