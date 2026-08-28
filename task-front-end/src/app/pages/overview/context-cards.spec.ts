import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { addDays } from '../../domain/dates';
import { aTask } from '../../domain/task.mother';
import { Task } from '../../domain/task';
import { ContextCards } from './context-cards';

const TODAY = '2026-08-14';

let fixture: ComponentFixture<ContextCards>;

async function render(tasks: Task[], entered?: string): Promise<void> {
  fixture = TestBed.createComponent(ContextCards);
  fixture.componentRef.setInput('tasks', tasks);
  fixture.componentRef.setInput('today', TODAY);
  if (entered !== undefined) {
    fixture.componentRef.setInput('entered', entered);
  }
  await fixture.whenStable();
}

function cards(): HTMLElement[] {
  return [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.card')];
}

function text(selector: string): string[] {
  return cards().map((card) => card.querySelector(selector)?.textContent?.trim() ?? '');
}

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
});

describe('the context cards', () => {
  it('is one card per context, and clicking one enters it', async () => {
    // Filtering *is* entering the app (ADR-0006): the four-apps feeling without four routes, four
    // modules or four deployments. So it is a link, and the entered context is in the URL — which is
    // what makes a push notification, a shared link and a restored session the same mechanism.
    await render([aTask({ context: 'house' }), aTask({ context: 'health' })]);

    expect(text('.context')).toEqual(['health', 'house']);
    expect(cards().map((card) => card.getAttribute('href'))).toEqual(['/in/health', '/in/house']);
  });

  it('marks the one you are standing in', async () => {
    await render([aTask({ context: 'house' }), aTask({ context: 'health' })], 'house');

    expect(cards().map((card) => card.classList.contains('entered'))).toEqual([false, true]);
    expect(cards().map((card) => card.getAttribute('aria-current'))).toEqual([null, 'true']);
  });

  it('shows nothing at all when this device holds no open work', async () => {
    await render([]);

    expect((fixture.nativeElement as HTMLElement).querySelector('nav')).toBeNull();
  });

  it('badges what is overdue in words, and counts the whole context beside it', async () => {
    // ADR-0019: a count and a state indicator are facts, so the card says `2 overdue`, not `⚠2`.
    await render([
      aTask({ context: 'house', dueDate: addDays(TODAY, -2) }),
      aTask({ context: 'house', dueDate: addDays(TODAY, -5) }),
      aTask({ context: 'house', dueDate: addDays(TODAY, 20) }),
    ]);

    expect(text('.badge')).toEqual(['2 overdue']);
    expect(text('.count')).toEqual(['3']);
  });

  it('badges what is due today when nothing is late', async () => {
    await render([aTask({ context: 'house', dueDate: TODAY })]);

    expect(text('.badge')).toEqual(['1 today']);
  });

  it('says nothing where there is no urgency to claim', async () => {
    await render([aTask({ context: 'house', dueDate: addDays(TODAY, 20) })]);

    expect(cards()[0].querySelector('.badge')).toBeNull();
  });

  it('colours the dot by the soonest task’s importance bucket, and keeps it out of the reader', async () => {
    // All that is left of the six-segment bar (#82). The soonest task is very important and near, so
    // the context's dot is `focus`.
    await render([
      aTask({ context: 'house', dueDate: addDays(TODAY, 1), importance: 'VERY_IMPORTANT' }),
      aTask({ context: 'house', dueDate: addDays(TODAY, 40), importance: 'NOT_SO_IMPORTANT' }),
    ]);

    const dot = cards()[0].querySelector('.dot');
    expect(dot?.className).toContain('bucket-focus');
    // A colour that only backs up facts said in words elsewhere; a screen reader naming it is noise.
    expect(dot?.getAttribute('aria-hidden')).toBe('true');
  });

  it('draws neither the six-segment bar nor a what-comes-next line — #82 dropped both', async () => {
    await render([
      aTask({ context: 'house', name: 'Ramen lappen', dueDate: TODAY }),
      aTask({ context: 'house', name: 'Onderhoud ketels', dueDate: addDays(TODAY, 9) }),
    ]);

    expect(cards()[0].querySelector('.bar')).toBeNull();
    expect(cards()[0].querySelector('.next')).toBeNull();
    expect(cards()[0].textContent).not.toContain('next:');
  });
});
