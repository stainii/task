import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, beforeEach } from 'vitest';

import { NOW } from '../../clock';
import { addDays } from '../../domain/dates';
import { PanelAction } from '../../domain/patches';
import { Task } from '../../domain/task';
import { aTask } from '../../domain/task.mother';
import { Confirms } from '../../ui/confirms';
import { TaskPanel } from './task-panel';

/**
 * The panel is where the two verbs portal never had become reachable, so most of what follows is
 * about the gesture: a swipe is the one affordance here that can fire by accident.
 */

const TODAY = '2026-08-14';
const NOW_AT = new Date('2026-08-14T10:00:00Z');

@Component({
  imports: [TaskPanel],
  template: `<app-task-panel
    [task]="task()"
    [today]="today"
    [showContext]="showContext()"
    [lastCompletedOn]="lastCompletedOn()"
    [manualTemplate]="manualTemplate()"
    (acted)="acted = $event"
  />`,
})
class Host {
  readonly task = signal<Task>(aTask());
  readonly today = TODAY;
  readonly showContext = signal(true);
  readonly lastCompletedOn = signal<string | null>(null);
  readonly manualTemplate = signal(false);
  acted: PanelAction | null = null;
}

let fixture: ComponentFixture<Host>;

function element(): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function text(selector: string): string {
  return element().querySelector(selector)?.textContent?.trim() ?? '';
}

async function show(task: Task): Promise<void> {
  fixture.componentInstance.task.set(task);
  await fixture.whenStable();
}

async function expand(): Promise<void> {
  element().querySelector<HTMLElement>('.row')?.click();
  await fixture.whenStable();
}

function verb(name: string): HTMLElement {
  const button = element().querySelector<HTMLElement>(`button[aria-label="${name}"]`);
  if (button === null) {
    throw new Error(`No control named '${name}' is on screen.`);
  }
  return button;
}

/** What the stubbed *when did you do it?* confirm answers with on its next call. */
let askAnswer: string | null = '2026-08-09';
const asks: string[] = [];

/** A pointer gesture. jsdom has no `PointerEvent`, and a `MouseEvent` carries the same `clientX`. */
async function swipe(by: number): Promise<void> {
  const panel = element().querySelector('.panel')!;
  panel.dispatchEvent(new MouseEvent('pointerdown', { clientX: 0, bubbles: true }));
  panel.dispatchEvent(new MouseEvent('pointermove', { clientX: by, bubbles: true }));
  await fixture.whenStable();
  panel.dispatchEvent(new MouseEvent('pointerup', { clientX: by, bubbles: true }));
  await fixture.whenStable();
}

beforeEach(async () => {
  askAnswer = '2026-08-09';
  asks.length = 0;
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: NOW, useValue: () => NOW_AT },
      {
        provide: Confirms,
        useValue: {
          ask: (what: string) => {
            asks.push(what);
            return Promise.resolve(askAnswer);
          },
        },
      },
    ],
  });
  fixture = TestBed.createComponent(Host);
  await fixture.whenStable();
});

describe('the collapsed row', () => {
  it('says the name, the context and when the task is due', async () => {
    await show(aTask({ name: 'Descale the coffee machine', context: 'house', dueDate: TODAY }));

    expect(text('.name')).toBe('Descale the coffee machine');
    expect(text('.context')).toBe('house');
    expect(text('.due')).toBe('today');
  });

  it.each([
    [-62, '62 days overdue'],
    [-1, '1 day overdue'],
    [0, 'today'],
    [1, 'tomorrow'],
    [9, 'in 9 days'],
  ])('writes a due date %i days out in words', async (days, expected) => {
    await show(aTask({ dueDate: addDays(TODAY, days) }));

    expect(text('.due')).toBe(expected);
  });

  it('says so in words when there is no due date at all', async () => {
    // Facts are words (ADR-0019), and *this task has no date* is a fact — the omnibox mints one
    // like it on every Enter. A blank here reads as a rendering failure rather than as an answer.
    await show(aTask({ dueDate: null }));

    expect(text('.due')).toBe('no due date');
  });

  it('carries the importance bucket for the stripe', async () => {
    await show(aTask({ importance: 'IMPORTANT', dueDate: addDays(TODAY, 2) }));

    expect(element().querySelector('.panel')?.getAttribute('data-bucket')).toBe('focus');
  });

  it('marks the rows that have something behind the caret', async () => {
    await show(aTask({ description: 'Ring the plumber first.' }));

    expect(text('.notes')).toBe('notes');
  });

  it.each([[null], [''], ['   ']])(
    'stays silent about notes when the description is %o',
    async (description) => {
      await show(aTask({ description }));

      expect(element().querySelector('.notes')).toBeNull();
    },
  );

  it('drops the context chip where every row would carry the same one', async () => {
    await show(aTask({ name: 'Descale the coffee machine', context: 'house' }));
    fixture.componentInstance.showContext.set(false);
    await fixture.whenStable();

    expect(element().querySelector('.context')).toBeNull();
    expect(text('.name')).toBe('Descale the coffee machine');
  });

  it('keeps the verbs behind the expansion', async () => {
    await show(aTask());

    expect(element().querySelector('button[aria-label="Complete"]')).toBeNull();
  });
});

describe('the expanded panel', () => {
  it('reveals the four verbs and the description', async () => {
    await show(aTask({ description: 'Including under the couch.' }));
    await expand();

    expect(text('.description')).toBe('Including under the couch.');
    for (const name of ['Edit', 'Postpone', 'Complete', 'Cancel']) {
      expect(verb(name)).toBeTruthy();
    }
  });

  it('separates cancel from the constructive verbs', async () => {
    // Colour already tells them apart, but colour says *category*, never *which* — the same way
    // #42's calendar and clock glyphs both read as "a date". So the separation is positional.
    await show(aTask());
    await expand();

    expect(element().querySelector('.verbs .destructive button[aria-label="Cancel"]')).toBeTruthy();
    expect(element().querySelector('.verbs .constructive button[aria-label="Cancel"]')).toBeNull();
  });

  it('completes the task', async () => {
    await show(aTask({ id: 'a' }));
    await expand();
    verb('Complete').click();
    await fixture.whenStable();

    expect(fixture.componentInstance.acted?.patch.taskId).toBe('a');
    expect(fixture.componentInstance.acted?.patch.changes).toEqual({
      status: 'COMPLETED',
      completedOn: TODAY,
    });
    // The completed task rides along, so the toast's *change day* row can mint the recomplete a
    // correction is (ADR-0018). Cancel and postpone do not carry it.
    expect(fixture.componentInstance.acted?.completed?.task.id).toBe('a');
  });

  it('cancels the task, which portal could never do at all', async () => {
    await show(aTask());
    await expand();
    verb('Cancel').click();
    await fixture.whenStable();

    expect(fixture.componentInstance.acted?.patch.changes).toEqual({ status: 'CANCELLED' });
    expect(fixture.componentInstance.acted?.completed).toBeUndefined();
  });

  it('postpones by a preset, pushing only the start date', async () => {
    await show(aTask({ dueDate: '2026-06-01' }));
    await expand();
    verb('Postpone').click();
    await fixture.whenStable();

    const presets = document.querySelectorAll<HTMLElement>('.postpone-preset');
    expect([...presets].map((preset) => preset.textContent?.trim())).toEqual([
      'Tomorrow',
      'In 3 days',
      'Next week',
    ]);

    presets[1].click();
    await fixture.whenStable();

    expect(fixture.componentInstance.acted?.patch.changes).toEqual({ startDate: '2026-08-17' });
  });
});

describe('completing on another day (issue #83)', () => {
  function doneWhen(): HTMLElement {
    return verb('Complete on another day');
  }

  async function openDoneWhen(): Promise<void> {
    await expand();
    doneWhen().click();
    await fixture.whenStable();
  }

  it('keeps the plain Complete tap silent and dated today', async () => {
    // ADR-0014's line: acted on in place does not ask. The split only adds a *deliberate* surface;
    // the tap that produced it is untouched.
    await show(aTask({ id: 'a' }));
    await expand();
    verb('Complete').click();
    await fixture.whenStable();

    expect(asks).toEqual([]);
    expect(fixture.componentInstance.acted?.patch.changes).toEqual({
      status: 'COMPLETED',
      completedOn: TODAY,
    });
  });

  it('offers today and the two days before it, then the shared confirm', async () => {
    await show(aTask());
    await openDoneWhen();

    const options = [...document.querySelectorAll<HTMLElement>('.done-when-preset')].map((option) =>
      option.textContent?.trim(),
    );
    expect(options).toEqual(['Today', 'Yesterday', '2 days ago']);
    expect(document.querySelector('.done-when-past')?.textContent?.trim()).toBe('In the past…');
  });

  it('backdates the completion by a preset', async () => {
    await show(aTask({ id: 'a' }));
    await openDoneWhen();

    document.querySelectorAll<HTMLElement>('.done-when-preset')[1].click();
    await fixture.whenStable();

    expect(asks).toEqual([]);
    expect(fixture.componentInstance.acted?.patch.changes).toEqual({
      status: 'COMPLETED',
      completedOn: addDays(TODAY, -1),
    });
    expect(fixture.componentInstance.acted?.completed?.task.id).toBe('a');
  });

  it('routes "In the past…" through the shared "When did you do it?" confirm', async () => {
    askAnswer = '2026-08-09';
    await show(aTask({ name: 'Descale the coffee machine' }));
    await openDoneWhen();

    document.querySelector<HTMLElement>('.done-when-past')!.click();
    await fixture.whenStable();

    expect(asks).toEqual(['Descale the coffee machine']);
    expect(fixture.componentInstance.acted?.patch.changes).toEqual({
      status: 'COMPLETED',
      completedOn: '2026-08-09',
    });
  });

  it('writes nothing when the confirm is dismissed', async () => {
    askAnswer = null;
    await show(aTask());
    await openDoneWhen();

    document.querySelector<HTMLElement>('.done-when-past')!.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.acted).toBeNull();
  });
});

describe('the swipe', () => {
  it('completes on a swipe to the right', async () => {
    await show(aTask());
    await swipe(200);

    expect(fixture.componentInstance.acted?.patch.changes).toEqual({
      status: 'COMPLETED',
      completedOn: TODAY,
    });
  });

  it('cancels on a swipe to the left', async () => {
    await show(aTask());
    await swipe(-200);

    expect(fixture.componentInstance.acted?.patch.changes).toEqual({ status: 'CANCELLED' });
  });

  it('says which verb the gesture is about to do, on the fill', async () => {
    // An unlabelled two-way swipe on a destructive-ish action is a coin flip. A gesture has no
    // glyph, so this is one of the places ADR-0019's rule leaves words in place.
    await show(aTask());
    const panel = element().querySelector('.panel')!;
    panel.dispatchEvent(new MouseEvent('pointerdown', { clientX: 0, bubbles: true }));
    panel.dispatchEvent(new MouseEvent('pointermove', { clientX: 60, bubbles: true }));
    await fixture.whenStable();

    expect(text('.fill.right')).toContain('Complete');
    expect(element().querySelector<HTMLElement>('.fill.right')?.style.width).not.toBe('0px');
  });

  it('does nothing at all when the gesture falls short', async () => {
    await show(aTask());
    await swipe(20);

    expect(fixture.componentInstance.acted).toBeNull();
  });

  it('does not expand the panel on the way back from a swipe', async () => {
    // A drag that ends over the row is not a click on it. Without this a short swipe both fails to
    // act and opens the panel, which reads as the gesture having done something else.
    await show(aTask());
    await swipe(40);
    await expand();

    expect(element().querySelector('button[aria-label="Complete"]')).toBeNull();
  });
});

describe('the description', () => {
  it('turns a URL into a link, and nothing else into anything', async () => {
    // ADR-0015: descriptions linkify URLs, **and nothing else**. Markdown invites a formatting
    // toolbar, which invites an editor.
    await show(aTask({ description: 'Bestel bij https://example.com/parts — code *AB12*' }));
    await expand();

    const panel = element();
    const link = panel.querySelector<HTMLAnchorElement>('.description a');
    expect(link?.getAttribute('href')).toBe('https://example.com/parts');
    expect(link?.textContent).toBe('https://example.com/parts');
    expect(panel.querySelector('.description')?.textContent).toContain('code *AB12*');
  });

  it('opens a link away from the app, without handing the new page a handle on it', async () => {
    // `noopener` is not decoration here: the app is a PWA holding a live session, and a page opened
    // from it could otherwise reach back through `window.opener`.
    await show(aTask({ description: 'https://example.com/parts' }));
    await expand();

    const panel = element();
    const link = panel.querySelector<HTMLAnchorElement>('.description a');
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('still says so out loud when there is no description', async () => {
    await show(aTask({ description: null }));
    await expand();

    expect(text('.description')).toBe('No description.');
  });

  it('says the same for a description that is only whitespace', async () => {
    // The row and the body are two views of one fact. This is the input where they can disagree:
    // a blank paragraph here, under a row that has already said there are no notes, would be the
    // panel answering *is there anything in this* twice, differently, one click apart.
    await show(aTask({ description: '   ' }));

    expect(element().querySelector('.notes')).toBeNull();

    await expand();
    expect(text('.description')).toBe('No description.');
  });
});

describe('the ↻ last-done line', () => {
  it('shows for a task its template made', async () => {
    fixture.componentInstance.lastCompletedOn.set('2026-08-02');
    await show(aTask({ taskTemplateId: 'boiler' }));
    await expand();

    expect(text('.facts')).toBe('↻ last 12 days ago · 2 Aug');
  });

  /**
   * #90: a `MANUAL` template has no cadence and no "last done" clock — a task it generated would
   * otherwise read `↻ never done`. The overview tells the panel the template is manual and the
   * line is dropped, the same way the templates list drops it.
   */
  it('is dropped for a task from a manual template', async () => {
    fixture.componentInstance.manualTemplate.set(true);
    await show(aTask({ taskTemplateId: 'workshop' }));
    await expand();

    expect(element().querySelector('.facts')).toBeNull();
  });
});
