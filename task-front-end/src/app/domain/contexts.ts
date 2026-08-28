import { bucketOf, ImportanceBucket } from './buckets';
import { daysUntil, dueIn, IsoDate } from './dates';
import { contextsOf } from './omnibox';
import { Task } from './task';

/**
 * The context cards above the bands
 * ([ADR-0006 §82](../../../../docs/adr/0006-one-overview-grouped-by-a-swappable-axis.md), amended by
 * [#82](https://github.com/stainii/task/issues/82)).
 *
 * **The axis is a property of this row alone.** Nothing below the cards knows the tasks are grouped
 * by context, which is what keeps a second axis — a goal — a change to this file rather than a
 * rewrite of the bands. That is the one decision discharging
 * [#4](https://github.com/stainii/task/issues/4)'s *design for a second axis and do not build one*.
 *
 * A front-end rule over data the client already holds, like the bands and the ranking: no endpoint,
 * no query parameter, and identical behaviour offline.
 *
 * **#82 shrank each card to a pill.** The full-width card carried a six-segment colour bar and a
 * *what comes next* line; on a phone with 5–7 contexts the day's work started a scroll below the
 * fold. What is left is a dominant-bucket **dot**, the context name, the true-total count, and a
 * worded badge only when started work is late or due. The bar and the next line were **dropped, not
 * relocated** — four treatments were driven on `prototype/context-row-scope-line` and the author
 * chose the one that keeps the `/in/:value` scope line exactly as it was.
 */

/** What the badge claims, or nothing at all. **Started tasks only** — see {@link contextCards}. */
export interface ContextBadge {
  readonly kind: 'overdue' | 'today';
  readonly count: number;
}

export interface ContextCard {
  /** The context itself. Named `value` for the route it links to, `/in/:value`, which does not name the axis. */
  readonly value: string;
  /** Everything open in the context — **a true total**, sleeping work included. */
  readonly count: number;
  /** The urgency claim, and the one part of the card scoped to started work. */
  readonly badge: ContextBadge | null;
  /**
   * The importance bucket of the **soonest** task in the context — the dot's colour.
   *
   * All that is left of the six-segment bar (#82). Like the bar, it describes the context rather
   * than started work, so a sleeper that is the soonest thing here still sets it.
   */
  readonly dominant: ImportanceBucket;
}

/**
 * One card per context this device holds open work in, alphabetically.
 *
 * **Only the badge is scoped to started tasks**
 * ([ADR-0015](../../../../docs/adr/0015-postpone-pushes-the-start-date-and-the-fold-speaks.md), *The
 * honesty valve is removed*). One rule states the whole asymmetry:
 *
 * > A task that has not started is not taken into consideration by anything that speaks about
 * > urgency.
 *
 * The count is a total and the dot is coloured from the soonest task asleep or not, because both
 * describe the **context**. Only the badge makes a claim about urgency, and only that claim has to
 * survive being clicked into: `house — 1 overdue` above a context you can enter and find nothing
 * overdue in is a card that has stopped being believable, and pressure you learn to distrust is not
 * pressure.
 *
 * The cost is recorded rather than mitigated: a sufficiently postponed task raises no badge anywhere.
 * Seven compensating candidates were put up and all seven declined — see that ADR before proposing
 * an eighth.
 *
 * @param tasks everything this device holds — **not** the entered scope, or the row collapses to the
 *   one card you are already standing in
 */
export function contextCards(tasks: readonly Task[], today: IsoDate): ContextCard[] {
  // Open work only, and that is the one place this differs from the omnibox's chips: a chip for a
  // context you have just cleared is still somewhere to capture *into*, where a card for it here
  // would describe nothing.
  const open = tasks.filter((task) => task.status === 'OPEN');
  return contextsOf(open).map((value) => card(value, open, today));
}

function card(value: string, open: readonly Task[], today: IsoDate): ContextCard {
  const mine = open.filter((task) => task.context === value);
  // `contextsOf` only yields a context that has open work, so `mine` is never empty and there is
  // always a soonest task to take the dot's colour from.
  const soonest = [...mine].sort(bySoonest);

  return {
    value,
    count: mine.length,
    badge: badgeOf(mine, today),
    dominant: bucketOf(soonest[0], today),
  };
}

/**
 * Overdue if anything is, otherwise what is due today, otherwise silence.
 *
 * Two states rather than one combined number, because *overdue* and *due today* are the same **set**
 * for the purpose of being visible work (`bands.ts`) and emphatically not the same **fact** on a
 * card: one is a debt and the other is a plan.
 */
function badgeOf(mine: readonly Task[], today: IsoDate): ContextBadge | null {
  const started = mine.filter((task) => daysUntil(today, task.startDate) <= 0);
  const overdue = started.filter((task) => (dueIn(task.dueDate, today) ?? 1) < 0).length;
  if (overdue > 0) {
    return { kind: 'overdue', count: overdue };
  }
  const due = started.filter((task) => dueIn(task.dueDate, today) === 0).length;
  return due > 0 ? { kind: 'today', count: due } : null;
}

/**
 * Soonest first, **undated last**.
 *
 * A task with no due date is not soon — there is no date for it to be near — which is `buckets.ts`'s
 * own reasoning for never calling it `focus`, applied to the order rather than to the colour.
 *
 * Compared as strings, which is what `YYYY-MM-DD` is for: two dates in that shape sort
 * lexicographically in calendar order, with no arithmetic and therefore no boundary to be wrong
 * about (`dates.ts`).
 */
function bySoonest(a: Task, b: Task): number {
  return (a.dueDate ?? NEVER).localeCompare(b.dueDate ?? NEVER);
}

/** Where an undated task sorts: after every date there is. Never compared to, only sorted with. */
const NEVER = '9999-12-31';
