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
import { LocalStore } from '../store/local-store';
import { SyncService } from '../sync/sync';
import { Omnibox } from './omnibox';

/**
 * The omnibox: capture by typing, one keystroke from wherever you are (ADR-0014).
 *
 * Driven through the router, because *the context you are standing in* is a URL — `/in/housagotchi`
 * — and a test that set the context directly would prove nothing about the rule that matters.
 *
 * The store and sync are the two boundaries, stubbed exactly as `overview.spec.ts` and
 * `task-page.spec.ts` stub them. Everything between is real.
 */

const NOW_AT = new Date('2026-08-14T10:00:00Z');

let held: Task[] = [];
let recorded: TaskPatch[] = [];
let lastContext: string | null = null;
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
  recorded = [];
  lastContext = null;
  TestBed.configureTestingModule({
    providers: [
      provideRouter(routes, withComponentInputBinding()),
      { provide: NOW, useValue: () => NOW_AT },
      {
        provide: LocalStore,
        useValue: {
          tasks: () => Promise.resolve([...held]),
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
          revision: signal(0),
          record: vi.fn((patch: TaskPatch) => {
            recorded.push(patch);
            // The real service writes through the store, so a captured task is on this device the
            // instant it is made — which is what *Add details* then navigates to. A stub that only
            // remembered the patch would make the dialog redirect as if the task did not exist.
            const history = recorded.filter((held) => held.taskId === patch.taskId);
            const task = foldOf(patch.taskId, history);
            held = [...held.filter((existing) => existing.id !== task.id), task];
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

  it('never changes the URL, so Escape returns you where you were', async () => {
    // The omnibox is a control on the appbar and deliberately **not** a route (ADR-0014).
    await standingAt('/in/housagotchi');

    await type('Ramen lappen');
    await press('Enter');
    await press('Escape');

    expect(TestBed.inject(Router).url).toBe('/in/housagotchi');
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

    expect(page.querySelector('app-date-confirm')).toBeTruthy();
    expect(recorded).toEqual([]);
  });

  it('records the completion on the day the confirm was answered with', async () => {
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    const field = page.querySelector<HTMLInputElement>("app-date-confirm input[type='date']");
    field!.value = '2026-08-11';
    field!.dispatchEvent(new Event('input'));
    await settle();
    await click('app-date-confirm .confirm');

    expect(recorded.map((patch) => [patch.taskId, patch.changes])).toEqual([
      ['bed', { status: 'COMPLETED', completedOn: '2026-08-11' }],
    ]);
  });

  it('writes nothing when the confirm is cancelled', async () => {
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    await click('app-date-confirm .dismiss');

    expect(recorded).toEqual([]);
    expect(page.querySelector('app-date-confirm')).toBeNull();
  });

  it('empties the box once something has been marked done', async () => {
    held = [aTask({ id: 'bed', name: 'Beddengoed wassen' })];
    await standingAt('/');

    await type('bedden');
    await click('.suggestion');
    await click('app-date-confirm .confirm');

    expect(box().value).toBe('');
    expect(page.querySelector('app-date-confirm')).toBeNull();
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

describe('the create toast', () => {
  it('names what was added and where it landed', async () => {
    await standingAt('/in/housagotchi');

    await type('Ramen lappen');
    await press('Enter');

    expect(page.querySelector('.created')?.textContent).toContain('Ramen lappen');
    expect(page.querySelector('.created')?.textContent).toContain('housagotchi');
  });

  it('gives the task a due date in one tap', async () => {
    // A captured task has no due date on purpose — a default one lies about when the thing was
    // needed. Due date is the most-edited field in six years of history (1,397 edits), so this tap
    // is what stops the edit dialog opening for the ordinary case.
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');
    await click('.created .due[data-days="1"]');

    const created = captured();
    expect(recorded.map((patch) => patch.changes)).toEqual([
      expect.objectContaining({ name: 'Ramen lappen' }),
      { dueDate: '2026-08-15' },
    ]);
    expect(recorded[1].taskId).toBe(created.id);
  });

  it('offers the three the ADR names, and Add details for everything else', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');

    expect(texts('.created .due')).toEqual(['due today', 'tomorrow', 'in 3 days']);
    expect(page.querySelector('.created .details')).toBeTruthy();
  });

  it('opens the task dialog on Add details, as an ordinary navigation', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');
    await click('.created .details');

    expect(TestBed.inject(Router).url).toBe(`/task/${captured().id}`);
  });

  it('goes away once a due date has been chosen', async () => {
    await standingAt('/');

    await type('Ramen lappen');
    await press('Enter');
    await click('.created .due[data-days="0"]');

    expect(page.querySelector('.created')).toBeNull();
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
