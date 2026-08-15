import { describe, expect, it } from 'vitest';

import {
  draftOf,
  emptyDraft,
  problemsOf,
  templateOf,
  triggerOf,
  variablesOfDraft,
} from './template-draft';
import { aDefinition, aTemplate } from './template.mother';

/**
 * The authoring form's own vocabulary, and the conversion to the wire shape.
 *
 * It exists because **the form does not ask what the table stores** (ADR-0013). Min/max is authored
 * as an interval plus a window; *yearly* is offered and stored as `Months(n × 12)`. Both are
 * deliberate, both are lossy in one direction, and a round trip is the only thing that can prove the
 * screen shows back what it saved.
 */

describe('min/max, authored as an interval plus a window', () => {
  /**
   * ADR-0013: the form asks *"comes round every N days, and I have M days to do it"*, and stores
   * `min = N`, `max = N + M`.
   */
  it('reads a stored min and max back as the two numbers the form asks for', () => {
    const draft = draftOf(aTemplate({ trigger: { type: 'MIN_MAX', minDays: 10, maxDays: 13 } }));

    expect(draft.kind).toBe('MIN_MAX');
    expect(draft.interval).toBe(10);
    expect(draft.window).toBe(3);
  });

  it('writes them back unchanged', () => {
    const draft = { ...emptyDraft(), kind: 'MIN_MAX' as const, interval: 10, window: 3 };

    expect(triggerOf(draft)).toMatchObject({ type: 'MIN_MAX', minDays: 10, maxDays: 13 });
  });

  /**
   * **`min == max` is a window of zero, which means due immediately** — and it is what ten of the
   * 44 real templates say (720/720, 365/365, 5/5 ×3…). Under two absolute fields that idiom is
   * typing the same number twice and wondering whether it was a mistake; here it is one field left
   * alone.
   */
  it('reads due-immediately as a window of zero rather than a repeated number', () => {
    const draft = draftOf(aTemplate({ trigger: { type: 'MIN_MAX', minDays: 365, maxDays: 365 } }));

    expect(draft.interval).toBe(365);
    expect(draft.window).toBe(0);
    expect(triggerOf(draft)).toMatchObject({ minDays: 365, maxDays: 365 });
  });
});

describe('the calendar rules', () => {
  it('carries several weekdays in one rule, which portal could not say', () => {
    const stored = {
      type: 'CALENDAR' as const,
      calendarRule: 'WEEKS' as const,
      calendarInterval: 2,
      calendarWeekdays: 'TUESDAY,THURSDAY',
    };

    const draft = draftOf(aTemplate({ trigger: stored }));
    expect(draft.calendarUnit).toBe('weeks');
    expect(draft.calendarEvery).toBe(2);
    expect(draft.weekdays).toEqual(['TUESDAY', 'THURSDAY']);

    expect(triggerOf(draft)).toMatchObject({
      calendarRule: 'WEEKS',
      calendarInterval: 2,
      calendarWeekdays: 'TUESDAY,THURSDAY',
    });
  });

  /**
   * **Yearly is a UI unit, not a stored rule** (ADR-0013). Nobody thinks *"every 12 months on day
   * 14"*, so the picker offers years and writes `Months(n × 12)` — the month coming from the anchor,
   * exactly as every other rule's phase does.
   */
  it('offers years and stores months', () => {
    const draft = {
      ...emptyDraft(),
      kind: 'CALENDAR' as const,
      calendarUnit: 'years' as const,
      calendarEvery: 2,
      dayOfMonth: 14,
    };

    expect(triggerOf(draft)).toMatchObject({
      calendarRule: 'MONTHS',
      calendarInterval: 24,
      calendarDayOfMonth: 14,
    });
  });

  it('reads a whole number of years back as years', () => {
    const draft = draftOf(
      aTemplate({
        trigger: {
          type: 'CALENDAR',
          calendarRule: 'MONTHS',
          calendarInterval: 24,
          calendarDayOfMonth: 14,
        },
      }),
    );

    expect(draft.calendarUnit).toBe('years');
    expect(draft.calendarEvery).toBe(2);
  });

  it('leaves a rule that is not a whole number of years in months', () => {
    const draft = draftOf(
      aTemplate({
        trigger: {
          type: 'CALENDAR',
          calendarRule: 'MONTHS',
          calendarInterval: 1,
          calendarDayOfMonth: 31,
        },
      }),
    );

    expect(draft.calendarUnit).toBe('months');
    expect(draft.calendarEvery).toBe(1);
    expect(draft.dayOfMonth).toBe(31);
  });

  /** *Every first Saturday* — the shape #36 asked for and no other rule can produce. */
  it('round-trips the nth weekday of the month', () => {
    const draft = draftOf(
      aTemplate({
        trigger: {
          type: 'CALENDAR',
          calendarRule: 'NTH_WEEKDAY',
          calendarInterval: 1,
          calendarOrdinal: 'FIRST',
          calendarWeekdays: 'SATURDAY',
        },
      }),
    );

    expect(draft.calendarUnit).toBe('months');
    expect(draft.monthlyOn).toBe('nth-weekday');
    expect(draft.ordinal).toBe('FIRST');
    expect(draft.nthWeekday).toBe('SATURDAY');

    expect(triggerOf(draft)).toMatchObject({
      calendarRule: 'NTH_WEEKDAY',
      calendarInterval: 1,
      calendarOrdinal: 'FIRST',
      calendarWeekdays: 'SATURDAY',
    });
  });
});

describe('a manual template', () => {
  /**
   * **The author names the anchor** — *"When is the workshop?"* — so running the template asks a
   * question rather than presenting a date picker, and the preview has a label to read.
   */
  it('carries the wording of its anchor', () => {
    const draft = draftOf(aTemplate({ trigger: { type: 'MANUAL', anchorLabel: 'When is it?' } }));

    expect(draft.anchorLabel).toBe('When is it?');
    expect(triggerOf(draft)).toEqual({ type: 'MANUAL', anchorLabel: 'When is it?' });
  });

  it('may leave it unnamed, which is a template run without an anchor at all', () => {
    const draft = { ...emptyDraft(), anchorLabel: '   ' };

    expect(triggerOf(draft)).toEqual({ type: 'MANUAL', anchorLabel: null });
  });
});

describe('the wire shape a draft becomes', () => {
  it('turns an empty description into no description', () => {
    const draft = {
      ...emptyDraft(),
      name: 'Iets',
      context: 'house',
      definitions: [
        {
          id: null,
          name: 'Iets doen',
          description: '  ',
          importance: 'IMPORTANT' as const,
          startDateOffsetDays: null,
          dueDateOffsetDays: null,
        },
      ],
    };

    expect(templateOf(draft, 'an-id').taskDefinitions[0].description).toBeNull();
  });

  /** `active` and `activeSince` are the server's. A draft has no honest value to put in them. */
  it('does not claim to know whether the template is active', () => {
    const template = templateOf({ ...emptyDraft(), name: 'Iets', context: 'house' }, 'an-id');

    expect(template.id).toBe('an-id');
    expect(template.activeSince).toBeNull();
  });
});

describe('what the form refuses to save', () => {
  it('wants a name and a context', () => {
    const draft = { ...emptyDraft(), name: '  ', context: '' };

    expect(problemsOf(draft)).toContain('Give the template a name.');
    expect(problemsOf(draft)).toContain('Give the template a context.');
  });

  /**
   * **A template must be able to render something.** One with no definitions fires an event with no
   * tasks, which the server refuses — so before the rule existed it threw once an hour, for ever,
   * with an ERROR line as the only trace. Refusing here turns that into a sentence on the screen
   * that caused it.
   */
  it('wants at least one task definition', () => {
    const draft = { ...emptyDraft(), name: 'Iets', context: 'house', definitions: [] };

    expect(problemsOf(draft)).toContain('A template needs at least one task.');
  });

  it('wants every task to have a name', () => {
    const draft = {
      ...emptyDraft(),
      name: 'Iets',
      context: 'house',
      definitions: [
        {
          id: null,
          name: ' ',
          description: '',
          importance: 'IMPORTANT' as const,
          startDateOffsetDays: null,
          dueDateOffsetDays: null,
        },
      ],
    };

    expect(problemsOf(draft)).toContain('Every task needs a name.');
  });

  /**
   * **`${…}` is manual-only.** Nothing is present to answer a placeholder when a template fires at
   * 04:00, so a scheduled one containing a variable renders a task literally named `${school}`.
   */
  it('refuses a variable on a template that fires by itself', () => {
    const scheduled = {
      ...emptyDraft(),
      name: 'Iets',
      context: 'house',
      kind: 'MIN_MAX' as const,
      definitions: [
        {
          id: null,
          name: 'Iets voor ${school}',
          description: '',
          importance: 'IMPORTANT' as const,
          startDateOffsetDays: null,
          dueDateOffsetDays: null,
        },
      ],
    };

    expect(problemsOf(scheduled)).toContainEqual(expect.stringContaining('school'));
  });

  it('allows one on a template you run by hand, which is the whole point of them', () => {
    const manual = {
      ...emptyDraft(),
      name: 'Workshop',
      context: 'work',
      definitions: [
        {
          id: null,
          name: 'Mail naar ${school}',
          description: '',
          importance: 'IMPORTANT' as const,
          startDateOffsetDays: null,
          dueDateOffsetDays: null,
        },
      ],
    };

    expect(problemsOf(manual)).toEqual([]);
    expect(variablesOfDraft(manual)).toEqual(['school']);
  });

  /** Inference reads the context too — it is a rendered field like any other. */
  it('sees a variable in the context as well as in a task name', () => {
    const draft = { ...emptyDraft(), name: 'Iets', context: '${where}' };

    expect(variablesOfDraft(draft)).toEqual(['where']);
  });
});

describe('a template read back into the form', () => {
  it('brings its tasks with their offsets and importance', () => {
    const draft = draftOf(
      aTemplate({
        name: 'Opvolgen workshop',
        context: 'work',
        taskDefinitions: [
          aDefinition({
            name: 'Voorbereidingsmail',
            startDateOffsetDays: -14,
            dueDateOffsetDays: -7,
            importance: 'NOT_SO_IMPORTANT',
            description: 'Stuur de mail',
          }),
        ],
      }),
    );

    expect(draft.name).toBe('Opvolgen workshop');
    expect(draft.context).toBe('work');
    expect(draft.definitions).toEqual([
      {
        id: null,
        name: 'Voorbereidingsmail',
        description: 'Stuur de mail',
        importance: 'NOT_SO_IMPORTANT',
        startDateOffsetDays: -14,
        dueDateOffsetDays: -7,
      },
    ]);
  });

  /** Absent importance is normalised to the default, so the form never shows an empty grade. */
  it('shows the default importance where the wire had none', () => {
    const draft = draftOf(aTemplate({ taskDefinitions: [aDefinition({ importance: null })] }));

    expect(draft.definitions[0].importance).toBe('IMPORTANT');
  });
});
