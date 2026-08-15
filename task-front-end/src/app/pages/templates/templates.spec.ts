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

async function render(templates: TaskTemplate[], tasks: Task[] = []): Promise<void> {
  heldTemplates = templates;
  heldTasks = tasks;
  fixture = TestBed.createComponent(Templates);
  await fixture.whenStable();
}

async function confirmOn(date: string): Promise<void> {
  const field = element().querySelector<HTMLInputElement>('app-date-confirm input[type=date]');
  if (field === null) {
    throw new Error('The date confirm is not open.');
  }
  field.value = date;
  field.dispatchEvent(new Event('input'));
  await fixture.whenStable();
  click('app-date-confirm .confirm');
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

    expect(element().querySelector('app-date-confirm')).not.toBeNull();
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
    expect(element().querySelector('app-date-confirm')).toBeNull();

    element().querySelectorAll<HTMLButtonElement>('.which .definition')[1].click();
    await fixture.whenStable();
    await confirmOn('2026-08-11');

    expect(foldOf(recorded[0].taskId, recorded).name).toBe('Bed stofzuigen');
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

    click('app-undo-toast .undo');
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
