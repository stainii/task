import { describe, expect, it } from 'vitest';

import { aTask } from './task.mother';
import { aDefinition, aTemplate } from './template.mother';
import {
  groupedTemplateRows,
  lastCompletionOf,
  openTaskOf,
  templateOffers,
  templateRowMatches,
  templateRows,
} from './templates';

const TODAY = '2026-08-14';

/**
 * The templates list's rules, away from the screen that presents them.
 *
 * Everything here is answered from tasks the client already holds, which is what makes the list —
 * and the ✓ on it — work with the radio off. There is no *when was this last done* endpoint and
 * there is deliberately not going to be one: ADR-0011 made completion a fact about a **task**, so
 * asking the tasks is not a workaround for a missing query, it is the query.
 */

describe('when this template was last done', () => {
  it('is the latest completedOn among its completed tasks', () => {
    const tasks = [
      aTask({ taskTemplateId: 'boiler', status: 'COMPLETED', completedOn: '2024-06-07' }),
      aTask({ taskTemplateId: 'boiler', status: 'COMPLETED', completedOn: '2022-06-13' }),
    ];

    expect(lastCompletionOf('boiler', tasks)).toBe('2024-06-07');
  });

  /**
   * ADR-0011's *two anchors, not one*: **any** closure ends a scheduling round, but *"when did I
   * last actually do this"* reads completions only. A cancelled task answering it would report a
   * day you explicitly did not do the thing.
   */
  it('ignores cancelled tasks, which are not a day you did it', () => {
    const tasks = [
      aTask({ taskTemplateId: 'boiler', status: 'COMPLETED', completedOn: '2024-06-07' }),
      aTask({ taskTemplateId: 'boiler', status: 'CANCELLED', completedOn: null }),
    ];

    expect(lastCompletionOf('boiler', tasks)).toBe('2024-06-07');
  });

  it('ignores other templates’ completions', () => {
    const tasks = [
      aTask({ taskTemplateId: 'bins', status: 'COMPLETED', completedOn: '2026-08-13' }),
    ];

    expect(lastCompletionOf('boiler', tasks)).toBeNull();
  });

  it('is null for a template that has never been done', () => {
    expect(lastCompletionOf('boiler', [])).toBeNull();
  });
});

describe('the open task of a template', () => {
  it('is the one still open', () => {
    const open = aTask({ id: 'open-one', taskTemplateId: 'bins' });
    const tasks = [aTask({ taskTemplateId: 'bins', status: 'COMPLETED' }), open];

    expect(openTaskOf('bins', tasks)?.id).toBe('open-one');
  });

  it('is null once everything it fired has been closed', () => {
    const tasks = [aTask({ taskTemplateId: 'bins', status: 'COMPLETED' })];

    expect(openTaskOf('bins', tasks)).toBeNull();
  });
});

describe('the rows of the templates list', () => {
  /** ADR-0013: deactivating drops a template from the list. The filter is the escape hatch. */
  it('drops deactivated templates, and shows them when asked', () => {
    const templates = [
      aTemplate({ id: 'alive', active: true }),
      aTemplate({ id: 'retired', active: false }),
    ];

    expect(templateRows(templates, [], TODAY).map((row) => row.template.id)).toEqual(['alive']);
    expect(
      templateRows(templates, [], TODAY, { includeInactive: true }).map((row) => row.template.id),
    ).toEqual(['alive', 'retired']);
  });

  /**
   * **Not-yet-due first** (ADR-0014). A template with an open task is already on the overview and
   * you complete it there; the ✓ exists for the ones showing nothing, so those are what the list
   * puts under your thumb.
   */
  it('puts the templates with nothing open first', () => {
    const templates = [aTemplate({ id: 'firing' }), aTemplate({ id: 'quiet' })];
    const tasks = [aTask({ taskTemplateId: 'firing', dueDate: '2026-06-01' })];

    expect(templateRows(templates, tasks, TODAY).map((row) => row.template.id)).toEqual([
      'quiet',
      'firing',
    ]);
  });

  it('leads with the one longest since it was done, and a template never done leads them all', () => {
    const templates = [
      aTemplate({ id: 'recent' }),
      aTemplate({ id: 'ages-ago' }),
      aTemplate({ id: 'never' }),
    ];
    const tasks = [
      aTask({ taskTemplateId: 'recent', status: 'COMPLETED', completedOn: '2026-08-01' }),
      aTask({ taskTemplateId: 'ages-ago', status: 'COMPLETED', completedOn: '2024-06-07' }),
    ];

    expect(templateRows(templates, tasks, TODAY).map((row) => row.template.id)).toEqual([
      'never',
      'ages-ago',
      'recent',
    ]);
  });

  /**
   * The ones that *are* firing keep the overview's own order. A second answer to *which of these
   * matters most today* is the shape that let portal's comparator and its buckets disagree for
   * years, and here the two would be one tab apart.
   */
  it('orders the firing ones by the overview’s own ranking', () => {
    const templates = [aTemplate({ id: 'soon' }), aTemplate({ id: 'late' })];
    const tasks = [
      aTask({ taskTemplateId: 'soon', dueDate: '2026-09-30' }),
      aTask({ taskTemplateId: 'late', dueDate: '2026-06-13' }),
    ];

    expect(templateRows(templates, tasks, TODAY).map((row) => row.template.id)).toEqual([
      'late',
      'soon',
    ]);
  });

  it('carries the open task and the last completion the row has to say out loud', () => {
    const templates = [aTemplate({ id: 'boiler' })];
    const tasks = [
      aTask({ id: 'the-open-one', taskTemplateId: 'boiler', dueDate: '2026-06-13' }),
      aTask({ taskTemplateId: 'boiler', status: 'COMPLETED', completedOn: '2024-06-07' }),
    ];

    expect(templateRows(templates, tasks, TODAY)[0]).toEqual({
      template: templates[0],
      openTask: expect.objectContaining({ id: 'the-open-one' }),
      lastCompletedOn: '2024-06-07',
    });
  });
});

describe('the templates the omnibox offers', () => {
  it('offers a template whose name contains what was typed', () => {
    const templates = [aTemplate({ id: 'bins', name: 'Vuilbakken buitenzetten' })];

    expect(templateOffers(templates, [], 'vuilbak', TODAY).map((offer) => offer.name)).toEqual([
      'Vuilbakken buitenzetten',
    ]);
  });

  /**
   * **A definition, not a template** (ADR-0011). Portal's dropdown listed one name each, and with
   * several definitions the equivalent is naming which one was done — so the expansion happens here
   * rather than as a question after the row is picked, and typing the second chore finds it.
   */
  it('offers each definition by its own name', () => {
    const templates = [
      aTemplate({
        name: 'Beddengoed',
        taskDefinitions: [
          aDefinition({ name: 'Beddengoed wassen' }),
          aDefinition({ name: 'Bed stofzuigen' }),
        ],
      }),
    ];

    expect(templateOffers(templates, [], 'stofzuig', TODAY)).toEqual([
      expect.objectContaining({ name: 'Bed stofzuigen', definitionIndex: 1 }),
    ]);
  });

  it('offers every definition when the template’s own name is what matched', () => {
    const templates = [
      aTemplate({
        name: 'Beddengoed',
        taskDefinitions: [
          aDefinition({ name: 'Beddengoed wassen' }),
          aDefinition({ name: 'Bed stofzuigen' }),
        ],
      }),
    ];

    expect(templateOffers(templates, [], 'beddengoed', TODAY).map((offer) => offer.name)).toEqual([
      'Beddengoed wassen',
      'Bed stofzuigen',
    ]);
  });

  /**
   * **Prefer the open task when there is one** (ADR-0014). A due template was being listed twice —
   * once as its task, once as itself — which is the double-listing that collapsed the dropdown's
   * two groups into one list. The task row is the one that survives, because completing it is what
   * the ✓ would do anyway.
   */
  it('says nothing about a template that already has an open task', () => {
    const templates = [aTemplate({ id: 'bins', name: 'Vuilbakken buitenzetten' })];
    const tasks = [aTask({ taskTemplateId: 'bins', name: 'Vuilbakken buitenzetten' })];

    expect(templateOffers(templates, tasks, 'vuilbak', TODAY)).toEqual([]);
  });

  it('says nothing about a deactivated template', () => {
    const templates = [aTemplate({ name: 'Vuilbakken buitenzetten', active: false })];

    expect(templateOffers(templates, [], 'vuilbak', TODAY)).toEqual([]);
  });

  /** The box is a thing you type into, not a thing you browse. That is the list's job. */
  it('offers nothing before a key is pressed', () => {
    const templates = [aTemplate({ name: 'Vuilbakken buitenzetten' })];

    expect(templateOffers(templates, [], '   ', TODAY)).toEqual([]);
  });
});

/**
 * The templates list's own search bar (#78/#79) — composable with the "show deactivated" checkbox
 * rather than a replacement for it, so this only decides *does the row's text match*, the same
 * substring rule `templateOffers` uses on name and definitions, extended to `context` since context
 * is a visible grouping axis on this page.
 */
describe('whether a template row matches a search', () => {
  it('matches on the template’s own name', () => {
    const row = {
      template: aTemplate({ name: 'Vuilbakken buitenzetten' }),
      openTask: null,
      lastCompletedOn: null,
    };

    expect(templateRowMatches(row, 'vuilbak')).toBe(true);
    expect(templateRowMatches(row, 'nope')).toBe(false);
  });

  /** The search bar is empty by default, and an empty query should narrow nothing. */
  it('matches everything when the query is blank', () => {
    const row = {
      template: aTemplate({ name: 'Vuilbakken buitenzetten' }),
      openTask: null,
      lastCompletedOn: null,
    };

    expect(templateRowMatches(row, '')).toBe(true);
    expect(templateRowMatches(row, '   ')).toBe(true);
  });

  it('matches on a task definition’s own name, not just the template’s', () => {
    const row = {
      template: aTemplate({
        name: 'Beddengoed',
        taskDefinitions: [
          aDefinition({ name: 'Beddengoed wassen' }),
          aDefinition({ name: 'Bed stofzuigen' }),
        ],
      }),
      openTask: null,
      lastCompletedOn: null,
    };

    expect(templateRowMatches(row, 'stofzuig')).toBe(true);
  });

  /**
   * The one rule the omnibox's `templateOffers` doesn't have: `context` is a visible grouping axis
   * on this page (#76), so a search here should be able to find *everything in the garden*.
   */
  it('matches on the template’s context, unlike the omnibox’s own matching', () => {
    const row = {
      template: aTemplate({ name: 'Vuilbakken buitenzetten', context: 'garden' }),
      openTask: null,
      lastCompletedOn: null,
    };

    expect(templateRowMatches(row, 'garden')).toBe(true);
  });
});

/**
 * The templates list's own grouping (#76, settled by #80's prototype: Variant A). A **presentation
 * layer over rows the caller already built** — it never re-derives `openTask`/`lastCompletedOn` and
 * never re-filters, so it composes with search and "show deactivated" for free: group whatever rows
 * you hand it.
 *
 * Context is the outer grouping and it drops ADR-0014's due/quiet split from ordering entirely —
 * that is `templateRows`' own concern for the omnibox, not this page's.
 */
describe('the templates list grouped by context', () => {
  function rowFor(context: string, name: string) {
    return {
      template: aTemplate({ context, name }),
      openTask: null,
      lastCompletedOn: null,
    };
  }

  it('groups rows under their template’s context', () => {
    const rows = [rowFor('garden', 'Grass'), rowFor('house', 'Windows'), rowFor('garden', 'Hedge')];

    const groups = groupedTemplateRows(rows);

    expect(groups.map((group) => group.context)).toEqual(['garden', 'house']);
    expect(groups[0].rows.map((row) => row.template.name)).toEqual(['Grass', 'Hedge']);
    expect(groups[1].rows.map((row) => row.template.name)).toEqual(['Windows']);
  });

  it('sorts groups alphabetically by context', () => {
    const rows = [rowFor('zoo', 'Feed'), rowFor('attic', 'Declutter'), rowFor('house', 'Vacuum')];

    expect(groupedTemplateRows(rows).map((group) => group.context)).toEqual([
      'attic',
      'house',
      'zoo',
    ]);
  });

  it('sorts each group alphabetically by template name, dropping the due/quiet split', () => {
    const rows = [rowFor('house', 'Windows'), rowFor('house', 'Boiler'), rowFor('house', 'Attic')];

    expect(groupedTemplateRows(rows)[0].rows.map((row) => row.template.name)).toEqual([
      'Attic',
      'Boiler',
      'Windows',
    ]);
  });

  it('groups an empty/uncategorized context on its own, under the empty string', () => {
    const rows = [rowFor('', 'Mystery chore'), rowFor('house', 'Windows')];

    const groups = groupedTemplateRows(rows);

    expect(groups.map((group) => group.context)).toEqual(['', 'house']);
    expect(groups[0].rows.map((row) => row.template.name)).toEqual(['Mystery chore']);
  });

  it('is empty when there are no rows to group', () => {
    expect(groupedTemplateRows([])).toEqual([]);
  });
});
