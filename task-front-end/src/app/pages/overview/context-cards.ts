import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ContextBadge, contextCards } from '../../domain/contexts';
import { daysUntil, IsoDate } from '../../domain/dates';
import { Task } from '../../domain/task';
import { dueLabel } from '../../ui/wording';

/**
 * The card row above the bands
 * ([ADR-0006 §82](../../../../../docs/adr/0006-one-overview-grouped-by-a-swappable-axis.md)).
 *
 * **Clicking a card enters that context**, at `/in/:value` — filtering *is* entering the app, which
 * gives back portal's four-apps feeling without four routes, four modules or four deployments. A
 * column per context and a two-axis grid were both rejected for the same reason: each destroys the
 * single ranked list, leaving one *what's next* per column instead of one for the day.
 *
 * **The grouping axis is a property of this row alone.** Nothing below it knows the cards group by
 * context, which is what keeps a goal axis a change here rather than a rewrite — and the route says
 * `value` rather than `context` so no stored URL hard-codes the answer.
 *
 * The rules are in `domain/contexts.ts`; this draws them.
 */
@Component({
  selector: 'app-context-cards',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './context-cards.html',
  styleUrl: './context-cards.css',
})
export class ContextCards {
  /** Everything this device holds — **not** the entered scope, or the row would collapse to one card. */
  readonly tasks = input.required<readonly Task[]>();

  readonly today = input.required<IsoDate>();

  /** The context you are standing in, or nothing at `/`. */
  readonly entered = input<string>();

  /**
   * The ids the bands below are showing, so *what comes next* means what it says.
   *
   * Passed in rather than recomputed here: the cap of five is global at `/` and per-context once you
   * have entered one, and only the screen knows which it is applying.
   */
  readonly onScreen = input.required<ReadonlySet<string>>();

  protected readonly cards = computed(() =>
    contextCards(this.tasks(), this.today(), this.onScreen()),
  );

  /**
   * *what comes next **after** the visible work* — the first thing a card can tell you that the
   * bands below have not already said.
   *
   * Cards deliberately do not list their next few tasks: an earlier draft did, and it duplicated
   * almost the entire visible band inside the first fold.
   *
   * **A sleeping task is named and nothing more.** ADR-0015 is explicit that `Onderhoud ketels`, 62
   * days overdue and asleep, *can still be the name on this line* and that **nothing says it is
   * late** — so `next: Onderhoud ketels · 62 days overdue` would be the one surface breaking the
   * rule the whole reversal turns on. It is the same rule as the badge's, applied to the half of
   * the line that speaks about time rather than to the whole line: a task that has not started is
   * not taken into consideration by anything that speaks about urgency.
   *
   * *Decided by recommendation while building #58; recorded as an amendment to ADR-0006.*
   */
  protected next(task: Task): string {
    if (daysUntil(this.today(), task.startDate) > 0) {
      return `next: ${task.name}`;
    }
    return `next: ${task.name} · ${dueLabel(task.dueDate, this.today())}`;
  }

  /** `2 overdue`, `1 today` — a fact in words (ADR-0019), with the colour only backing it up. */
  protected badge(badge: ContextBadge): string {
    return `${badge.count} ${badge.kind}`;
  }
}
