import { variablesIn } from './render';
import { Importance } from './task';
import { Ordinal, StoredTrigger, TaskTemplate, TriggerType, Weekday } from './template';

/**
 * The authoring form's own vocabulary, and the conversion to and from the wire.
 *
 * It exists because **the form deliberately does not ask what the table stores**
 * ([ADR-0013](../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)):
 *
 * - min/max is authored as an **interval plus a window** — *"comes round every 10 days, and I have
 *   3 days to do it"* — because `min == max` is what ten of the 44 real templates say, and under
 *   two absolute fields that idiom is typing the same number twice and wondering whether it was a
 *   mistake;
 * - ***yearly* is offered and stored as `Months(n × 12)`**, because nobody thinks *"every 12 months
 *   on day 14"* — the affordance stays even though the rule does not.
 *
 * Both conversions are lossy in one direction, which is why they live here with a round trip over
 * them rather than being spelled out inside a component: the only thing that can prove the screen
 * shows back what it saved is reading it back.
 */

/** Which of the three the form is shaped for. The trigger is the first field (ADR-0013). */
export type TriggerKind = TriggerType;

/** What *every N …* counts in. `years` is the one with no rule behind it. */
export type CalendarUnit = 'days' | 'weeks' | 'months' | 'years';

/** The two ways a monthly rule can name its day. */
export type MonthlyOn = 'day-of-month' | 'nth-weekday';

/** One task-to-be, as the form holds it: strings where the wire has nulls. */
export interface DefinitionDraft {
  readonly id: string | null;
  readonly name: string;
  /** `''` means none. The wire's null is restored on the way out. */
  readonly description: string;
  readonly importance: Importance;
  /** Days from the firing's one anchor. Negative is before it. */
  readonly startDateOffsetDays: number | null;
  readonly dueDateOffsetDays: number | null;
}

export interface TemplateDraft {
  readonly name: string;
  readonly context: string;

  readonly kind: TriggerKind;

  /** Manual: the wording of the anchor — *"When is the workshop?"*. `''` means unnamed. */
  readonly anchorLabel: string;

  /** Min/max: *comes round every N days*, and *I have M days to do it*. */
  readonly interval: number;
  readonly window: number;

  /** Calendar: *every N …*. */
  readonly calendarEvery: number;
  readonly calendarUnit: CalendarUnit;
  readonly weekdays: readonly Weekday[];
  readonly dayOfMonth: number;
  readonly monthlyOn: MonthlyOn;
  readonly ordinal: Ordinal;
  readonly nthWeekday: Weekday;

  readonly definitions: readonly DefinitionDraft[];
}

/**
 * A new template, before anything has been typed.
 *
 * **Manual, with one task.** Manual because it is the only kind that cannot fire something
 * unexpected while you are still deciding, and one task because a template with none is the one
 * shape the server refuses.
 */
export function emptyDraft(): TemplateDraft {
  return {
    name: '',
    context: '',
    kind: 'MANUAL',
    anchorLabel: '',
    // Ten days and a three-day window: the median shape of the real data, so the fields read as an
    // example rather than as zeroes to be cleared.
    interval: 10,
    window: 0,
    calendarEvery: 1,
    calendarUnit: 'weeks',
    weekdays: ['MONDAY'],
    dayOfMonth: 1,
    monthlyOn: 'day-of-month',
    ordinal: 'FIRST',
    nthWeekday: 'SATURDAY',
    definitions: [
      {
        id: null,
        name: '',
        description: '',
        importance: 'IMPORTANT',
        startDateOffsetDays: null,
        dueDateOffsetDays: null,
      },
    ],
  };
}

/** A stored template, read back into the form's vocabulary. */
export function draftOf(template: TaskTemplate): TemplateDraft {
  const empty = emptyDraft();
  const trigger = template.trigger;

  return {
    ...empty,
    name: template.name,
    context: template.context,
    kind: trigger.type,
    anchorLabel: trigger.anchorLabel ?? '',
    ...minMaxFields(trigger, empty),
    ...calendarFields(trigger, empty),
    definitions: template.taskDefinitions.map((definition) => ({
      // Dropped rather than carried: the definitions are replaced wholesale on a `PUT`, and a
      // client-held id that the server has re-minted is a fact that can only ever be stale.
      id: null,
      name: definition.name,
      description: definition.description ?? '',
      // Normalised here, as the domain does, so the form never shows an empty grade for a value
      // that is absent from most payloads and from every row portal ever wrote.
      importance: definition.importance ?? 'IMPORTANT',
      startDateOffsetDays: definition.startDateOffsetDays,
      dueDateOffsetDays: definition.dueDateOffsetDays,
    })),
  };
}

function minMaxFields(trigger: StoredTrigger, empty: TemplateDraft): Partial<TemplateDraft> {
  if (trigger.type !== 'MIN_MAX') {
    return {};
  }
  const min = trigger.minDays ?? empty.interval;
  const max = trigger.maxDays ?? min;
  return { interval: min, window: max - min };
}

function calendarFields(trigger: StoredTrigger, empty: TemplateDraft): Partial<TemplateDraft> {
  if (trigger.type !== 'CALENDAR') {
    return {};
  }
  const every = trigger.calendarInterval ?? 1;
  const weekdays = (trigger.calendarWeekdays ?? '')
    .split(',')
    .map((day) => day.trim())
    .filter((day) => day !== '') as Weekday[];

  switch (trigger.calendarRule) {
    case 'DAYS':
      return { calendarUnit: 'days', calendarEvery: every };
    case 'WEEKS':
      return {
        calendarUnit: 'weeks',
        calendarEvery: every,
        weekdays: weekdays.length === 0 ? empty.weekdays : weekdays,
      };
    case 'NTH_WEEKDAY':
      return {
        calendarUnit: 'months',
        calendarEvery: every,
        monthlyOn: 'nth-weekday',
        ordinal: trigger.calendarOrdinal ?? empty.ordinal,
        nthWeekday: weekdays[0] ?? empty.nthWeekday,
      };
    default:
      // **Months, shown as years when it is a whole number of them.** The two are the same rule and
      // the same firings; which one the form says is a readability call, and ADR-0013's whole
      // argument for the `years` unit is that *every 12 months on day 14* is not a sentence anyone
      // thinks in. A template authored as *every 12 months* therefore comes back as *every 1 year*,
      // which is the same thing said better.
      return every % 12 === 0 && every >= 12
        ? {
            calendarUnit: 'years',
            calendarEvery: every / 12,
            dayOfMonth: trigger.calendarDayOfMonth ?? empty.dayOfMonth,
          }
        : {
            calendarUnit: 'months',
            calendarEvery: every,
            dayOfMonth: trigger.calendarDayOfMonth ?? empty.dayOfMonth,
          };
  }
}

/** The draft's trigger, flattened into the shape the table and the API share. */
export function triggerOf(draft: TemplateDraft): StoredTrigger {
  switch (draft.kind) {
    case 'MANUAL':
      return { type: 'MANUAL', anchorLabel: blankToNull(draft.anchorLabel) };
    case 'MIN_MAX':
      // The one conversion: `max = min + window`, so a window of zero is *due immediately*.
      return {
        type: 'MIN_MAX',
        minDays: draft.interval,
        maxDays: draft.interval + draft.window,
      };
    default:
      return calendarTrigger(draft);
  }
}

function calendarTrigger(draft: TemplateDraft): StoredTrigger {
  switch (draft.calendarUnit) {
    case 'days':
      return { type: 'CALENDAR', calendarRule: 'DAYS', calendarInterval: draft.calendarEvery };
    case 'weeks':
      return {
        type: 'CALENDAR',
        calendarRule: 'WEEKS',
        calendarInterval: draft.calendarEvery,
        // Sorted into `DayOfWeek` order, which is the order the server stores them in. Sending them
        // in tap order would make two templates with the same rule differ as strings.
        calendarWeekdays: [...draft.weekdays]
          .sort((left, right) => WEEKDAY_ORDER[left] - WEEKDAY_ORDER[right])
          .join(','),
      };
    case 'years':
      // *Yearly on a date* is not a stored rule. `Months(n × 12)` already is it, with the month
      // coming from the anchor exactly as every other rule's phase does.
      return {
        type: 'CALENDAR',
        calendarRule: 'MONTHS',
        calendarInterval: draft.calendarEvery * 12,
        calendarDayOfMonth: draft.dayOfMonth,
      };
    default:
      return draft.monthlyOn === 'nth-weekday'
        ? {
            type: 'CALENDAR',
            calendarRule: 'NTH_WEEKDAY',
            calendarInterval: draft.calendarEvery,
            calendarOrdinal: draft.ordinal,
            calendarWeekdays: draft.nthWeekday,
          }
        : {
            type: 'CALENDAR',
            calendarRule: 'MONTHS',
            calendarInterval: draft.calendarEvery,
            calendarDayOfMonth: draft.dayOfMonth,
          };
  }
}

const WEEKDAY_ORDER: Record<Weekday, number> = {
  MONDAY: 1,
  TUESDAY: 2,
  WEDNESDAY: 3,
  THURSDAY: 4,
  FRIDAY: 5,
  SATURDAY: 6,
  SUNDAY: 7,
};

/**
 * The draft as the wire shape.
 *
 * `active` and `activeSince` are **the server's**, echoed back rather than sent: `activeSince` is
 * *the date this template began firing under its current rule*, which no client can know, and
 * `active` moves only through `/deactivation` and `/reactivation` precisely so that changing it
 * always writes the date. Sending a guess here would give reactivation a second, silent route.
 */
export function templateOf(draft: TemplateDraft, id: string): TaskTemplate {
  return {
    id,
    name: draft.name.trim(),
    context: draft.context.trim(),
    active: true,
    activeSince: null,
    trigger: triggerOf(draft),
    taskDefinitions: draft.definitions.map((definition) => ({
      id: definition.id,
      name: definition.name.trim(),
      startDateOffsetDays: definition.startDateOffsetDays,
      dueDateOffsetDays: definition.dueDateOffsetDays,
      importance: definition.importance,
      description: blankToNull(definition.description),
    })),
  };
}

/**
 * Every variable this draft asks for, inferred from the `${…}` in the context and in every task's
 * name and description.
 *
 * **There is no declared list** (ADR-0013). Portal kept one beside the placeholders and they
 * drifted: one template asked for a `${lector}` that appeared in no text, and threw the answer away
 * for years with nothing able to report it. With inference that state cannot be represented — and a
 * typo surfaces as a fifth chip in the form as it is typed, rather than as a task named `${scool}`
 * months later.
 */
export function variablesOfDraft(draft: TemplateDraft): string[] {
  return variablesIn(
    draft.context,
    ...draft.definitions.flatMap((definition) => [definition.name, definition.description]),
  );
}

/**
 * What the form refuses to save, **as sentences rather than as unrepresentable states**.
 *
 * The weaker guarantee is deliberate, and the server makes the same choice for the same reason: a
 * check in a constructor runs on every *read* as well as every write, so one bad row would throw out
 * of the list and take the whole hourly due-check sweep with it — a per-template failure moved
 * somewhere nothing can catch it.
 *
 * These are the client's copy of `TaskTemplate#validateForSaving`, said before the round trip rather
 * than instead of it. The server is still the one that decides.
 */
export function problemsOf(draft: TemplateDraft): string[] {
  const problems: string[] = [];

  if (draft.name.trim() === '') {
    problems.push('Give the template a name.');
  }
  if (draft.context.trim() === '') {
    problems.push('Give the template a context.');
  }
  if (draft.definitions.length === 0) {
    problems.push('A template needs at least one task.');
  }
  if (draft.definitions.some((definition) => definition.name.trim() === '')) {
    problems.push('Every task needs a name.');
  }

  const variables = variablesOfDraft(draft);
  if (variables.length > 0 && draft.kind !== 'MANUAL') {
    problems.push(
      `Nothing can answer ${variables.map((name) => '${' + name + '}').join(', ')} at 04:00, ` +
        'so variables only work on a template you run by hand.',
    );
  }

  return problems;
}

function blankToNull(value: string): string | null {
  return value.trim() === '' ? null : value;
}
