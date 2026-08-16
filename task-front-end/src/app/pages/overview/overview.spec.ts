import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { NOW } from '../../clock';
import { addDays } from '../../domain/dates';
import { foldOf } from '../../domain/fold';
import { Task, TaskPatch } from '../../domain/task';
import { aTask } from '../../domain/task.mother';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { flush } from '../../testing';
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
let recorded: TaskPatch[] = [];
const revision = signal(0);

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
  recorded = [];
  revision.set(0);
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: NOW, useValue: () => NOW_AT },
      { provide: LocalStore, useValue: { tasks: () => Promise.resolve([...held]) } },
      {
        provide: SyncService,
        useValue: {
          revision,
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

    expect(band('Due today').querySelectorAll('app-task-panel')).toHaveLength(5);
    // Folded means *not rendered*, not rendered dead. Portal greyed six tasks out behind a
    // `disabled` class, so the commonest act after clearing the top five was a click that revealed
    // what was already on screen.
    expect(band('Also…').querySelectorAll('app-task-panel')).toHaveLength(0);
    expect(band('Also…').textContent).toContain('3');
    expect(band('Starting in the future…').textContent).toContain('1');
  });

  it('retitles the band when the cap is exceeded, so it is visible why', async () => {
    await render(Array.from({ length: 8 }, () => aTask({ dueDate: TODAY })));

    const title = band('Due today').querySelector('.band-title')?.textContent ?? '';
    expect(title).toContain('all 8');
    expect(title).toContain('the cap does not apply');
    expect(band('Due today').querySelectorAll('app-task-panel')).toHaveLength(8);
  });

  it('leaves the title alone when the cap is not exceeded', async () => {
    await render(Array.from({ length: 3 }, () => aTask({ dueDate: TODAY })));

    expect(band('Due today').querySelector('.band-title')?.textContent).not.toContain('the cap');
  });

  it('does not claim work is due today when none of it is', async () => {
    // The band tops itself up with work that is not due, and a heading is a fact in words. `Due
    // today` over five tasks due next month says something untrue.
    await render(Array.from({ length: 3 }, () => aTask({ dueDate: addDays(TODAY, 20) })));

    expect(texts('.band-title')).toEqual(['Next up']);
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

  it('hides a band that has nothing behind the door', async () => {
    await render([aTask({ dueDate: TODAY })]);

    expect(texts('.band-title')).toEqual(['Due today']);
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

  it('re-reads the store when sync says something changed', async () => {
    await render([]);
    expect(texts('.name')).toEqual([]);

    held = [aTask({ name: 'arrived from the stream', dueDate: TODAY })];
    revision.update((value) => value + 1);
    await fixture.whenStable();

    expect(texts('.name')).toEqual(['arrived from the stream']);
  });
});
