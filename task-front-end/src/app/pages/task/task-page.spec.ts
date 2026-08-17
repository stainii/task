import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { routes } from '../../app.routes';
import { NOW } from '../../clock';
import { Task, TaskPatch } from '../../domain/task';
import { aTask } from '../../domain/task.mother';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { Notices } from '../../ui/notices';
import { Overlays } from '../../ui/overlays';

const NOW_AT = new Date('2026-08-14T10:00:00Z');

/**
 * The task dialog is tested through the route it lives on, because the route **is** part of the
 * design (ADR-0018): the look and the addressability were decided separately, and hardware back,
 * ADR-0012's 07:30 push and the create toast's *Add details* all depend on the URL rather than on
 * the component. So navigation is driven, not simulated.
 *
 * Its two collaborators are boundaries and nothing else is stubbed — the store is IndexedDB and
 * sync is the network, exactly as in `overview.spec.ts`.
 */

let held: Task[] = [];
let recorded: TaskPatch[] = [];
let harness: RouterTestingHarness;

function router(): Router {
  return TestBed.inject(Router);
}

async function open(task: Task | null, at = '/task/a'): Promise<HTMLElement> {
  held = task === null ? [] : [task];
  harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(at);
  await settle();
  return harness.fixture.nativeElement as HTMLElement;
}

/** Lets the resource resolve and the view catch up with it. */
async function settle(): Promise<void> {
  await harness.fixture.whenStable();
}

/**
 * Renders what is on screen **without** waiting for the app to go stable.
 *
 * The confirm exists *during* a navigation the guard is holding open, so the app is deliberately
 * unstable while it is up: `whenStable()` would wait for the very answer the assertion is about.
 */
async function paint(): Promise<void> {
  // A macrotask, not a microtask: the guard runs inside the router's own async pipeline, so the
  // question is not asked yet at the end of the current tick.
  await new Promise((resume) => setTimeout(resume, 0));
  harness.fixture.detectChanges();
}

function field<T extends HTMLElement>(page: HTMLElement, name: string): T {
  const found = page.querySelector<T>(`[data-field='${name}']`);
  if (found === null) {
    throw new Error(`No field '${name}' on the form.`);
  }
  return found;
}

function texts(page: HTMLElement, selector: string): string[] {
  return [...page.querySelectorAll(selector)].map((node) => node.textContent?.trim() ?? '');
}

function button(page: HTMLElement, selector: string): HTMLElement {
  const found = page.querySelector<HTMLElement>(selector);
  if (found === null) {
    throw new Error(`No '${selector}' on the dialog.`);
  }
  return found;
}

const save = (page: HTMLElement) => button(page, '.actions .save');
const cancel = (page: HTMLElement) => button(page, '.actions .discard');
const scrim = (page: HTMLElement) => button(page, '.scrim');

/** Types into a control the way a person does: set the value, then let the app hear about it. */
function type(control: HTMLElement, value: string): void {
  const input = control as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
  input.value = value;
  input.dispatchEvent(new Event(control instanceof HTMLSelectElement ? 'change' : 'input'));
}

beforeEach(() => {
  held = [];
  recorded = [];
  TestBed.configureTestingModule({
    providers: [
      provideRouter(routes, withComponentInputBinding()),
      { provide: NOW, useValue: () => NOW_AT },
      {
        provide: LocalStore,
        useValue: {
          tasks: () => Promise.resolve([...held]),
          task: (id: string) => Promise.resolve(held.find((task) => task.id === id) ?? null),
        },
      },
      {
        provide: SyncService,
        useValue: {
          revision: signal(0),
          // The dialog is a route *over* the overview (ADR-0018), so the shell renders both and the
          // stub has to answer what the band below it reads.
          failures: signal([]),
          record: vi.fn((patch: TaskPatch) => {
            recorded.push(patch);
            return Promise.resolve(null);
          }),
        },
      },
    ],
  });
});

describe('a task that can no longer be edited', () => {
  it('redirects a completed task to the overview rather than 404ing', async () => {
    // Routing makes `/task/:id` deep-linkable including to a task that has since been closed, which
    // ADR-0012's 07:30 push makes ordinary rather than exotic. Opening it editable would let a
    // change be saved into a task the overview will never show again.
    await open(aTask({ id: 'a', name: 'Beddengoed wassen', status: 'COMPLETED' }));

    expect(router().url).toBe('/');
  });

  it('says why, because a screen that silently swaps itself out reads as a bug', async () => {
    await open(aTask({ id: 'a', name: 'Beddengoed wassen', status: 'COMPLETED' }));

    expect(TestBed.inject(Notices).message()).toBe('Beddengoed wassen is already completed.');
  });

  it('redirects a cancelled task too, and names its own status', async () => {
    await open(aTask({ id: 'a', name: 'Gitaar schoonmaken', status: 'CANCELLED' }));

    expect(router().url).toBe('/');
    expect(TestBed.inject(Notices).message()).toBe('Gitaar schoonmaken is already cancelled.');
  });

  it('redirects a task this device has never heard of', async () => {
    // The client is authoritative for display at all times, so *not in the store* is the only
    // answer there is — there is no server to ask, and a spinner would wait for ever offline.
    await open(null);

    expect(router().url).toBe('/');
    expect(TestBed.inject(Notices).message()).toBe('That task is not on this device.');
  });
});

describe('the form', () => {
  const KETEL = aTask({
    id: 'a',
    name: 'Onderhoud ketels',
    context: 'house',
    importance: 'VERY_IMPORTANT',
    dueDate: '2026-06-30',
    startDate: '2026-08-17',
    description: 'Vraag de firma om langs te komen.',
  });

  it('shows all six fields at once — no steps, no drawer', async () => {
    // The stepper's sin was making six fields into six screens, not having them (ADR-0018). This
    // deliberately inverts ADR-0013's progressive drawer, because the rule that hides what is
    // rarely *set* would hide description and *ask me from* — the 3rd and 4th most-edited fields
    // in six years of real use.
    const page = await open(KETEL);

    expect(page.querySelectorAll('[data-field]')).toHaveLength(6);
  });

  it('opens on the task’s own values', async () => {
    const page = await open(KETEL);

    expect(field<HTMLInputElement>(page, 'name').value).toBe('Onderhoud ketels');
    expect(field<HTMLSelectElement>(page, 'context').value).toBe('house');
    expect(field<HTMLSelectElement>(page, 'importance').value).toBe('VERY_IMPORTANT');
    expect(field<HTMLInputElement>(page, 'dueDate').value).toBe('2026-06-30');
    expect(field<HTMLInputElement>(page, 'startDate').value).toBe('2026-08-17');
    expect(field<HTMLTextAreaElement>(page, 'description').value).toBe(
      'Vraag de firma om langs te komen.',
    );
  });

  it('calls the start date “ask me from”, and never “start date”', async () => {
    // Worded in postpone's vocabulary so the two surfaces cannot disagree about what a start date
    // means. `CONTEXT.md` records the term; the column keeps its name and no person ever sees it.
    const page = await open(KETEL);

    expect(page.textContent).toContain('Ask me from');
    expect(page.textContent?.toLowerCase()).not.toContain('start date');
  });

  it('keeps both dates as words, never as glyphs', async () => {
    // ADR-0019's floor 1, narrowed to this screen: a calendar glyph and a clock glyph both read as
    // *a date* and neither says **which** — and in the dialog, unlike on a row, both are live and
    // side by side.
    const page = await open(KETEL);

    const dates = page.querySelector("[data-field='dueDate']")?.closest('.dates');
    expect(dates?.querySelectorAll('app-glyph')).toHaveLength(0);
    expect(dates?.textContent).toContain('Due');
    expect(dates?.textContent).toContain('Ask me from');
  });

  it('keeps the verbs off it — the panel owns those', async () => {
    // Repeating them would give `CANCELLED` — a brand-new verb with exactly two well-understood
    // entry points — a third one, inside a form (ADR-0018).
    const page = await open(KETEL);

    expect(page.textContent).not.toContain('Complete');
    expect(page.querySelectorAll('app-glyph-button')).toHaveLength(0);
  });
});

describe('ask me from', () => {
  function preset(page: HTMLElement, label: string): HTMLButtonElement {
    const found = [...page.querySelectorAll<HTMLButtonElement>('.preset')].find(
      (button) => button.textContent?.trim() === label,
    );
    if (found === undefined) {
      throw new Error(`No preset '${label}'. On screen: ${texts(page, '.preset').join(' | ')}`);
    }
    return found;
  }

  it('offers the four measured presets', async () => {
    const page = await open(aTask({ id: 'a' }));

    expect(texts(page, '.preset')).toEqual(['Today', 'Tomorrow', 'In 3 days', 'Next week']);
  });

  it.each([
    ['Today', '2026-08-14'],
    ['Tomorrow', '2026-08-15'],
    ['In 3 days', '2026-08-17'],
    ['Next week', '2026-08-21'],
  ])('writes %s as %s, measured from today', async (label, expected) => {
    const page = await open(aTask({ id: 'a', startDate: '2026-01-01' }));

    preset(page, label).click();
    await settle();

    expect(field<HTMLInputElement>(page, 'startDate').value).toBe(expected);
  });

  it('pulls a sleeping task back into today — the only un-postpone in the app', async () => {
    // 1,210 of portal's 3,726 start-date pushes set the start date to the day they were made.
    // ADR-0015's postpone moves forward only, so this control is the whole point of the screen.
    const sleeping = aTask({ id: 'a', startDate: '2026-09-30' });
    const page = await open(sleeping);

    preset(page, 'Today').click();
    await settle();
    save(page).click();
    await settle();

    expect(recorded.at(-1)?.changes).toEqual({ startDate: '2026-08-14' });
  });

  it('marks the preset the date is already on', async () => {
    const page = await open(aTask({ id: 'a', startDate: '2026-08-17' }));

    expect(preset(page, 'In 3 days').classList).toContain('on');
    expect(preset(page, 'Today').classList).not.toContain('on');
  });
});

describe('saving', () => {
  it('writes one patch carrying every changed field and closes', async () => {
    const page = await open(aTask({ id: 'a', name: 'Was ophangen', importance: 'IMPORTANT' }));

    type(field(page, 'name'), 'Was ophangen en opvouwen');
    type(field(page, 'importance'), 'VERY_IMPORTANT');
    await settle();
    save(page).click();
    await settle();

    expect(recorded).toHaveLength(1);
    expect(recorded[0].changes).toEqual({
      name: 'Was ophangen en opvouwen',
      importance: 'VERY_IMPORTANT',
    });
    expect(router().url).toBe('/');
  });

  it('writes nothing when nothing was touched', async () => {
    const page = await open(aTask({ id: 'a' }));

    save(page).click();
    await settle();

    expect(recorded).toEqual([]);
    expect(router().url).toBe('/');
  });

  it('clears an emptied description with a null rather than an empty string', async () => {
    // `''` and *no description* are different things to the fold, and only one of them is what an
    // emptied textarea means.
    const page = await open(aTask({ id: 'a', description: 'Vraag de firma.' }));

    type(field(page, 'description'), '');
    await settle();
    save(page).click();
    await settle();

    expect(recorded[0].changes).toEqual({ description: null });
  });
});

describe('leaving without saving', () => {
  it('discards on a deliberate Cancel, and says what went', async () => {
    // A deliberate Cancel is an answer, so it does not ask (ADR-0018).
    const page = await open(aTask({ id: 'a', name: 'Was ophangen' }));

    type(field(page, 'name'), 'Was ophangen en opvouwen');
    await settle();
    cancel(page).click();
    await settle();

    expect(recorded).toEqual([]);
    expect(router().url).toBe('/');
    expect(TestBed.inject(Notices).message()).toBe('Discarded 1 unsaved change(s)');
  });

  it('asks first when the dismissal was accidental', async () => {
    // Splitting by gesture is what lets both answers hold without a confirm in front of every
    // close: the scrim, Escape and hardware back are all things you can hit by accident.
    const page = await open(aTask({ id: 'a', name: 'Was ophangen' }));

    type(field(page, 'name'), 'Was ophangen en opvouwen');
    await settle();
    scrim(page).click();
    await paint();

    expect(router().url).toBe('/task/a');
    expect(page.querySelector('.confirm')?.textContent).toContain('1 unsaved change(s)');
  });

  it('asks on any exit, including the one no component can see', async () => {
    // Hardware back is a `popstate`, which the dialog never hears about — so the confirm has to
    // live on the route rather than on the scrim. ADR-0018 lists all three dismissals together.
    const page = await open(aTask({ id: 'a', name: 'Was ophangen' }));

    type(field(page, 'name'), 'Was ophangen en opvouwen');
    await settle();
    void router().navigateByUrl('/');
    await paint();

    expect(router().url).toBe('/task/a');
    expect(page.querySelector('.confirm')).not.toBeNull();
  });

  it('discards from the confirm, and says what went', async () => {
    const page = await open(aTask({ id: 'a', name: 'Was ophangen' }));

    type(field(page, 'name'), 'Was ophangen en opvouwen');
    await settle();
    scrim(page).click();
    await paint();
    button(page, '.confirm .discard').click();
    await settle();

    expect(router().url).toBe('/');
    expect(recorded).toEqual([]);
    expect(TestBed.inject(Notices).message()).toBe('Discarded 1 unsaved change(s)');
  });

  /**
   * **The two Escape claims #67 settled.**
   *
   * This dialog and `DateConfirm` each bound `(document:keydown.escape)` unconditionally, and this
   * one *navigates away* — so with a confirm open over the screen, one press cancelled the confirm
   * and left the dialog underneath it too. Both now say they are open and the shell hands the key
   * to whichever is on top.
   */
  describe('Escape', () => {
    it('dismisses the dialog, exactly as the scrim does', async () => {
      await open(aTask({ id: 'a' }));

      TestBed.inject(Overlays).escape();
      await settle();

      expect(router().url).toBe('/');
    });

    it('answers the discard confirm without also taking the exit it is asking about', async () => {
      // The defect, in the shape it would really have arrived in: two owners, one press, and the
      // confirm's *keep editing* immediately undone by the dismissal underneath it.
      const page = await open(aTask({ id: 'a', name: 'Was ophangen' }));

      type(field(page, 'name'), 'Was ophangen en opvouwen');
      await settle();
      scrim(page).click();
      await paint();
      expect(page.querySelector('.confirm')).not.toBeNull();

      TestBed.inject(Overlays).escape();
      await paint();

      expect(page.querySelector('.confirm')).toBeNull();
      expect(router().url).toBe('/task/a');
      expect(recorded).toEqual([]);
      // And the typing survived, which is the whole point of having asked.
      expect(field<HTMLInputElement>(page, 'name').value).toBe('Was ophangen en opvouwen');
    });
  });

  it('goes straight out on an accidental dismissal when nothing was typed', async () => {
    const page = await open(aTask({ id: 'a' }));

    scrim(page).click();
    await settle();

    expect(router().url).toBe('/');
  });

  it('keeps editing when the confirm is refused', async () => {
    const page = await open(aTask({ id: 'a', name: 'Was ophangen' }));

    type(field(page, 'name'), 'Was ophangen en opvouwen');
    await settle();
    scrim(page).click();
    await paint();
    button(page, '.confirm button:not(.discard)').click();
    await settle();

    expect(router().url).toBe('/task/a');
    expect(page.querySelector('.confirm')).toBeNull();
    expect(field<HTMLInputElement>(page, 'name').value).toBe('Was ophangen en opvouwen');
  });
});

describe('a task a template made', () => {
  it('is edited freely, and told where it came from', async () => {
    // Locking the fields would contradict ADR-0001 — a generated task *is* a task — and would break
    // the ordinary case of annotating one particular round.
    const generated = { ...aTask({ id: 'a' }), taskTemplateId: 'tpl-1' };
    const page = await open(generated);

    expect(field<HTMLInputElement>(page, 'name').disabled).toBe(false);
    const provenance = page.querySelector('.provenance');
    expect(provenance?.textContent).toContain('changes here apply to this task only');
    expect(provenance?.querySelector('a')?.getAttribute('href')).toBe('/templates/tpl-1');
  });

  it('says nothing about provenance on a task nobody generated', async () => {
    const page = await open(aTask({ id: 'a' }));

    expect(page.querySelector('.provenance')).toBeNull();
  });
});

describe('asking after it is due', () => {
  it('states it in words instead of rejecting it', async () => {
    // 40% of the author's real tasks are in this state by design. The reflex validation here would
    // reject six years of history (ADR-0018).
    const page = await open(aTask({ id: 'a', startDate: '2026-08-17', dueDate: '2026-06-30' }));

    const note = page.querySelector('.note')?.textContent?.replace(/\s+/g, ' ').trim();
    // 48, not 45: the count is measured **from the date you will be asked**, which is ADR-0018's
    // own arithmetic — 30 Jun to 17 Aug is 48 days — and it is the only reading that stays true.
    expect(note).toContain('Asking from 17 Aug, still due 30 Jun — 48 days overdue');
    expect(note).toContain('Not an error');
  });

  it('counts from the day you will be asked, not from today', async () => {
    // Measuring against today makes the sentence contradict itself whenever the due date has not
    // arrived yet: *asking from 25 Aug, still due 20 Aug — in 6 days* says the thing is both late
    // and not yet due. The gap the sentence is about is between the two dates it names.
    const page = await open(aTask({ id: 'a', startDate: '2026-08-25', dueDate: '2026-08-20' }));

    const note = page.querySelector('.note')?.textContent?.replace(/\s+/g, ' ').trim();
    expect(note).toContain('Asking from 25 Aug, still due 20 Aug — 5 days overdue');
  });

  it('says nothing when the task is asked about before it is due', async () => {
    const page = await open(aTask({ id: 'a', startDate: '2026-06-01', dueDate: '2026-06-30' }));

    expect(page.querySelector('.note')).toBeNull();
  });

  it('appears as soon as the form is edited into that state', async () => {
    const page = await open(aTask({ id: 'a', startDate: '2026-06-01', dueDate: '2026-06-30' }));

    type(field(page, 'startDate'), '2026-08-17');
    await settle();

    expect(page.querySelector('.note')?.textContent).toContain('still due 30 Jun');
  });
});
