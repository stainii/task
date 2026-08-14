import { daysUntil, IsoDate } from './dates';
import { byRank } from './ranking';
import { Task } from './task';

/**
 * Visible work: what the overview shows before you open anything
 * ([ADR-0006](../../../../docs/adr/0006-one-overview-grouped-by-a-swappable-axis.md), scoped to
 * started tasks by [ADR-0015](../../../../docs/adr/0015-postpone-pushes-the-start-date-and-the-fold-speaks.md)).
 *
 * > Of the tasks that have **started**: everything overdue or due today is always visible, however
 * > many that is; normal tasks then top the band up to five — `max(0, 5 − dueCount)` of them.
 *
 * A front-end rule over data the client already holds. No endpoint, no query parameter, no
 * back-end change — which is what makes it work identically offline.
 */

/**
 * **A day's feasible work, not a function of screen estate.**
 *
 * It does not scale with the viewport and it does not get laxer because you bought a monitor; the
 * fold below is capped for the same reason. Five is also a *floor* rather than a ceiling — the
 * first fold can exceed it, and only ever when everything in it is overdue or due today.
 */
export const CAP = 5;

export interface VisibleWork {
  /** The day's work: the whole due set, topped up in rank order. */
  readonly visible: readonly Task[];
  /** Started, not due, and over the top-up. `Also…`, and it starts folded. */
  readonly also: readonly Task[];
  /** Start date not arrived. `Starting in the future…`, and it starts folded. */
  readonly notStarted: readonly Task[];
  /** How many of {@link visible} are overdue or due today — what the top-up is subtracted from. */
  readonly dueCount: number;
  /** The due set alone is bigger than the cap, so the band retitles itself to say why. */
  readonly capExceeded: boolean;
}

/**
 * Splits everything this device holds into the three bands.
 *
 * `today` is passed in rather than read, because a screenful must be banded against one date: a
 * clock read per task can put two tasks on opposite sides of midnight.
 */
export function visibleWork(tasks: readonly Task[], today: IsoDate): VisibleWork {
  // Both closed statuses. Portal filtered `status != "COMPLETED"` only, so every cancelled task
  // stayed on the overview for ever — ranked, coloured and counted like live work.
  const open = tasks.filter((task) => task.status === 'OPEN').sort(byRank(today));

  const started = open.filter((task) => daysUntil(today, task.startDate) <= 0);
  const notStarted = open.filter((task) => daysUntil(today, task.startDate) > 0);

  const due = started.filter(
    (task) => task.dueDate !== null && daysUntil(today, task.dueDate) <= 0,
  );
  const rest = started.filter((task) => !due.includes(task));

  const topUp = Math.max(0, CAP - due.length);
  return {
    visible: [...due, ...rest.slice(0, topUp)],
    also: rest.slice(topUp),
    notStarted,
    dueCount: due.length,
    capExceeded: due.length > CAP,
  };
}
