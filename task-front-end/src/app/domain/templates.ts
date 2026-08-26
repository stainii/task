import { CAP } from './bands';
import { IsoDate } from './dates';
import { byRank } from './ranking';
import { Task } from './task';
import { TaskTemplate } from './template';

/**
 * The templates list's rules, away from the screen that presents them.
 *
 * **The list is the reminding surface**
 * ([ADR-0014](../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md)): typing
 * assumes you know what you did, and the author does not always — *"I like that you can go to the
 * templates, see when it's last done and hit a button."*
 *
 * Everything here is answered from tasks the client already holds. That is not a workaround for a
 * missing endpoint: ADR-0011 made completion **a fact about a task**, which the template *reads*,
 * so asking the tasks is the query. It is also what makes the whole list work with the radio off.
 */

/** One row of the list: the template, and the two facts the row says out loud. */
export interface TemplateRow {
  readonly template: TaskTemplate;
  /**
   * The task this template is currently asking for, or null when it is showing nothing.
   *
   * The row's whole state hangs off it. With one, the template is **due** and the ✓ completes that
   * task; with none, the ✓ mints ADR-0011's second shape — a task created and completed in the same
   * breath.
   */
  readonly openTask: Task | null;
  /** *When did I last actually do this?* — the date, so the row can say it as words and a memory. */
  readonly lastCompletedOn: IsoDate | null;
}

/**
 * **The latest `completedOn` among this template's completed tasks** — ADR-0011's `lastCompletionOf`
 * on the client.
 *
 * Completions only, and the cancelled ones are the point of saying so: ADR-0011 split one anchor
 * into two because *any* closure ends a scheduling round while only a completion is a day you did
 * the thing. This is the second question, and a cancellation answering it would put a date on the
 * row for a round the author explicitly declined.
 */
export function lastCompletionOf(templateId: string, tasks: readonly Task[]): IsoDate | null {
  let latest: IsoDate | null = null;
  for (const task of tasks) {
    if (task.taskTemplateId === templateId && task.status === 'COMPLETED') {
      // ISO dates compare as strings, which is the whole reason `domain/task.ts` keeps them that
      // way rather than round-tripping through a `Date` and a time zone.
      if (task.completedOn !== null && (latest === null || task.completedOn > latest)) {
        latest = task.completedOn;
      }
    }
  }
  return latest;
}

/**
 * The template's open task, or null.
 *
 * One task, not a list: a scheduled template does not fire again while it has an open one, so a
 * template with several open tasks is a multi-definition firing, and the row is about *this
 * template is currently asking* rather than about a particular task of it. The first is enough to
 * answer that.
 */
export function openTaskOf(templateId: string, tasks: readonly Task[]): Task | null {
  return tasks.find((task) => task.taskTemplateId === templateId && task.status === 'OPEN') ?? null;
}

export interface TemplateRowOptions {
  /** ADR-0013's escape hatch: deactivated templates are hidden, never deleted. */
  readonly includeInactive?: boolean;
}

/**
 * The list, in the order it is read.
 *
 * **Not-yet-due first**, which is ADR-0014's own rule and is the opposite of what a *most urgent
 * first* list would do. A template with an open task is already on the overview and completing it
 * there is one screen away; the ✓ exists for the templates showing **nothing**, so those are what
 * goes under your thumb. On the author's real data that is 28 against 16.
 *
 * Within the quiet half, **longest since it was done leads**, and a template never done leads them
 * all — that is the reminding question in order. Within the firing half, the overview's own
 * `byRank`, because a second answer to *what matters most today* one tab away from the first is
 * exactly how portal's comparator and its buckets came to disagree for years.
 *
 * *Decided by recommendation:* ADR-0014 states which half comes first and says nothing about the
 * order inside either.
 */
export function templateRows(
  templates: readonly TaskTemplate[],
  tasks: readonly Task[],
  today: IsoDate,
  options: TemplateRowOptions = {},
): TemplateRow[] {
  const rank = byRank(today);

  return templates
    .filter((template) => template.active || options.includeInactive === true)
    .map<TemplateRow>((template) => ({
      template,
      openTask: openTaskOf(template.id, tasks),
      lastCompletedOn: lastCompletionOf(template.id, tasks),
    }))
    .sort((left, right) => {
      if ((left.openTask === null) !== (right.openTask === null)) {
        return left.openTask === null ? -1 : 1;
      }
      if (left.openTask !== null && right.openTask !== null) {
        return rank(left.openTask, right.openTask);
      }
      // Never done sorts first, because it is the longest anything has gone unrecorded.
      return (left.lastCompletedOn ?? '').localeCompare(right.lastCompletedOn ?? '');
    });
}

/** One context's slice of the templates list, alphabetical within (#76). */
export interface TemplateGroup {
  readonly context: string;
  readonly rows: readonly TemplateRow[];
}

/**
 * Groups already-built rows by `template.context`, alphabetical by context and, within each group,
 * alphabetical by template name — the settled ordering for the templates list page (#76, #80's
 * Variant A). **Not-yet-due-first drops out entirely here**: `templateRows`' due/quiet split is a
 * scheduling concern for the omnibox, and urgency is not what this page sorts by.
 *
 * A **presentation layer over rows the caller already built**, deliberately: it takes `TemplateRow[]`
 * rather than templates/tasks, so it never re-derives `openTask`/`lastCompletedOn` and never
 * re-filters. That is what makes it compose with the search bar and "show deactivated" for free — a
 * group with no rows left after filtering simply is not in what it is handed, so it does not appear.
 *
 * An empty/uncategorized `context` (`''`) is its own group; the page renders it as *No context*, but
 * that is a display label, not a value this function invents.
 */
export function groupedTemplateRows(rows: readonly TemplateRow[]): readonly TemplateGroup[] {
  const byContext = new Map<string, TemplateRow[]>();
  for (const row of rows) {
    const group = byContext.get(row.template.context);
    if (group === undefined) {
      byContext.set(row.template.context, [row]);
    } else {
      group.push(row);
    }
  }
  return [...byContext.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([context, groupRows]) => ({
      context,
      rows: [...groupRows].sort((left, right) =>
        left.template.name.localeCompare(right.template.name),
      ),
    }));
}

/**
 * One thing the omnibox can offer against a template: **a definition, not a template.**
 *
 * ADR-0011 makes the affordance pick a task — portal's *"What did you do?"* dropdown listed
 * recurring tasks one name each, and with several definitions the equivalent is naming which one.
 * Expanding here rather than asking afterwards is what keeps the omnibox one keystroke deep: you
 * type *stofzuigen* and the thing you meant is on the list, where a template row would have been a
 * row called *Beddengoed* that then asks a question.
 */
export interface TemplateOffer {
  readonly row: TemplateRow;
  readonly definitionIndex: number;
  /** The definition's name — what a ✓ here would create, and what matched what was typed. */
  readonly name: string;
}

/**
 * The rows the omnibox merges into its one list.
 *
 * Three rules, all ADR-0014's, and all of them subtractive:
 *
 * - **Prefer the open task when there is one.** A due template used to appear twice — once as its
 *   task, once as itself — which is the double-listing that collapsed the dropdown's two groups into
 *   one list in the first place. The task row survives, because completing that task is what the ✓
 *   would have done anyway.
 * - **A deactivated template is not offered.** It cannot fire, so *"I already did this"* would be
 *   recording work against a rule the author switched off.
 * - **Nothing before a key is pressed.** The box is a thing you type into; browsing is the
 *   templates list's job.
 *
 * A definition matches on **its own name or its template's**, because the two are the same word for
 * 44 of the 47 real templates and deliberately different for the rest — `Beddengoed wassen` lives
 * under a template whose name a person might type either way.
 */

export function templateOffers(
  templates: readonly TaskTemplate[],
  tasks: readonly Task[],
  query: string,
  today: IsoDate,
  /**
   * **How many rows the caller has room for**, rather than a cap taken here.
   *
   * The omnibox shares one five-row list between tasks and templates (ADR-0014), so capping both
   * halves at `CAP` and then capping the merge again is a cap of ten pretending to be a cap of
   * five — and the half listed second silently loses every row.
   */
  limit: number = CAP,
): TemplateOffer[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') {
    return [];
  }

  return templateRows(templates, tasks, today)
    .filter((row) => row.openTask === null)
    .flatMap((row) =>
      row.template.taskDefinitions.map((definition, definitionIndex) => ({
        row,
        definitionIndex,
        name: definition.name,
      })),
    )
    .filter(
      (offer) =>
        offer.name.toLowerCase().includes(needle) ||
        offer.row.template.name.toLowerCase().includes(needle),
    )
    .slice(0, limit);
}

/**
 * Whether the templates list's own search bar (#78/#79) should keep this row.
 *
 * The same substring rule as `templateOffers` — case-insensitive, on the template's name and each
 * definition's — plus one the omnibox doesn't have: `context`, because on this page context is a
 * visible grouping axis (#76) rather than an internal field, so a search for *garden* should find
 * everything in it.
 *
 * Deliberately **not** composed with `showInactive` here — the list page decides how the two
 * combine (composably: search only narrows whatever the checkbox already shows), and this only
 * answers the one question of whether the row's own text matches.
 */
export function templateRowMatches(row: TemplateRow, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (row.template.name.toLowerCase().includes(needle)) {
    return true;
  }
  if (row.template.context.toLowerCase().includes(needle)) {
    return true;
  }
  return row.template.taskDefinitions.some((definition) =>
    definition.name.toLowerCase().includes(needle),
  );
}
