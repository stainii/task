import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { routes } from '../../app.routes';
import { NOW } from '../../clock';
import { Task } from '../../domain/task';
import { aTask } from '../../domain/task.mother';
import { TaskTemplate } from '../../domain/template';
import { aDefinition, aTemplate, manual, minMax } from '../../domain/template.mother';
import { LocalStore } from '../../store/local-store';
import { TemplateService } from '../../sync/templates';
import { TemplateAuthoring } from './template-authoring';

const NOW_AT = new Date('2026-08-14T10:00:00Z');

/**
 * **One screen, and the trigger is the first field on it** (ADR-0013).
 *
 * Three separate forms behind a chooser were rejected because they would have re-forbidden the one
 * combination this screen exists to unlock — a template with several tasks *and* a repeating
 * trigger — and both halves of that combination are already in production as workarounds.
 *
 * Tested through what it renders and what it sends. The one boundary is `TemplateService`: templates
 * are online-write-only, so there is no outbox to fold anything through.
 */

let heldTemplates: TaskTemplate[] = [];
let heldTasks: Task[] = [];
let saved: { template: TaskTemplate; existing: boolean } | null = null;
const writable = signal(true);

const templates = {
  writable,
  refresh: vi.fn(() => Promise.resolve()),
  save: vi.fn((template: TaskTemplate, options: { existing: boolean }) => {
    saved = { template, existing: options.existing };
    return Promise.resolve();
  }),
  deactivate: vi.fn(() => Promise.resolve()),
  reactivate: vi.fn(() => Promise.resolve()),
  remove: vi.fn(() => Promise.resolve()),
  run: vi.fn(() => Promise.resolve()),
};

let fixture: ComponentFixture<TemplateAuthoring>;

function element(): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function texts(selector: string): string[] {
  return [...element().querySelectorAll(selector)].map((node) => node.textContent?.trim() ?? '');
}

function field(name: string): HTMLInputElement | HTMLSelectElement {
  const found = element().querySelector<HTMLInputElement>(`[data-field='${name}']`);
  if (found === null) {
    throw new Error(
      `No field '${name}'. On screen: ${[...element().querySelectorAll('[data-field]')]
        .map((node) => node.getAttribute('data-field'))
        .join(', ')}`,
    );
  }
  return found;
}

async function fill(name: string, value: string): Promise<void> {
  const control = field(name);
  control.value = value;
  control.dispatchEvent(new Event('input'));
  control.dispatchEvent(new Event('change'));
  await fixture.whenStable();
}

async function click(selector: string): Promise<void> {
  const button = element().querySelector<HTMLElement>(selector);
  if (button === null) {
    throw new Error(`No '${selector}' on screen.`);
  }
  button.click();
  await fixture.whenStable();
}

async function open(id: string): Promise<void> {
  fixture = TestBed.createComponent(TemplateAuthoring);
  fixture.componentRef.setInput('id', id);
  await fixture.whenStable();
}

beforeEach(() => {
  heldTemplates = [];
  heldTasks = [];
  saved = null;
  writable.set(true);
  vi.clearAllMocks();
  TestBed.configureTestingModule({
    providers: [
      // The **real** routes, not an empty list: saving navigates back to `/templates`, and against
      // `provideRouter([])` that rejects with NG04002 *after* the assertion has passed — an
      // unhandled rejection, which is #56's trap exactly. Vitest exits 1 on one while every test
      // reads green, so a spec that navigates has to be able to arrive.
      provideRouter(routes),
      { provide: NOW, useValue: () => NOW_AT },
      {
        provide: LocalStore,
        useValue: {
          templates: () => Promise.resolve([...heldTemplates]),
          tasks: () => Promise.resolve([...heldTasks]),
        },
      },
      { provide: TemplateService, useValue: templates },
    ],
  });
});

describe('a new template', () => {
  it('starts as one you run by hand, with one task on it', async () => {
    await open('new');

    expect((field('kind') as HTMLSelectElement).value).toBe('MANUAL');
    expect(element().querySelectorAll('.definition')).toHaveLength(1);
  });

  /**
   * **The trigger shapes the form beneath it.** That is the decision, not a layout preference: one
   * screen whose second half swaps, rather than three forms that would each have nowhere to put the
   * thing the other two need.
   */
  it('swaps the fields under the trigger when the trigger changes', async () => {
    await open('new');

    expect(element().querySelector("[data-field='anchorLabel']")).not.toBeNull();
    expect(element().querySelector("[data-field='interval']")).toBeNull();

    await fill('kind', 'MIN_MAX');

    expect(element().querySelector("[data-field='anchorLabel']")).toBeNull();
    expect(element().querySelector("[data-field='interval']")).not.toBeNull();
    expect(element().querySelector("[data-field='window']")).not.toBeNull();
  });

  it('sends the interval and the window as a min and a max', async () => {
    await open('new');

    await fill('name', 'Beddengoed wassen');
    await fill('context', 'house');
    await fill('kind', 'MIN_MAX');
    await fill('interval', '10');
    await fill('window', '3');
    await fill('definition-name-0', 'Beddengoed wassen');
    await click('.save');

    expect(saved?.existing).toBe(false);
    expect(saved?.template.trigger).toMatchObject({ type: 'MIN_MAX', minDays: 10, maxDays: 13 });
  });
});

/**
 * **The screen and the draft must agree**, and for a `<select>` that is not free.
 *
 * Found by driving the real app: a new template's importance read *"I don't really care"* while the
 * draft said `IMPORTANT`, because `[value]` on a select is applied before its options exist and is
 * silently dropped. Every select on this screen had it, and every test was green — each one happened
 * to assert a value that is also the **first option**, which is what a dropped binding falls back
 * to. So these two assert a value that is deliberately *not* first.
 */
describe('what the dropdowns actually show', () => {
  it('shows the importance the task really has', async () => {
    heldTemplates = [
      aTemplate({
        id: 'bins',
        taskDefinitions: [aDefinition({ name: 'Vuilbakken', importance: 'VERY_IMPORTANT' })],
      }),
    ];

    await open('bins');

    expect(field('definition-importance-0').value).toBe('VERY_IMPORTANT');
  });

  it('shows the trigger the template really has', async () => {
    heldTemplates = [aTemplate({ id: 'bins', trigger: minMax(10, 3) })];

    await open('bins');

    expect(field('kind').value).toBe('MIN_MAX');
  });
});

describe('what the form will not send', () => {
  it('says what is missing rather than posting it and showing a 400', async () => {
    await open('new');

    await click('.save');

    expect(texts('.problem')).toContain('Give the template a name.');
    expect(templates.save).not.toHaveBeenCalled();
  });

  /** `${…}` is manual-only: nothing is present to answer it when a template fires at 04:00. */
  it('refuses a variable on a template that fires by itself', async () => {
    await open('new');

    await fill('name', 'Iets');
    await fill('context', 'house');
    await fill('definition-name-0', 'Mail naar ${school}');
    await fill('kind', 'MIN_MAX');
    await click('.save');

    expect(texts('.problem').join(' ')).toContain('${school}');
    expect(templates.save).not.toHaveBeenCalled();
  });
});

describe('variables', () => {
  /**
   * **Inferred from the text, with no declared list** (ADR-0013). A typo becomes a fifth chip in
   * the form as it is typed, rather than a task named `${scool}` months later.
   */
  it('appear as chips as they are typed', async () => {
    await open('new');

    await fill('definition-name-0', 'Mail naar ${school} over ${onderwerp}');

    expect(texts('.variable')).toEqual(['${school}', '${onderwerp}']);
  });
});

describe('an existing template', () => {
  it('opens with its own fields in it', async () => {
    heldTemplates = [
      aTemplate({
        id: 'workshop',
        name: 'Opvolgen workshop',
        context: 'work',
        trigger: manual('When is the workshop?'),
        taskDefinitions: [
          aDefinition({
            name: 'Voorbereidingsmail',
            startDateOffsetDays: -14,
            dueDateOffsetDays: -7,
          }),
        ],
      }),
    ];

    await open('workshop');

    expect(field('name').value).toBe('Opvolgen workshop');
    expect(field('context').value).toBe('work');
    expect(field('anchorLabel').value).toBe('When is the workshop?');
    expect(field('definition-name-0').value).toBe('Voorbereidingsmail');
  });

  it('updates rather than creating', async () => {
    heldTemplates = [aTemplate({ id: 'workshop', name: 'Opvolgen workshop', context: 'work' })];

    await open('workshop');
    await fill('name', 'Opvolgen workshop 2026');
    await click('.save');

    expect(saved?.existing).toBe(true);
    expect(saved?.template.id).toBe('workshop');
  });
});

describe('retiring a template', () => {
  /**
   * **Deactivated, not deleted**, once it has tasks. #35 measured what deleting cost portal: 49% of
   * recurring tasks point at a template that no longer exists, and `taskTemplateId` is load-bearing
   * now — the min/max anchor reads it.
   */
  it('offers deactivation, not deletion, once it has fired', async () => {
    heldTemplates = [aTemplate({ id: 'bins' })];
    heldTasks = [aTask({ taskTemplateId: 'bins', status: 'COMPLETED' })];

    await open('bins');

    expect(element().querySelector('.delete')).toBeNull();

    await click('.deactivate');
    expect(templates.deactivate).toHaveBeenCalledWith('bins');
  });

  /** Deleting survives for the genuine *I just made this and got it wrong* case. It is a count. */
  it('offers deletion while it has no tasks at all', async () => {
    heldTemplates = [aTemplate({ id: 'bins' })];

    await open('bins');

    await click('.delete');
    expect(templates.remove).toHaveBeenCalledWith('bins');
  });

  it('offers to switch a deactivated one back on', async () => {
    heldTemplates = [aTemplate({ id: 'bins', active: false })];

    await open('bins');

    await click('.reactivate');
    expect(templates.reactivate).toHaveBeenCalledWith('bins');
  });
});

describe('running a manual template', () => {
  /**
   * **The anchor is asked by name.** *"When is the workshop?"* rather than a date picker is the
   * whole reason the label lives on the trigger (ADR-0013).
   */
  it('asks the question the author wrote, and then creates the tasks', async () => {
    heldTemplates = [
      aTemplate({
        id: 'workshop',
        trigger: manual('When is the workshop?'),
        taskDefinitions: [aDefinition({ name: 'Voorbereidingsmail', startDateOffsetDays: -14 })],
      }),
    ];

    await open('workshop');
    await click('.run');

    expect(texts('.run-form label')).toContain('When is the workshop?');

    await fill('anchorDate', '2026-09-15');
    await click('.run-confirm');

    expect(templates.run).toHaveBeenCalledWith('workshop', {
      variables: {},
      anchorDate: '2026-09-15',
    });
  });

  /**
   * **The preview is the check that replaces an edit-before-create step** (ADR-0013). Running
   * creates ordinary tasks immediately, and they are editable one screen later.
   */
  it('shows the dates it is about to create, once the anchor has been typed', async () => {
    heldTemplates = [
      aTemplate({
        id: 'workshop',
        trigger: manual('When is the workshop?'),
        taskDefinitions: [aDefinition({ name: 'Voorbereidingsmail', startDateOffsetDays: -14 })],
      }),
    ];

    await open('workshop');
    await click('.run');
    await fill('anchorDate', '2026-09-15');

    expect(texts('.run-form .preview-row').join(' ')).toContain('2026-09-01');
  });

  it('is not offered for a template that fires by itself', async () => {
    heldTemplates = [aTemplate({ id: 'bins', trigger: minMax(10, 0) })];

    await open('bins');

    expect(element().querySelector('.run')).toBeNull();
  });
});

describe('when the device is offline', () => {
  /** ADR-0004: **visibly** unavailable, never a save that silently goes nowhere. */
  it('says so and offers nothing to press', async () => {
    heldTemplates = [aTemplate({ id: 'bins' })];
    writable.set(false);

    await open('bins');

    expect(texts('.offline')).toEqual([
      'Templates can only be changed online. What is here is what this device last saw.',
    ]);
    expect(element().querySelector('.save')).toBeNull();
    expect(element().querySelector('.deactivate')).toBeNull();
    expect(element().querySelector('.run')).toBeNull();
  });
});
