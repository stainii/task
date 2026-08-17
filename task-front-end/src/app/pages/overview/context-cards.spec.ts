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
  });

  it('shows nothing at all when this device holds no open work', async () => {
    await render([]);

    expect((fixture.nativeElement as HTMLElement).querySelector('nav')).toBeNull();
  });

  it('badges what is overdue, and counts the whole context beside it', async () => {
    await render([
      aTask({ context: 'house', dueDate: addDays(TODAY, -2) }),
      aTask({ context: 'house', dueDate: addDays(TODAY, 20) }),
    ]);

    expect(text('.badge')).toEqual(['1 overdue']);
    expect(text('.count')).toEqual(['2']);
  });

  it('badges what is due today when nothing is late', async () => {
    await render([aTask({ context: 'house', dueDate: TODAY })]);

    expect(text('.badge')).toEqual(['1 today']);
  });

  it('says nothing where there is no urgency to claim', async () => {
    await render([aTask({ context: 'house', dueDate: addDays(TODAY, 20) })]);

    expect(cards()[0].querySelector('.badge')).toBeNull();
  });

  it('names what comes next after the visible work', async () => {
    await render([
      ...Array.from({ length: 5 }, (_, index) =>
        aTask({ context: 'house', name: `shown ${index}`, dueDate: addDays(TODAY, index) }),
      ),
      aTask({ context: 'house', name: 'Renew bike insurance', dueDate: addDays(TODAY, 9) }),
    ]);

    expect(text('.next')).toEqual(['next: Renew bike insurance · in 9 days']);
  });

  it('says so plainly when the context is all on screen already', async () => {
    // Cards deliberately do not list their next few tasks — an earlier draft did and duplicated
    // almost the whole visible band. Showing what comes *after* it makes them additive, and this is
    // that line with nothing left to name.
    await render([aTask({ context: 'house', dueDate: TODAY })]);

    expect(text('.next')).toEqual(['nothing pending']);
  });

  it('draws the importance buckets as the colour bar, and keeps it out of the reader', async () => {
    await render([
      aTask({ context: 'house', dueDate: addDays(TODAY, 1), importance: 'VERY_IMPORTANT' }),
      aTask({ context: 'house', dueDate: addDays(TODAY, 40), importance: 'NOT_SO_IMPORTANT' }),
    ]);

    const bar = cards()[0].querySelector('.bar');
    expect([...(bar?.children ?? [])].map((segment) => segment.className)).toEqual([
      'bucket-focus',
      'bucket-back-burner',
    ]);
    // Shape-at-a-glance is a picture of facts that are said in words elsewhere; a screen reader
    // spelling out six colours would be noise, not access.
    expect(bar?.getAttribute('aria-hidden')).toBe('true');
  });
});
