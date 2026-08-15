import { addDays, IsoDate } from './dates';
import {
  Firing,
  RenderedDefinition,
  RenderedFiring,
  StoredTrigger,
  TaskDefinition,
  TaskTemplate,
} from './template';

/**
 * Template rendering: **`${…}` substituted, offsets resolved to real dates**, nothing left to look
 * up.
 *
 * The TypeScript half of `TaskTemplate#render`, and the reason a second half exists at all: the
 * authoring screen previews what running a template is about to create, and *"I already did this"*
 * mints its task **client-side, through the patch outbox**, because it has to work with no server
 * ([ADR-0011](../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)).
 *
 * **No rendering rule without a fixture.** Everything here is pinned by `/render-fixtures/`, which
 * both suites enumerate — see `render.fixtures.spec.ts`. Silent divergence between the two
 * implementations would put a different date on the same task on two devices, visible only in
 * history.
 *
 * Kept a plain module with no injected clock, matching the Java side sitting on the aggregate: every
 * date it produces comes from the firing it is given, so there is no *now* for it to disagree about.
 */

/** `${` up to the first `}`, with at least one character between. `${}` is text, not a variable. */
const PLACEHOLDER = /\$\{([^}]+)\}/g;

/**
 * A template that cannot render one of its tasks.
 *
 * Its own type rather than a bare `Error`, because the authoring screen has to tell the two apart
 * from a failure of the app: this one is a sentence about the template on screen.
 */
export class TemplateRenderError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TemplateRenderError';
  }
}

/**
 * Every variable these texts name, **in the order they first appear**, so the authoring form's chips
 * read in the order they were typed.
 *
 * The same pattern the substitution below understands, deliberately in one place: a form that
 * disagreed with the renderer about what a placeholder is would offer a field the renderer then
 * cannot fill.
 */
export function variablesIn(...texts: readonly (string | null | undefined)[]): string[] {
  const variables = new Set<string>();
  for (const text of texts) {
    if (text !== null && text !== undefined) {
      for (const match of text.matchAll(PLACEHOLDER)) {
        variables.add(match[1]);
      }
    }
  }
  return [...variables];
}

/**
 * Substitution, and **an unanswered variable is left standing as `${…}`** rather than blanked.
 *
 * The task is then named for the mistake, which is how it is found; substituting it away would
 * produce a plausible name nobody typed. Pinned by fixture 02.
 */
export function fillInVariables(text: string, variables: Readonly<Record<string, string>>): string {
  let filled = text;
  for (const [name, value] of Object.entries(variables)) {
    filled = filled.split('${' + name + '}').join(value);
  }
  return filled;
}

/**
 * The due date a definition with no due offset falls back to.
 *
 * **Asked of the trigger, never computed by the caller** — the same move the Java side made when it
 * deleted this as a parameter, because a caller that computes it is a caller that can compute it
 * differently. Only min/max has one: its `max` is *the day this stops being a suggestion* (REC-006),
 * so the old reminder→urgent escalation is one task going overdue. A calendar firing names a date
 * and nothing else.
 */
export function defaultDueDateFor(trigger: StoredTrigger, firingDate: IsoDate): IsoDate | null {
  if (trigger.type !== 'MIN_MAX') {
    return null;
  }
  const min = trigger.minDays ?? 0;
  const max = trigger.maxDays ?? min;
  // Created at `min`, due at `max`, both from the same round start — so the due date is the firing
  // date plus the window.
  return addDays(firingDate, max - min);
}

/**
 * **Renders a firing into the tasks it describes.**
 *
 * The template's context is rendered **before any definition is**, so a template whose text resolves
 * to nothing fails loudly and produces **no tasks at all** rather than some of them (TODO-022, over
 * portal's silent `"No name"` fallback).
 */
export function renderTemplate(template: TaskTemplate, firing: Firing): RenderedFiring {
  const context = required(
    fillInVariables(template.context, firing.variables),
    `Template ${template.id} renders to an empty context.`,
  );

  const defaultDueDate = defaultDueDateFor(template.trigger, firing.firingDate);

  return {
    context,
    definitions: template.taskDefinitions.map((definition) =>
      render(template, definition, firing, defaultDueDate),
    ),
  };
}

function render(
  template: TaskTemplate,
  definition: TaskDefinition,
  firing: Firing,
  defaultDueDate: IsoDate | null,
): RenderedDefinition {
  return {
    name: required(
      fillInVariables(definition.name, firing.variables),
      `Definition ${definition.name} of template ${template.id} renders to an empty name.`,
    ),
    description:
      definition.description === null
        ? null
        : fillInVariables(definition.description, firing.variables),
    // The one place the default lives, as on the Java side: `importance` is absent from most
    // payloads and from every row portal ever wrote, so the absence is normalised here rather than
    // carried on as a null the task side would have to rule on again.
    importance: definition.importance ?? 'IMPORTANT',
    // No start offset means the task starts **the day the template came round** — not "today",
    // which is the same date for a manual run and the wrong one for a calendar template catching up
    // on a date it slept through.
    startDate: offsetFrom(firing.anchor, definition.startDateOffsetDays) ?? firing.firingDate,
    dueDate: offsetFrom(firing.anchor, definition.dueDateOffsetDays) ?? defaultDueDate,
  };
}

/** With no anchor there is nothing to measure from, so the offset resolves to no date at all. */
function offsetFrom(anchor: IsoDate | null, offsetDays: number | null): IsoDate | null {
  if (anchor === null || offsetDays === null) {
    return null;
  }
  return addDays(anchor, offsetDays);
}

function required(rendered: string, complaint: string): string {
  if (rendered.trim() === '') {
    throw new TemplateRenderError(complaint);
  }
  return rendered;
}
