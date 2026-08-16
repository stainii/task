import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { routes } from '../app.routes';
import { NOW } from '../clock';
import { foldOf } from '../domain/fold';
import { Task, TaskPatch } from '../domain/task';
import { aTask } from '../domain/task.mother';
import { TaskTemplate } from '../domain/template';
import { aDefinition, aTemplate, minMax } from '../domain/template.mother';
import { LocalStore } from '../store/local-store';
import { flush } from '../testing';
import { SyncService } from '../sync/sync';
import { Omnibox } from './omnibox';
import { Ask, Confirms } from './confirms';
import { Overlays } from './overlays';
import { Toast, Toasts } from './toasts';

/**
 * The omnibox: capture by typing, one keystroke from wherever you are (ADR-0014).
 *
 * Driven through the router, because *the context you are standing in* is a URL — `/in/housagotchi`
 * — and a test that set the context directly would prove nothing about the rule that matters.
 *
 * The store and sync are the two boundaries, stubbed exactly as `overview.spec.ts` and
 * `task-page.spec.ts` stub them. Everything between is real.
 *
 * **The confirm and the toast are read off the shell's overlay layer, not out of this component**
 * (#67). The omnibox stopped painting either of them when it turned out that painting them from
 * inside `.appbar` clamped them under every other overlay in the app; what it kept is the half that
 * was ever its own — *ask before writing*, and *these are the verbs that offer stands with*.
 */

const NOW_AT = new Date('2026-08-14T10:00:00Z');

/** The clock, movable: the appbar outlives midnight and has to notice. */
let at = NOW_AT;

let held: Task[] = [];
let heldTemplates: TaskTemplate[] = [];
let recorded: TaskPatch[] = [];
let lastContext: string | null = null;
let revision = signal(0);
let harness: RouterTestingHarness;
let page: HTMLElement;

async function standingAt(url: string): Promise<HTMLElement> {
  harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  const fixture = TestBed.createComponent(Omnibox);
  await fixture.whenStable();
  page = fixture.nativeElement as HTMLElement;
  return page;
}

function box(): HTMLInputElement {
  const input = page.querySelector<HTMLInputElement>('input.query');
  if (input === null) {
    throw new Error('The appbar has no omnibox.');
  }
  return input;
}

async function type(what: string): Promise<void> {
  const input = box();
  input.value = what;
  input.dispatchEvent(new Event('input'));
  await settle();
}

async function press(key: string): Promise<void> {
  box().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
  await settle();
}

async function click(selector: string): Promise<void> {
  const found = page.querySelector<HTMLElement>(selector);
  if (found === null) {
    throw new Error(`No '${selector}' in the omnibox.`);
  }
  found.click();
  await settle();
}

async function settle(): Promise<void> {
  await harness.fixture.whenStable();
  harness.fixture.detectChanges();
}

function texts(selector: string): string[] {
  return [...page.querySelectorAll(selector)].map((node) => node.textContent?.trim() ?? '');
}

/** The question standing in the shell's one confirm. */
function asking(): Ask | null {
  return TestBed.inject(Confirms).asking();
}

/** Answers it as a person would, and lets the omnibox act on it. */
async function answer(on: string | null): Promise<void> {
  const ask = asking();
  if (ask === null) {
    throw new Error('Nothing is being confirmed.');
  }
  ask.answer(on);
  await flush();
  await settle();
}

/** What is standing in the shell's one corner. */
function toast(): Toast | null {
  return TestBed.inject(Toasts).showing();
}

/** Presses Escape the way the shell does: at the topmost overlay, wherever focus happens to be. */
async function escape(): Promise<void> {
  TestBed.inject(Overlays).escape();
  await settle();
}

/** The task a capture created, folded from the patch that created it. */
function captured(): Task {
  const creation = recorded.find((patch) => 'creationDateTime' in patch.changes);
  if (creation === undefined) {
    throw new Error(`Nothing was captured. Recorded: ${JSON.stringify(recorded)}`);
  }
  return foldOf(creation.taskId, [creation]);
}

beforeEach(() => {
  held = [];
  heldTemplates = [];
  recorded = [];
  lastContext = null;
  at = NOW_AT;
  revision = signal(0);
  TestBed.configureTestingModule({
    providers: [
      provideRouter(routes, withComponentInputBinding()),
      { provide: NOW, useValue: () => at },
      {
        provide: LocalStore,
        useValue: {
          tasks: () => Promise.resolve([...held]),
          templates: () => Promise.resolve([...heldTemplates]),
          // Reached only by *Add details*, which really navigates to the task dialog: the harness
          // renders whatever the route lands on, so the boundary has to answer for that screen too.
          task: (id: string) => Promise.resolve(held.find((task) => task.id === id) ?? null),
          lastContext: () => Promise.resolve(lastContext),
          setLastContext: vi.fn((context: string) => {
            lastContext = context;
            return Promise.resolve();
          }),
        },
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
            // The real service writes through the store, so a captured task is on this device the
            // instant it is made — which is what *Add details* then navigates to. A stub that only
            // remembered the patch would make the dialog redirect as if the task did not exist.
            //
            // **Refolded against the task's whole history**, exactly as `LocalStore.refold` does.
            // Folding only the patches this stub had seen threw `IncompleteTaskHistoryError` for
            // every completion of a task that was already here — a rejection inside a click
            // handler, so no test failed on it and vitest went red on the exit code alone.
            const existing = held.find((task) => task.id === patch.taskId);
            const history = [
              ...(existing?.history ?? []),
              ...recorded.filter((made) => made.taskId === patch.taskId),
            ];
            const task = foldOf(patch.taskId, history);
            held = [...held.filter((other) => other.id !== task.id), task];
            return Promise.resolve(task);
          }),
        },
      },
    ],
  });
});

describe('capture by typing', () => {
  it('creates a task on Enter, from the name alone', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');

    expect(captured().name).toBe('Ramen lappen');
  });

  it('gives it the context you are standing in', async () => {
    // ADR-0006's card click *enters* a context, so a task captured from inside one belongs to it by
    // construction rather than by history.
    await standingAt('/in/housagotchi');

    await type('Ramen lappen');
    await press('Enter');

    expect(captured().context).toBe('housagotchi');
  });

  it('falls back to the last context captured into, and remembers the new one', async () => {
    lastContext = 'social';
    await standingAt('/');

    await type('Bellen met oma');
    await press('Enter');

    expect(captured().context).toBe('social');
    expect(TestBed.inject(LocalStore).setLastContext).toHaveBeenCalledWith('social');
  });

  it('empties itself after a capture, ready for the next one', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');

    expect(box().value).toBe('');
  });

  it('creates nothing from an empty box', async () => {
    await standingAt('/');

    await type('   ');
    await press('Enter');

    expect(recorded).toEqual([]);
  });

  it('puts the box down on Escape without changing the URL', async () => {
    // The omnibox is a control on the appbar and deliberately **not** a route (ADR-0014), so
    // *Escape returns you where you were* means the panel closes and the URL is untouched.
    //
    // Pressed on **unfinished** input, which is the whole test: the first version of this pressed
    // Escape after Enter, and `capture()` had already emptied the box — so both assertions held
    // before the key was dispatched, and it passed against an omnibox with no Escape binding at all.
    held = [aTask({ name: 'Beddengoed wassen' })];
    await standingAt('/in/housagotchi');

    await type('bedden');
    expect(page.querySelector('.panel')).toBeTruthy();

    await escape();

    expect(box().value).toBe('');
    expect(page.querySelector('.panel')).toBeNull();
    expect(TestBed.inject(Router).url).toBe('/in/housagotchi');
  });

  it('answers Escape wherever focus is, not only from the caret', async () => {
    // The chips and the suggestions are real, Tab-reachable buttons, and #60 scoped Escape to the
    // component host so it caught them. #67 took the key off every component and gave it to the
    // shell: the omnibox says *I am open* while the dropdown is up, and the topmost overlay is the
    // one that answers — which is the same promise without a listener of its own.
    held = [aTask({ context: 'house' }), aTask({ context: 'social' })];
    await standingAt('/');

    await type('Ramen lappen');
    expect(page.querySelector('.panel')).toBeTruthy();

    await escape();

    expect(box().value).toBe('');
    expect(page.querySelector('.panel')).toBeNull();
  });

  it('gives Escape to the confirm first, and only then to the box', async () => {
    // The defect #67 names, from the other end: one press must dismiss **one** overlay. The confirm
    // opened over the dropdown, so it goes first and what is typed survives it.
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    expect(asking()).not.toBeNull();

    await escape();

    expect(asking()).toBeNull();
    expect(box().value).toBe('bedden');
    expect(recorded).toEqual([]);

    await escape();

    expect(box().value).toBe('');
  });
});

describe('marking something done by name', () => {
  it('offers the open tasks that match, each saying which state it is in', async () => {
    // ADR-0014 collapsed the dropdown's two groups into one list of things you can mark done, with
    // the sub-line carrying the difference — *7 days overdue* against *no due date*.
    held = [
      aTask({ name: 'Beddengoed wassen', dueDate: '2026-08-07' }),
      aTask({ name: 'Was sorteren', dueDate: null }),
      aTask({ name: 'Onderhoud ketels' }),
    ];
    await standingAt('/');

    await type('was');

    expect(texts('.suggestion .name')).toEqual(['Beddengoed wassen', 'Was sorteren']);
    expect(texts('.suggestion .state')).toEqual(['7 days overdue', 'no due date']);
  });

  it('offers creating what you typed underneath, even when something matches', async () => {
    held = [aTask({ name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('was');

    expect(page.querySelector('.create')?.textContent).toContain('was');
  });

  it('asks when you did it rather than completing on the spot', async () => {
    // *Chosen by name asks; acted on in place does not* (ADR-0014). Picking a row here is recording
    // something that already happened, so nothing is written until the confirm is answered.
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');

    expect(asking()?.what).toBe('Beddengoed wassen');
    expect(recorded).toEqual([]);
  });

  it('records the completion on the day the confirm was answered with', async () => {
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    await answer('2026-08-11');

    expect(recorded.map((patch) => [patch.taskId, patch.changes])).toEqual([
      ['bed', { status: 'COMPLETED', completedOn: '2026-08-11' }],
    ]);
  });

  it('writes nothing when the confirm is cancelled', async () => {
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    await answer(null);

    expect(recorded).toEqual([]);
    expect(asking()).toBeNull();
  });

  it('empties the box once something has been marked done', async () => {
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    await answer('2026-08-14');

    expect(box().value).toBe('');
    expect(asking()).toBeNull();
  });
});

describe('the context chips', () => {
  it('offers every context this device knows, marking the one a capture would land in', async () => {
    held = [aTask({ context: 'social' }), aTask({ context: 'house' }), aTask({ context: 'house' })];
    await standingAt('/in/house');

    await type('Ramen lappen');

    expect(texts('.chip')).toEqual(['house', 'social']);
    expect(texts('.chip.on')).toEqual(['house']);
  });

  it('changes where a capture lands, in one tap, before Enter', async () => {
    // Not a token syntax: `#house` is a vocabulary to remember whose failure mode is silently
    // eating a word out of a task name (ADR-0018). A chip cannot corrupt what you typed.
    held = [aTask({ context: 'social' }), aTask({ context: 'house' })];
    await standingAt('/in/house');

    await type('Bellen met oma');
    await click('.chip:not(.on)');
    await press('Enter');

    expect(captured().context).toBe('social');
    expect(captured().name).toBe('Bellen met oma');
  });

  it('forgets the chip once the capture is made, so the next one starts where you are', async () => {
    held = [aTask({ context: 'social' }), aTask({ context: 'house' })];
    await standingAt('/in/house');

    await type('Bellen met oma');
    await click('.chip:not(.on)');
    await press('Enter');
    await type('Ramen lappen');

    expect(texts('.chip.on')).toEqual(['house']);
  });
});

/**
 * The offer a capture leaves standing. **What it looks like is `create-toast.spec.ts`'s**; what it
 * *does* is the omnibox's, and that is what these drive — the toast carries the verbs of the screen
 * that raised it, and the shell paints them without knowing what they mean.
 */
describe('the create toast', () => {
  it('names what was added and where it landed', async () => {
    await standingAt('/in/housagotchi');

    await type('Ramen lappen');
    await press('Enter');

    expect(toast()).toMatchObject({
      kind: 'created',
      name: 'Ramen lappen',
      context: 'housagotchi',
    });
  });

  it('gives the task a due date in one tap', async () => {
    // A captured task has no due date on purpose — a default one lies about when the thing was
    // needed. Due date is the most-edited field in six years of history (1,397 edits), so this tap
    // is what stops the edit dialog opening for the ordinary case.
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');
    const offer = toast();
    if (offer?.kind !== 'created') {
      throw new Error('No capture is being offered a due date.');
    }
    offer.due(1);
    await settle();

    const created = captured();
    expect(recorded.map((patch) => patch.changes)).toEqual([
      expect.objectContaining({ name: 'Ramen lappen' }),
      { dueDate: '2026-08-15' },
    ]);
    expect(recorded[1].taskId).toBe(created.id);
  });

  it('opens the task dialog on Add details, as an ordinary navigation', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');
    const offer = toast();
    if (offer?.kind !== 'created') {
      throw new Error('No capture is offering details.');
    }
    offer.details();
    await settle();

    expect(TestBed.inject(Router).url).toBe(`/task/${captured().id}`);
  });

  it('gives the corner back once a due date has been chosen', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');
    const offer = toast();
    if (offer?.kind !== 'created') {
      throw new Error('No capture is being offered a due date.');
    }
    offer.due(0);
    await settle();

    expect(toast()).toBeNull();
  });
});

describe('where a capture lands before anything has been captured', () => {
  it('prefers a context this device really has over the invented default', async () => {
    // Found by driving the real app: standing at `/` on a device holding four contexts, the chip
    // row marked `general` — a literal nothing in the data had ever named.
    held = [
      aTask({ context: 'house', creationDateTime: '2026-03-01T09:00:00Z' }),
      aTask({ context: 'setlist', creationDateTime: '2026-03-09T09:00:00Z' }),
    ];
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');

    expect(captured().context).toBe('setlist');
  });

  it('falls back to a literal only on a device that holds nothing at all', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');

    expect(captured().context).toBe('general');
  });
});

describe('undoing a completion made by name', () => {
  it('offers an undo, which writes a void patch naming the completion', async () => {
    // ADR-0015: an ~8 second toast with *Undo* follows **any action that removes a row**, and this
    // one removes a row from a screen that does not show closed tasks. It is also the only
    // correction path ADR-0011's amendment and ADR-0018 left for `completedOn`: undo and recomplete
    // inside the toast, or a wrong completion date is permanent — the task is closed, the omnibox
    // is open-only, and the overview will never show it again.
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    await answer('2026-08-14');

    const offer = toast();
    if (offer?.kind !== 'undoable') {
      throw new Error('Nothing is offering to be undone.');
    }
    expect(offer.what).toContain('Beddengoed wassen');

    offer.undo();
    await settle();

    expect(recorded).toHaveLength(2);
    expect(recorded[1].voids).toBe(recorded[0].id);
    expect(recorded[1].changes).toEqual({});
  });

  it('shows one toast at a time, so a capture does not sit under a completion', async () => {
    // The corner is one slot (#67), so this is now true by construction rather than by the omnibox
    // remembering to clear its own — which is exactly what it could not do about the overview's.
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    await answer('2026-08-14');
    await type('Ramen lappen');
    await press('Enter');

    expect(toast()?.kind).toBe('created');
  });
});

describe('the date the omnibox measures against', () => {
  it('moves with the calendar, so a completion after midnight is not dated yesterday', async () => {
    // `NOW` is a plain function, not a signal, so a `computed` over it memoises **for the life of
    // the tab** — and this is an installed PWA whose appbar is never destroyed. The confirm seeds
    // its date field from this value, so a frozen one writes yesterday into `completedOn`: ADR-0011's
    // domain clock, what a min/max anchor reads, and `CONTEXT.md` says it is never editable
    // afterwards. `overview.ts` met this exact problem and answered it with a signal re-set on
    // reload; this is the same answer.
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    // Read it **first**. A memoised computed is only wrong once it has an answer to remember, so a
    // test that moves the clock before anything has looked at the date passes either way.
    await type('bedden');
    expect(texts('.suggestion .state')).toEqual(['no due date']);

    at = new Date('2026-08-15T10:00:00Z');
    revision.update((value) => value + 1);
    await settle();

    await click('.suggestion');

    // The confirm seeds its field from whatever it is handed, so the date the omnibox hands over
    // *is* the date that gets written.
    expect(asking()?.today).toBe('2026-08-15');
  });
});

/**
 * **The template half of the one list** — the seam #60 stopped at, because it needs templates held
 * on the client and the TypeScript renderer, and neither existed until this ticket.
 */
describe('saying you did a chore that is showing nothing', () => {
  it('offers a template that is not yet due, in the same list as the tasks', async () => {
    held = [aTask({ id: 'bed', name: 'Beddengoed opnieuw kopen' })];
    heldTemplates = [
      aTemplate({
        id: 'bins',
        name: 'Beddengoed wassen',
        taskDefinitions: [aDefinition({ name: 'Beddengoed wassen' })],
      }),
    ];
    await standingAt('/');

    await type('bedden');

    // One list, not two groups: ADR-0014 collapsed the split once every row opened the same
    // confirm, because it was invisible and it listed a due template twice. The **open task leads**,
    // which is the order that ADR states outright.
    expect(texts('.suggestion .name')).toEqual(['Beddengoed opnieuw kopen', 'Beddengoed wassen']);
  });

  it('says which state each row is in, in words', async () => {
    heldTemplates = [
      aTemplate({ name: 'Vuilbakken', taskDefinitions: [aDefinition({ name: 'Vuilbakken' })] }),
    ];
    held = [];
    await standingAt('/');

    await type('vuilbak');

    expect(texts('.suggestion .state')).toEqual(['never done']);
  });

  /**
   * ADR-0011's second shape, through the omnibox: a task created and completed in one breath. The
   * confirm is the same one a task row opens, which is the whole reason two capture paths are
   * affordable.
   */
  it('mints a task created and completed in one breath, on the day you say', async () => {
    heldTemplates = [
      aTemplate({
        id: 'bins',
        name: 'Vuilbakken',
        trigger: minMax(10, 0),
        taskDefinitions: [aDefinition({ name: 'Vuilbakken buitenzetten' })],
      }),
    ];
    await standingAt('/');

    await type('vuilbak');
    await click('.suggestion');
    await answer('2026-08-11');

    expect(recorded).toHaveLength(2);
    const task = foldOf(recorded[0].taskId, recorded);
    expect(task.name).toBe('Vuilbakken buitenzetten');
    expect(task.status).toBe('COMPLETED');
    expect(task.completedOn).toBe('2026-08-11');
    expect(task.taskTemplateId).toBe('bins');
  });

  it('offers undo for it, exactly as it does for a task', async () => {
    heldTemplates = [
      aTemplate({ name: 'Vuilbakken', taskDefinitions: [aDefinition({ name: 'Vuilbakken' })] }),
    ];
    await standingAt('/');

    await type('vuilbak');
    await click('.suggestion');
    await answer('2026-08-14');

    const offer = toast();
    if (offer?.kind !== 'undoable') {
      throw new Error('Nothing is offering to be undone.');
    }
    offer.undo();
    await settle();

    const undo = recorded[recorded.length - 1];
    expect(undo.voids).toBe(recorded[recorded.length - 2].id);
  });
});

/**
 * The five rows are **shared**, not five each. Capping both halves and then the merge is a cap of
 * ten pretending to be a cap of five, and it starves whichever half is listed second — here, every
 * chore, on a query that matches five tasks.
 */
describe('the one list’s one cap', () => {
  it('gives the templates whatever room the tasks left, rather than a second five', async () => {
    held = Array.from({ length: 5 }, (_unused, index) =>
      aTask({ id: `t${index}`, name: `Vuilbak ${index}`, dueDate: '2026-08-20' }),
    );
    heldTemplates = [
      aTemplate({ name: 'Vuilbakken', taskDefinitions: [aDefinition({ name: 'Vuilbakken' })] }),
    ];
    await standingAt('/');

    await type('vuilbak');

    expect(texts('.suggestion .name')).toHaveLength(5);
    expect(texts('.suggestion .name').every((name) => name.startsWith('Vuilbak '))).toBe(true);
  });
});
