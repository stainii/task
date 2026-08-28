import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { NOW } from '../../clock';
import { foldOf } from '../../domain/fold';
import { Task, TaskPatch } from '../../domain/task';
import { aTask } from '../../domain/task.mother';
import { TaskTemplate } from '../../domain/template';
import { aDefinition, aTemplate, minMax } from '../../domain/template.mother';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { TemplateService } from '../../sync/templates';
import { flush } from '../../testing';
import { Confirms } from '../../ui/confirms';
import { Overlays } from '../../ui/overlays';
import { Toasts } from '../../ui/toasts';
import { Templates } from './templates';

const NOW_AT = new Date('2026-08-14T10:00:00Z');

/**
 * **The reminding surface** (ADR-0014). Typing assumes you know what you did, and the author does
 * not always — *"I like that you can go to the templates, see when it's last done and hit a
 * button."*
 *
 * Tested through what it renders and what it writes. Its collaborators are boundaries — the store is
 * IndexedDB, sync is the network — so those are stubbed and nothing else is; the patches a ✓ writes
 * are folded by the **real** fold, so *the task really did close* is not an assertion about a stub.
 */

let heldTasks: Task[] = [];
let heldTemplates: TaskTemplate[] = [];
let recorded: TaskPatch[] = [];
const revision = signal(0);
const writable = signal(true);

let fixture: ComponentFixture<Templates>;

function element(): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function texts(selector: string): string[] {
  return [...element().querySelectorAll(selector)].map((node) => node.textContent?.trim() ?? '');
}

function click(selector: string, within: ParentNode = element()): void {
  const button = within.querySelector<HTMLButtonElement>(selector);
  if (button === null) {
    throw new Error(`No ${selector} on screen.`);
  }
  button.click();
}

function type(selector: string, text: string): void {
  const input = element().querySelector<HTMLInputElement>(selector);
  if (input === null) {
    throw new Error(`No ${selector} on screen.`);
  }
  input.value = text;
  input.dispatchEvent(new Event('input'));
}

async function render(templates: TaskTemplate[], tasks: Task[] = []): Promise<void> {
  heldTemplates = templates;
  heldTasks = tasks;
  fixture = TestBed.createComponent(Templates);
  await fixture.whenStable();
}

/**
 * The confirm is **the shell's** since #67, so this screen is asked whether it *asked* rather than
 * whether it painted. Answering resumes a suspended `await` inside the component, and what resumes
 * records patches before it reaches the toast — so a macrotask drains the chain that `whenStable`
 * does not see as pending work.
 */
function asking() {
  return TestBed.inject(Confirms).asking();
}

async function confirmOn(date: string | null): Promise<void> {
  const ask = asking();
  if (ask === null) {
    throw new Error('The date confirm is not open.');
  }
  ask.answer(date);
  await flush();
  await fixture.whenStable();
}

beforeEach(() => {
  heldTasks = [];
  heldTemplates = [];
  recorded = [];
  revision.set(0);
  writable.set(true);
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: NOW, useValue: () => NOW_AT },
      {
        provide: LocalStore,
        useValue: {
          tasks: () => Promise.resolve([...heldTasks]),
          templates: () => Promise.resolve([...heldTemplates]),
        },
      },
      {
        provide: TemplateService,
        useValue: { writable, refresh: vi.fn(() => Promise.resolve()) },
      },
      {
        provide: SyncService,
        useValue: {
          revision,
          // The real one records in order and answers with the patch undo names; the stub delegates
          // to this file's own `record` so the fold below still does the work.
          recordAll: async (patches: TaskPatch[]) => {
            for (const patch of patches) {
              await TestBed.inject(SyncService).record(patch);
            }
            return patches[patches.length - 1];
          },
          record: vi.fn((patch: TaskPatch) => {
            recorded.push(patch);
            const existing = heldTasks.find((task) => task.id === patch.taskId);
            const history = [...(existing?.history ?? []), patch];
            const folded = foldOf(patch.taskId, history);
            heldTasks =
              existing === undefined
                ? [...heldTasks, folded]
                : heldTasks.map((task) => (task.id === patch.taskId ? folded : task));
            return Promise.resolve(folded);
          }),
        },
      },
    ],
  });
});

/**
 * Context grouping (#76, settled by #80's prototype: Variant A) — plain uppercase `<h3>` section
 * headers, always expanded, no count/icon. Grouping composes with search and "show deactivated" for
 * free since it is a presentation layer over `rows()`, which is already filtered.
 */
describe('grouping by context', () => {
  it('groups templates under their context, alphabetical by context and by name within', async () => {
    await render([
      aTemplate({ name: 'Windows', context: 'house' }),
      aTemplate({ name: 'Grass', context: 'garden' }),
      aTemplate({ name: 'Hedge', context: 'garden' }),
    ]);

    expect(texts('h3.context-header')).toEqual(['garden', 'house']);
    const groups = [...element().querySelectorAll('.context-group')];
    expect(
      groups.map((group) =>
        [...group.querySelectorAll('.template .name')].map((n) => n.textContent?.trim()),
      ),
    ).toEqual([['Grass', 'Hedge'], ['Windows']]);
  });

  it('groups an empty/uncategorized context under "No context"', async () => {
    await render([aTemplate({ name: 'Mystery chore', context: '' })]);

    expect(texts('h3.context-header')).toEqual(['No context']);
  });

  it('drops a group entirely once search leaves it with no rows', async () => {
    await render([
      aTemplate({ name: 'Windows', context: 'house' }),
      aTemplate({ name: 'Grass', context: 'garden' }),
    ]);

    type('.search', 'window');
    await fixture.whenStable();

    expect(texts('h3.context-header')).toEqual(['house']);
  });
});

describe('the templates list', () => {
  it('says when each one was last done, as a count and a date', async () => {
    await render(
      [aTemplate({ id: 'boiler', name: 'Onderhoud ketels' })],
      [aTask({ taskTemplateId: 'boiler', status: 'COMPLETED', completedOn: '2024-06-07' })],
    );

    expect(texts('li.template .name')).toEqual(['Onderhoud ketels']);
    expect(texts('li.template .last')).toEqual(["last 798 days ago · 7 Jun '24"]);
  });

  it('says a template has never been done rather than leaving the row silent', async () => {
    await render([aTemplate({ name: 'Vissen eten geven' })]);

    expect(texts('li.template .last')).toEqual(['never done']);
  });

  /**
   * #88: a chore last done weeks ago has no completed task left on this device — `GET /api/tasks`
   * is `OPEN`-only and the store prunes closed tasks after a day. The row falls back to the
   * server's whole-history `lastCompletedOn`.
   */
  it('falls back to the server’s last-completion when this device holds no completed task', async () => {
    await render([
      aTemplate({ id: 'boiler', name: 'Onderhoud ketels', lastCompletedOn: '2026-06-01' }),
    ]);

    expect(texts('li.template .last')).toEqual(['last 74 days ago · 1 Jun']);
  });

  /**
   * #88 / #86(c): the ✓ still gives instant feedback. A completion minted here outranks the
   * server's older value, so the row flips to *today* before any sync round trip — offline too.
   */
  it('shows last done today the moment the ✓ records, over a stale server value', async () => {
    await render([
      aTemplate({
        id: 'bins',
        name: 'Vuilbakken',
        trigger: minMax(10, 0),
        lastCompletedOn: '2026-06-01',
        taskDefinitions: [aDefinition({ name: 'Vuilbakken buitenzetten' })],
      }),
    ]);

    expect(texts('li.template .last')).toEqual(['last 74 days ago · 1 Jun']);

    click('li.template .did-it');
    await fixture.whenStable();
    await confirmOn('2026-08-14');

    // The real SyncService bumps its revision when the store changes; the stub folds the patch
    // into heldTasks but leaves that to the test.
    revision.update((value) => value + 1);
    await fixture.whenStable();

    expect(texts('li.template .last')).toEqual(['last done today']);
  });

  /** A template that is due is already asking, on the overview. The row says which state it is in. */
  it('says how overdue the task is for a template that is currently asking', async () => {
    await render(
      [aTemplate({ id: 'boiler', name: 'Onderhoud ketels' })],
      [aTask({ taskTemplateId: 'boiler', dueDate: '2026-06-13' })],
    );

    expect(texts('li.template .state')).toEqual(['62 days overdue']);
  });

  it('drops deactivated templates until they are asked for', async () => {
    await render([
      aTemplate({ name: 'Alive', active: true }),
      aTemplate({ name: 'Retired', active: false }),
    ]);

    expect(texts('li.template .name')).toEqual(['Alive']);

    click('.show-inactive');
    await fixture.whenStable();

    expect(texts('li.template .name')).toEqual(['Alive', 'Retired']);
  });
});

/**
 * The search bar (#78/#79). **Composable with "show deactivated"**, never a replacement for it:
 * search only ever narrows within whatever the checkbox is already showing (Variant A of #78).
 */
describe('the search bar', () => {
  it('narrows the list to templates whose name matches what was typed', async () => {
    await render([
      aTemplate({ name: 'Vuilbakken buitenzetten' }),
      aTemplate({ name: 'Ramen lappen' }),
    ]);

    type('.search', 'vuilbak');
    await fixture.whenStable();

    expect(texts('li.template .name')).toEqual(['Vuilbakken buitenzetten']);
  });

  /**
   * **Composable, not overriding** (Variant A of #78): search only narrows what the checkbox is
   * already showing. It never surfaces a deactivated match on its own — that stays the nudge's job.
   */
  it('does not surface a matching deactivated template while "show deactivated" is off', async () => {
    await render([aTemplate({ name: 'Vuilbakken buitenzetten', active: false })]);

    type('.search', 'vuilbak');
    await fixture.whenStable();

    expect(texts('li.template .name')).toEqual([]);
  });

  it('does search among deactivated templates once "show deactivated" is on', async () => {
    await render([
      aTemplate({ name: 'Vuilbakken buitenzetten', active: false }),
      aTemplate({ name: 'Ramen lappen' }),
    ]);

    click('.show-inactive');
    await fixture.whenStable();
    type('.search', 'vuilbak');
    await fixture.whenStable();

    expect(texts('li.template .name')).toEqual(['Vuilbakken buitenzetten']);
  });

  /**
   * **Never a silent override** (Variant A of #78): a match hidden behind the checkbox surfaces as
   * a nudge, not by widening the search's own scope on its own.
   */
  it('nudges toward "show deactivated" when a search matches nothing visible but something hidden', async () => {
    await render([aTemplate({ name: 'Vuilbakken buitenzetten', active: false })]);

    type('.search', 'vuilbak');
    await fixture.whenStable();

    expect(texts('.hidden-matches')).toEqual([
      '1 more match among deactivated templates — show deactivated to see them.',
    ]);
  });

  it('reveals the hidden matches when the nudge’s own button is pressed', async () => {
    await render([aTemplate({ name: 'Vuilbakken buitenzetten', active: false })]);

    type('.search', 'vuilbak');
    await fixture.whenStable();

    click('.hidden-matches button');
    await fixture.whenStable();

    expect(texts('li.template .name')).toEqual(['Vuilbakken buitenzetten']);
    expect(element().querySelector('.hidden-matches')).toBeNull();
  });

  /** Once the checkbox is on, matches are visible directly — the nudge would repeat what's shown. */
  it('says nothing once "show deactivated" is already on', async () => {
    await render([aTemplate({ name: 'Vuilbakken buitenzetten', active: false })]);

    click('.show-inactive');
    await fixture.whenStable();
    type('.search', 'vuilbak');
    await fixture.whenStable();

    expect(element().querySelector('.hidden-matches')).toBeNull();
  });

  /** Context is a visible grouping axis on this page (#76), so search should find it too. */
  it('matches on a template’s context, not just its name', async () => {
    await render([
      aTemplate({ name: 'Vuilbakken buitenzetten', context: 'garden' }),
      aTemplate({ name: 'Ramen lappen', context: 'house' }),
    ]);

    type('.search', 'garden');
    await fixture.whenStable();

    expect(texts('li.template .name')).toEqual(['Vuilbakken buitenzetten']);
  });
});

/** #61's "Done when": the list grows to ~115 entries after import, so it has to work at that size. */
describe('at the size the import produces', () => {
  it('renders all ~115 rows', async () => {
    await render(
      Array.from({ length: 115 }, (_unused, index) =>
        aTemplate({ id: `t${index}`, name: `Template ${index}` }),
      ),
    );

    expect(element().querySelectorAll('li.template')).toHaveLength(115);
  });
});

describe('the ✓', () => {
  /**
   * **Neither capture path fires silently** (ADR-0014, amended by the author after the first draft
   * let the ✓ do exactly that). The whole reason this action exists is that you did the thing away
   * from the app, so a path that can only mean *today* forces the lie ADR-0011 was written to
   * prevent — and throws away the min/max anchor's accuracy with it.
   */
  it('asks when, rather than completing on the spot', async () => {
    await render([aTemplate({ name: 'Vuilbakken' })]);

    click('li.template .did-it');
    await fixture.whenStable();

    expect(asking()?.what).toBe('Vuilbakken');
    expect(recorded).toEqual([]);
  });

  it('mints a task created and completed in one breath when nothing is open', async () => {
    await render([
      aTemplate({
        id: 'bins',
        name: 'Vuilbakken',
        trigger: minMax(10, 0),
        taskDefinitions: [aDefinition({ name: 'Vuilbakken buitenzetten' })],
      }),
    ]);

    click('li.template .did-it');
    await fixture.whenStable();
    await confirmOn('2026-08-11');

    expect(recorded).toHaveLength(2);
    const task = foldOf(recorded[0].taskId, recorded);
    expect(task.name).toBe('Vuilbakken buitenzetten');
    expect(task.status).toBe('COMPLETED');
    expect(task.completedOn).toBe('2026-08-11');
    expect(task.taskTemplateId).toBe('bins');
  });

  /** One button, two shapes, chosen by the data — never by where you clicked (ADR-0011). */
  it('completes the open task when there is one, instead of minting a second', async () => {
    await render(
      [aTemplate({ id: 'bins', name: 'Vuilbakken' })],
      [aTask({ id: 'the-open-one', taskTemplateId: 'bins' })],
    );

    click('li.template .did-it');
    await fixture.whenStable();
    await confirmOn('2026-08-11');

    expect(recorded).toHaveLength(1);
    expect(recorded[0].taskId).toBe('the-open-one');
    expect(recorded[0].changes).toEqual({ status: 'COMPLETED', completedOn: '2026-08-11' });
  });

  /**
   * ADR-0011: the affordance **picks a task, not a template**. Conjuring every definition as
   * completed would complete tasks the user never named.
   */
  it('asks which one was done when a template describes several tasks', async () => {
    await render([
      aTemplate({
        name: 'Beddengoed',
        taskDefinitions: [
          aDefinition({ name: 'Beddengoed wassen' }),
          aDefinition({ name: 'Bed stofzuigen' }),
        ],
      }),
    ]);

    click('li.template .did-it');
    await fixture.whenStable();

    expect(texts('.which .definition')).toEqual(['Beddengoed wassen', 'Bed stofzuigen']);
    expect(asking()).toBeNull();

    element().querySelectorAll<HTMLButtonElement>('.which .definition')[1].click();
    await fixture.whenStable();
    await confirmOn('2026-08-11');

    expect(foldOf(recorded[0].taskId, recorded).name).toBe('Bed stofzuigen');
  });

  it('takes Escape as a way out of the chooser, which it declares itself modal to', async () => {
    // `aria-modal="true"` with no way to close by keyboard is a guarantee living in code and broken
    // by everything outside it. Before #67 neither of the app's two Escape owners was this screen,
    // so the only way out was the mouse.
    await render([
      aTemplate({
        name: 'Beddengoed',
        taskDefinitions: [
          aDefinition({ name: 'Beddengoed wassen' }),
          aDefinition({ name: 'Bed stofzuigen' }),
        ],
      }),
    ]);

    click('li.template .did-it');
    await fixture.whenStable();
    expect(element().querySelector('.which')).not.toBeNull();

    TestBed.inject(Overlays).escape();
    await fixture.whenStable();

    expect(element().querySelector('.which')).toBeNull();
    expect(recorded).toEqual([]);
  });

  /**
   * ADR-0015 puts a toast behind any action that removes a row — and the sharper reason here is
   * that undo-then-recomplete inside it is the **only** correction path `completedOn` has
   * (ADR-0011's amendment). Without it a mistap is permanent the instant *Done* is pressed.
   */
  it('offers undo, which is the only way a wrong date is ever corrected', async () => {
    await render([aTemplate({ name: 'Vuilbakken' })]);

    click('li.template .did-it');
    await fixture.whenStable();
    await confirmOn('2026-08-11');

    const offer = TestBed.inject(Toasts).showing();
    if (offer?.kind !== 'undoable') {
      throw new Error('Nothing is offering to be undone.');
    }
    expect(offer.what).toContain('Vuilbakken');
    offer.undo();
    await flush();
    await fixture.whenStable();

    const undo = recorded[recorded.length - 1];
    expect(undo.voids).toBe(recorded[recorded.length - 2].id);
    expect(undo.changes).toEqual({});
  });
});

describe('when the device is offline', () => {
  /** ADR-0004: template editing is **visibly** unavailable, never a save that goes nowhere. */
  it('says so, and offers no way to author one', async () => {
    writable.set(false);
    await render([aTemplate({ name: 'Vuilbakken' })]);

    expect(element().querySelector('.new-template')).toBeNull();
    expect(texts('.offline')).toEqual(['Templates can only be changed online.']);
  });

  /** The ✓ is not editing. It is a patch on a task, and patches are what the outbox is for. */
  it('still lets you say you did something', async () => {
    writable.set(false);
    await render([aTemplate({ name: 'Vuilbakken' })]);

    click('li.template .did-it');
    await fixture.whenStable();
    await confirmOn('2026-08-11');

    expect(recorded).toHaveLength(2);
  });
});
