import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { NOW } from '../../clock';
import { addDays } from '../../domain/dates';
import { foldOf } from '../../domain/fold';
import { SyncFailure } from '../../domain/sync';
import { Task, TaskPatch } from '../../domain/task';
import { aTask } from '../../domain/task.mother';
import { TaskTemplate } from '../../domain/template';
import { aTemplate, manual } from '../../domain/template.mother';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { flush } from '../../testing';
import { Confirms } from '../../ui/confirms';
import { Toasts } from '../../ui/toasts';
import { Overview } from './overview';

const TODAY = '2026-08-14';
const NOW_AT = new Date('2026-08-14T10:00:00Z');

/**
 * The overview is tested through what it renders and what it writes. Its two collaborators are
 * boundaries rather than internals — the store is IndexedDB and sync is the network — so they are
 * the two things stubbed, and nothing else is.
 */

let held: Task[] = [];
let heldTemplates: TaskTemplate[] = [];
let recorded: TaskPatch[] = [];
const revision = signal(0);
/** What the outbox dropped — the band's input, and empty in every test that is not about it. */
const failures = signal<readonly SyncFailure[]>([]);
let sentAgain: string[] = [];
let forgotten: string[] = [];

let fixture: ComponentFixture<Overview>;

function element(): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function texts(selector: string): string[] {
  return [...element().querySelectorAll(selector)].map((node) => node.textContent?.trim() ?? '');
}

function band(title: string): HTMLElement {
  const found = [...element().querySelectorAll<HTMLElement>('section.band')].find((section) =>
    section.querySelector('.band-title')?.textContent?.includes(title),
  );
  if (found === undefined) {
    throw new Error(`No band titled '${title}'. On screen: ${texts('.band-title').join(' | ')}`);
  }
  return found;
}

async function render(tasks: Task[], context?: string): Promise<void> {
  held = tasks;
  fixture = TestBed.createComponent(Overview);
  if (context !== undefined) {
    fixture.componentRef.setInput('value', context);
  }
  await fixture.whenStable();
}

beforeEach(() => {
  held = [];
  heldTemplates = [];
  recorded = [];
  revision.set(0);
  failures.set([]);
  sentAgain = [];
  forgotten = [];
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: NOW, useValue: () => NOW_AT },
      {
        provide: LocalStore,
        useValue: {
          tasks: () => Promise.resolve([...held]),
          templates: () => Promise.resolve([...heldTemplates]),
        },
      },
      {
        provide: SyncService,
        useValue: {
          revision,
          failures,
          sendAgain: (patchId: string) => {
            sentAgain.push(patchId);
            return Promise.resolve();
          },
          forget: (patchId: string) => {
            forgotten.push(patchId);
            return Promise.resolve();
          },
          // Stands in for the network, not for the fold: the patch goes into its task's history and
          // the row is recomputed by the **real** `foldOf`. Faking the closure instead would make
          // "the row leaves the screen" an assertion about this stub.
          record: vi.fn((patch: TaskPatch) => {
            recorded.push(patch);
            held = held.map((task) =>
              task.id === patch.taskId ? foldOf(task.id, [...task.history, patch]) : task,
            );
            return Promise.resolve(null);
          }),
        },
      },
    ],
  });
});

describe('the bands', () => {
  it('shows the day’s work and folds the rest away', async () => {
    // `soon 0` is due today, so this is the *day's work* the band is named for: one due task, then
    // four more topping it up to the cap.
    await render([
      ...Array.from({ length: 8 }, (_, index) =>
        aTask({ name: `soon ${index}`, dueDate: addDays(TODAY, index) }),
      ),
      aTask({ name: 'sleeping', startDate: addDays(TODAY, 10) }),
    ]);

    expect(band("Today's work").querySelectorAll('app-task-panel')).toHaveLength(5);
    // Folded means *not rendered*, not rendered dead. Portal greyed six tasks out behind a
    // `disabled` class, so the commonest act after clearing the top five was a click that revealed
    // what was already on screen.
    expect(band('Also…').querySelectorAll('app-task-panel')).toHaveLength(0);
    expect(band('Also…').textContent).toContain('3');
    expect(band('Starting in the future…').textContent).toContain('1');
  });

  it('retitles the band when the cap is exceeded, so it is visible why', async () => {
    await render(Array.from({ length: 8 }, () => aTask({ dueDate: TODAY })));

    // `Due today` survives here and nowhere else: when the cap is exceeded every row in the band
    // really is due, so the heading is the one place it can say so and be true.
    const title = band('Due today').querySelector('.band-title')?.textContent ?? '';
    expect(title).toContain('all 8');
    expect(title).toContain('the cap does not apply');
    expect(band('Due today').querySelectorAll('app-task-panel')).toHaveLength(8);
  });

  it('leaves the title alone when the cap is not exceeded', async () => {
    await render(Array.from({ length: 3 }, () => aTask({ dueDate: TODAY })));

    expect(band("Today's work").querySelector('.band-title')?.textContent).not.toContain('the cap');
  });

  it('does not claim work is due today when none of it is', async () => {
    // The band tops itself up with work that is not due, and a heading is a fact in words. `Due
    // today` over five tasks due next month says something untrue.
    await render(Array.from({ length: 3 }, () => aTask({ dueDate: addDays(TODAY, 20) })));

    expect(texts('.band-title')).toEqual(['Next up']);
  });

  it('does not claim work is due today over the rows topping the band up either', async () => {
    // One task due, four borrowed from `Also…` to reach the cap. `Due today` is true of exactly
    // one of the five rows beneath it, which is the same objection as the test above — it just
    // survives having a due task on screen.
    await render(
      Array.from({ length: 6 }, (_, index) =>
        aTask({ name: `soon ${index}`, dueDate: addDays(TODAY, index) }),
      ),
    );

    expect(texts('.band-title')).toEqual(["Today's work", 'Also…']);
  });

  it('opens a folded band, and remembers that it is open', async () => {
    await render(
      Array.from({ length: 8 }, (_, index) =>
        aTask({ name: `soon ${index}`, dueDate: addDays(TODAY, index + 1) }),
      ),
    );

    band('Also…').querySelector<HTMLElement>('.foldbar')?.click();
    await fixture.whenStable();

    expect(band('Also…').querySelectorAll('app-task-panel')).toHaveLength(3);
    expect(band('Also…').querySelector('.foldbar')).toBeNull();
  });

  it('draws a chevron on a folded band, so the dashed border is not the only thing saying open me', async () => {
    await render(
      Array.from({ length: 8 }, (_, index) =>
        aTask({ name: `soon ${index}`, dueDate: addDays(TODAY, index + 1) }),
      ),
    );

    const chevron = band('Also…').querySelector('.foldbar .chevron');
    expect(chevron?.textContent?.trim()).toBe('▾');
    // Drawn, so the screen reader is not told about a shape (ADR-0019).
    expect(chevron?.getAttribute('aria-hidden')).toBe('true');
  });

  it('hides a band that has nothing behind the door', async () => {
    await render([aTask({ dueDate: TODAY })]);

    expect(texts('.band-title')).toEqual(["Today's work"]);
  });

  it('says so when there is nothing at all, in FE-006’s own words', async () => {
    await render([]);

    expect(element().textContent).toContain('Relax! Nothing else to do.');
  });

  it('does not say there is nothing to do while work sleeps in the future band', async () => {
    // Portal's own condition, and the reason its wording says *nothing else*: sleeping work is
    // still work, so the message must not appear over a future band that has something in it.
    await render([aTask({ startDate: addDays(TODAY, 10) })]);

    expect(element().textContent).not.toContain('Relax!');
    expect(band('Starting in the future…')).toBeTruthy();
  });
});

describe('what a folded band says is behind its door', () => {
  it('answers the two questions that would make you open `Also…`', async () => {
    // Of four variants the author drove, words beat stripes: a distribution nobody can act on is
    // not worth a colour. (It would once have clashed with the six-segment bar on the cards one
    // band above; #82 dropped that bar, but the distribution argument stands on its own.)
    await render(
      Array.from({ length: 8 }, (_, index) =>
        aTask({
          name: `soon ${index}`,
          dueDate: addDays(TODAY, index + 5),
          importance: 'NOT_SO_IMPORTANT',
        }),
      ),
    );

    expect(band('Also…').querySelector('.foldbar')?.textContent).toContain(
      'nothing urgent · soonest in 10 days',
    );
  });

  it('leaves `Starting in the future…` at a bare count', async () => {
    // Nothing that has not started is taken into consideration by anything speaking about urgency
    // (ADR-0015), and that band holds nothing else — so it has nothing to say. A `soonest` term
    // over it would render `soonest 62 days overdue` for a sleeping overdue task: true, unreadable,
    // and no answer at all to *should I open this*.
    await render([
      aTask({ dueDate: TODAY }),
      aTask({
        name: 'Onderhoud ketels',
        dueDate: addDays(TODAY, -62),
        startDate: addDays(TODAY, 5),
        importance: 'VERY_IMPORTANT',
      }),
    ]);

    const bar = band('Starting in the future…').querySelector('.foldbar')?.textContent ?? '';
    expect(bar).toContain('Starting in the future…');
    expect(bar).toContain('1');
    expect(bar).not.toContain('soonest');
    expect(bar).not.toContain('urgent');
  });
});

describe('the context cards', () => {
  it('stands above the bands, over every context this device holds', async () => {
    await render([
      aTask({ name: 'house one', context: 'house', dueDate: TODAY }),
      aTask({ name: 'health one', context: 'health', dueDate: TODAY }),
    ]);

    expect(texts('app-context-cards .context')).toEqual(['health', 'house']);
  });

  it('keeps every card while you are inside one, so the row is how you switch', async () => {
    await render(
      [
        aTask({ name: 'house one', context: 'house', dueDate: TODAY }),
        aTask({ name: 'health one', context: 'health', dueDate: TODAY }),
      ],
      'house',
    );

    expect(texts('app-context-cards .context')).toEqual(['health', 'house']);
  });
});

describe('entering a context', () => {
  it('scopes every band to it', async () => {
    await render(
      [
        aTask({ name: 'house one', context: 'house', dueDate: TODAY }),
        aTask({ name: 'health one', context: 'health', dueDate: TODAY }),
      ],
      'house',
    );

    expect(texts('.name')).toEqual(['house one']);
  });

  it('says where you are, and how to leave', async () => {
    await render(
      [
        aTask({ context: 'house', dueDate: TODAY }),
        aTask({ context: 'house', startDate: addDays(TODAY, 10) }),
        aTask({ context: 'health', dueDate: TODAY }),
      ],
      'house',
    );

    // Two, not one: the same total the card carries, said in the same word. A number that changed
    // meaning one line apart would be the card's own count contradicted on the screen it opened.
    expect(element().querySelector('.scope')?.textContent).toContain('house — 2 open');
    expect(element().querySelector('.scope a')?.getAttribute('href')).toBe('/');
  });

  it('drops the context chip from every row, since they would all carry the same one', async () => {
    await render(
      [
        aTask({ name: 'house one', context: 'house', dueDate: TODAY }),
        aTask({ name: 'house two', context: 'house', dueDate: TODAY }),
      ],
      'house',
    );

    // The chip on the rows only. The card row above is over everything the device holds, so its
    // own contexts stay named.
    expect(texts('app-task-panel .context')).toEqual([]);
  });

  it('keeps the context chip on the rows at `/`, where it is the one thing telling them apart', async () => {
    await render([
      aTask({ context: 'house', dueDate: TODAY }),
      aTask({ context: 'health', dueDate: TODAY }),
    ]);

    expect(texts('app-task-panel .context').sort()).toEqual(['health', 'house']);
  });

  it('says nothing about scope at `/`, where there is none', async () => {
    await render([aTask({ dueDate: TODAY })]);

    expect(element().querySelector('.scope')).toBeNull();
  });
});

describe('acting on a task', () => {
  it('records the patch and takes the row off the screen', async () => {
    await render([aTask({ id: 'a', name: 'Call mum', dueDate: TODAY })]);

    element().querySelector<HTMLElement>('.row')?.click();
    await fixture.whenStable();
    element().querySelector<HTMLElement>('button[aria-label="Complete"]')?.click();
    await fixture.whenStable();

    expect(recorded).toHaveLength(1);
    expect(recorded[0].changes).toEqual({ status: 'COMPLETED', completedOn: TODAY });
    expect(texts('.name')).toEqual([]);
  });

  it('offers an undo, which writes a void patch naming the one it undoes', async () => {
    // Complete, cancel and postpone all make a row leave, and the overview does not show closed
    // tasks — so without this a mis-swipe vanishes with no trace at all. It is also the only path
    // that exercises ADR-0004's void patch in normal use.
    await render([aTask({ id: 'a', dueDate: TODAY })]);

    element().querySelector<HTMLElement>('.row')?.click();
    await fixture.whenStable();
    element().querySelector<HTMLElement>('button[aria-label="Complete"]')?.click();
    await fixture.whenStable();

    // The offer stands in the shell's one corner (#67), not on this screen: the overview used to
    // paint a toast of its own, and the omnibox another, and completing here and then capturing
    // within eight seconds put both in the same place with the newer one underneath.
    const offer = TestBed.inject(Toasts).showing();
    if (offer?.kind !== 'undoable') {
      throw new Error('Nothing is offering to be undone.');
    }
    expect(offer.what).toContain('Completed');

    offer.undo();
    await flush();
    await fixture.whenStable();

    expect(recorded).toHaveLength(2);
    expect(recorded[1].voids).toBe(recorded[0].id);
    expect(recorded[1].changes).toEqual({});
  });

  async function completeFromPanel(): Promise<void> {
    element().querySelector<HTMLElement>('.row')?.click();
    await fixture.whenStable();
    element().querySelector<HTMLElement>('button[aria-label="Complete"]')?.click();
    await fixture.whenStable();
  }

  function undoable() {
    const offer = TestBed.inject(Toasts).showing();
    if (offer?.kind !== 'undoable') {
      throw new Error('Nothing is offering to be undone.');
    }
    return offer;
  }

  it('lets a silent panel completion move to another day, as undo-then-recomplete (issue #83)', async () => {
    // A swipe and a plain Complete tap both file the task under today with nothing in the way
    // (ADR-0014). This row is the *"oh wait, that was yesterday"* path, inside the horizon — and
    // ADR-0018 makes the correction undo-then-recomplete, because the patch id is an idempotency key.
    await render([aTask({ id: 'a', name: 'Call mum', dueDate: TODAY })]);
    await completeFromPanel();

    const offer = undoable();
    expect(offer.correction?.on).toBe(TODAY);
    expect(offer.correction?.today).toBe(TODAY);

    offer.correction?.changeDay(addDays(TODAY, -1));
    await flush();
    await fixture.whenStable();

    expect(recorded).toHaveLength(3);
    expect(recorded[0].changes).toEqual({ status: 'COMPLETED', completedOn: TODAY });
    expect(recorded[1].voids).toBe(recorded[0].id);
    expect(recorded[2].changes).toEqual({ status: 'COMPLETED', completedOn: addDays(TODAY, -1) });
    // The toast now names the recompletion, so a further nudge or an Undo acts on the right patch.
    expect(undoable().correction?.on).toBe(addDays(TODAY, -1));
  });

  it('sends "In the past…" through the shared confirm before the recomplete', async () => {
    vi.spyOn(TestBed.inject(Confirms), 'ask').mockResolvedValue('2026-08-01');
    await render([aTask({ id: 'a', name: 'Call mum', dueDate: TODAY })]);
    await completeFromPanel();

    undoable().correction?.pickDay();
    await flush();
    await fixture.whenStable();

    expect(recorded[2].changes).toEqual({ status: 'COMPLETED', completedOn: '2026-08-01' });
  });

  it('keeps "In the past…" a no-op when the confirm is dismissed', async () => {
    vi.spyOn(TestBed.inject(Confirms), 'ask').mockResolvedValue(null);
    await render([aTask({ id: 'a', dueDate: TODAY })]);
    await completeFromPanel();

    undoable().correction?.pickDay();
    await flush();
    await fixture.whenStable();

    expect(recorded).toHaveLength(1);
  });

  it('offers no day change behind a cancel — there is no completedOn to move', async () => {
    await render([aTask({ id: 'a', dueDate: TODAY })]);
    element().querySelector<HTMLElement>('.row')?.click();
    await fixture.whenStable();
    element().querySelector<HTMLElement>('button[aria-label="Cancel"]')?.click();
    await fixture.whenStable();

    const offer = undoable();
    expect(offer.correction).toBeUndefined();
  });

  it('re-reads the store when sync says something changed', async () => {
    await render([]);
    expect(texts('.name')).toEqual([]);

    held = [aTask({ name: 'arrived from the stream', dueDate: TODAY })];
    revision.update((value) => value + 1);
    await fixture.whenStable();

    expect(texts('.name')).toEqual(['arrived from the stream']);
  });
});

/**
 * The rejected-changes band (ADR-0014). What only this screen can say is *where* it is — above the
 * work, because a rejection is something you believe you did that did not happen — and that its two
 * verbs reach sync. What a row *says* is `ui/rejections.spec.ts`.
 */
describe('the changes the server refused', () => {
  const REFUSED = {
    patchId: 'p1',
    taskId: 'a',
    status: 400,
    at: '2026-08-12T18:00:04+02:00',
  };

  async function withRejection(): Promise<void> {
    failures.set([REFUSED]);
    const completion: TaskPatch = {
      id: 'p1',
      taskId: 'a',
      dateTime: '2026-08-12T18:00:00+02:00',
      sequence: null,
      voids: null,
      changes: { status: 'COMPLETED', completedOn: '2026-08-12' },
    };
    const created: TaskPatch = { ...completion, id: 'p0', changes: { name: 'Call the dentist' } };
    await render([
      aTask({
        id: 'a',
        name: 'Call the dentist',
        status: 'COMPLETED',
        history: [created, completion],
      }),
      aTask({ name: 'Vacuum the living room', dueDate: TODAY }),
    ]);
  }

  it('stands above the day’s work, which is the whole placement argument', async () => {
    await withRejection();

    const bands = [...element().querySelectorAll('section.band')];
    expect(bands[0].classList.contains('rejected')).toBe(true);
    expect(bands[0].querySelector('.act')?.textContent).toContain('Call the dentist');
  });

  it('speaks for a task that is no longer on the overview at all', async () => {
    // The common case, and the reason the band exists: the completion succeeded locally, the fold
    // closed the task, and this screen does not show closed tasks.
    await withRejection();

    expect(texts('.name')).toEqual(['Vacuum the living room']);
    expect(element().querySelector('.act')?.textContent).toContain('marked complete');
  });

  it('sends it again on `Fix and retry`', async () => {
    await withRejection();

    element().querySelector<HTMLElement>('.retry')?.click();
    await flush();

    expect(sentAgain).toEqual(['p1']);
  });

  it('forgets it on `Discard`', async () => {
    await withRejection();

    element().querySelector<HTMLElement>('.discard')?.click();
    await flush();

    expect(forgotten).toEqual(['p1']);
  });

  it('leaves no trace when nothing was refused', async () => {
    await render([aTask({ dueDate: TODAY })]);

    expect(element().querySelector('section.band.rejected')).toBeNull();
  });
});

/**
 * The `↻ last done…` line in the expanded panel (#88). The overview holds the templates and the
 * tasks, floors the server's whole-history value with the completions this device keeps, and
 * passes the result to each panel — the panel itself reads no store.
 */
describe('the last-done line for a template task', () => {
  function expand(): void {
    element().querySelector<HTMLElement>('.row')?.click();
  }

  it('shows when the chore was last done, from the server value this device has pruned past', async () => {
    heldTemplates = [aTemplate({ id: 'boiler', lastCompletedOn: '2026-06-01' })];
    await render([aTask({ id: 'a', taskTemplateId: 'boiler', dueDate: TODAY })]);

    expand();
    await fixture.whenStable();

    expect(texts('app-task-panel .facts')).toEqual(['↻ last 74 days ago · 1 Jun']);
  });

  it('says never done for a template task whose chore has no recorded completion', async () => {
    heldTemplates = [aTemplate({ id: 'boiler', lastCompletedOn: null })];
    await render([aTask({ id: 'a', taskTemplateId: 'boiler', dueDate: TODAY })]);

    expand();
    await fixture.whenStable();

    expect(texts('app-task-panel .facts')).toEqual(['↻ never done']);
  });

  it('renders no such line for a task no template made', async () => {
    await render([aTask({ id: 'a', taskTemplateId: null, dueDate: TODAY })]);

    expand();
    await fixture.whenStable();

    expect(element().querySelector('app-task-panel .facts')).toBeNull();
  });

  /**
   * #90: a `MANUAL` template has no cadence — it is run by answering its anchor question, not
   * ticked. A task it generated must not carry a `↻ last done` line (it would only ever read
   * `never done`), so the overview tells the panel the template is manual.
   */
  it('renders no such line for a task from a manual template', async () => {
    heldTemplates = [aTemplate({ id: 'workshop', trigger: manual('When is the workshop?') })];
    await render([aTask({ id: 'a', taskTemplateId: 'workshop', dueDate: TODAY })]);

    expand();
    await fixture.whenStable();

    expect(element().querySelector('app-task-panel .facts')).toBeNull();
  });
});
