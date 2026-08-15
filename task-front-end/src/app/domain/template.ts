import { IsoDate } from './dates';
import { Importance } from './task';

/**
 * The client's model of a task template — the TypeScript half of `TaskTemplateDto`.
 *
 * **Everything here is the wire shape, carried verbatim**, for `domain/task.ts`'s reason: dates stay
 * ISO-8601 strings because a `Date` cannot hold a `LocalDate` without inventing a time zone.
 *
 * ### Templates are not tasks, and are stored nothing like them
 *
 * A task is patched through the outbox and works offline; a template is **plain CRUD, online only**
 * (`CONTEXT.md`). Patching works for a task precisely because a task is inert, where a template is a
 * rule that keeps running in your absence — two devices editing one offline would produce a firing
 * schedule neither of them chose. So the client *holds* templates to read from (the reminding list,
 * the omnibox rows and ADR-0011's offline mint all need them with the radio off) and *writes* them
 * only when the server is there.
 */

/** Mirrors `StoredTrigger.Type`. */
export type TriggerType = 'MANUAL' | 'MIN_MAX' | 'CALENDAR';

/** Mirrors `StoredTrigger.Rule` — the four-rule vocabulary of ADR-0013, deliberately not RRULE. */
export type CalendarRuleName = 'DAYS' | 'WEEKS' | 'MONTHS' | 'NTH_WEEKDAY';

/** Mirrors `CalendarRule.Ordinal`. There is no `FIFTH`: `LAST` is what people mean by it. */
export type Ordinal = 'FIRST' | 'SECOND' | 'THIRD' | 'FOURTH' | 'LAST';

/** Mirrors `java.time.DayOfWeek`, in its order, so `WEEKDAYS.indexOf` sorts the way Java does. */
export const WEEKDAYS = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;

export type Weekday = (typeof WEEKDAYS)[number];

/**
 * The trigger, **flat**: a discriminator plus the columns that discriminator uses.
 *
 * The same shape as the table and the same shape as the API, which is what lets a
 * `/render-fixtures/` file be read straight into this with no conversion to keep honest. The fields
 * a discriminator does not use are absent rather than null in a fixture and null on the wire, so
 * they are optional here — the one type has to survive both.
 */
export interface StoredTrigger {
  readonly type: TriggerType;

  /** `MANUAL` only: *"When is the workshop?"*. The template author's own wording (ADR-0013). */
  readonly anchorLabel?: string | null;

  /** `MIN_MAX` only. Authored as interval plus window; stored as `min` and `min + window`. */
  readonly minDays?: number | null;
  readonly maxDays?: number | null;

  /** `CALENDAR` only. */
  readonly calendarRule?: CalendarRuleName | null;
  readonly calendarInterval?: number | null;
  /** Comma-separated, because `WEEKS` names several weekdays and `NTH_WEEKDAY` names one. */
  readonly calendarWeekdays?: string | null;
  readonly calendarDayOfMonth?: number | null;
  readonly calendarOrdinal?: Ordinal | null;
}

/**
 * One task a template produces when it fires.
 *
 * `importance` is nullable on the wire and defaults to `IMPORTANT`; the two offsets are days from
 * the firing's **one anchor**, negative being before it.
 */
export interface TaskDefinition {
  readonly id?: string | null;
  /** May contain `${…}` placeholders. There is no declared list — the placeholders *are* it. */
  readonly name: string;
  readonly startDateOffsetDays: number | null;
  readonly dueDateOffsetDays: number | null;
  readonly importance: Importance | null;
  readonly description: string | null;
}

export interface TaskTemplate {
  readonly id: string;
  readonly name: string;
  /** On the template, never on a definition: a context does not vary inside one (ADR-0013). */
  readonly context: string;
  /** Read-only on the wire: it moves through `/deactivation` and `/reactivation` only. */
  readonly active: boolean;
  /** Read-only on the wire. *The date this template began firing under its current rule.* */
  readonly activeSince?: IsoDate | null;
  readonly trigger: StoredTrigger;
  readonly taskDefinitions: readonly TaskDefinition[];
}

/** What a firing supplies: the date it came round, the anchor, and the answers to the variables. */
export interface Firing {
  readonly firingDate: IsoDate;
  /** Null when a manual template was run without one. */
  readonly anchor: IsoDate | null;
  readonly variables: Readonly<Record<string, string>>;
}

/** One task a firing describes. It stops at the description: turning it into a task is elsewhere. */
export interface RenderedDefinition {
  readonly name: string;
  readonly description: string | null;
  readonly importance: Importance;
  readonly startDate: IsoDate;
  readonly dueDate: IsoDate | null;
}

/** A firing, rendered: the context and the tasks it describes. */
export interface RenderedFiring {
  readonly context: string;
  readonly definitions: readonly RenderedDefinition[];
}
