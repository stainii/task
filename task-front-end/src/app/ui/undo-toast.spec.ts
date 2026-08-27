import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { CompletionCorrection } from './toasts';
import { UndoToast } from './undo-toast';

const TODAY = '2026-08-14';

/**
 * The toast behind a completion, and — for a completion the overview panel made — issue #83's
 * *change day* row: the one place a silent swipe's `completedOn` can be corrected before the horizon
 * closes on it (ADR-0011). The omnibox and the templates list already asked *when*, so they pass no
 * {@link CompletionCorrection} and the row does not render.
 */
describe('UndoToast', () => {
  let fixture: ComponentFixture<UndoToast>;
  let changed: string[];
  let picked: number;

  function render(what: string, on?: string): HTMLElement {
    changed = [];
    picked = 0;
    fixture = TestBed.createComponent(UndoToast);
    fixture.componentRef.setInput('what', what);
    if (on !== undefined) {
      const correction: CompletionCorrection = {
        on,
        today: TODAY,
        changeDay: (to) => changed.push(to),
        pickDay: () => (picked += 1),
      };
      fixture.componentRef.setInput('correction', correction);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function button(page: HTMLElement, label: string): HTMLElement {
    const found = [...page.querySelectorAll<HTMLElement>('button')].find(
      (node) => node.textContent?.trim() === label,
    );
    if (found === undefined) {
      throw new Error(`No button labelled '${label}'.`);
    }
    return found;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({});
  });

  it('is just the sentence and Undo when the completion was made by name', () => {
    const page = render('Completed — Beddengoed wassen');

    expect(page.textContent).toContain('Completed — Beddengoed wassen');
    expect(page.querySelector('.undo')).not.toBeNull();
    expect(page.querySelector('.when')).toBeNull();
  });

  it('says which day a panel completion was filed under', () => {
    expect(render('x', TODAY).querySelector('.when')?.textContent).toContain('done today');
    expect(render('x', '2026-08-13').querySelector('.when')?.textContent).toContain(
      'done yesterday',
    );
    expect(render('x', '2026-08-09').querySelector('.when')?.textContent).toContain('done 9 Aug');
  });

  it('opens the correction options on the toggle, and moves the day to a preset', () => {
    const page = render('x', TODAY);

    button(page, 'change day ▾').click();
    fixture.detectChanges();

    button(page, 'Yesterday').click();
    button(page, '2 days ago').click();

    expect(changed).toEqual(['2026-08-13', '2026-08-12']);
  });

  it('hands "In the past…" to the correction for the shared confirm', () => {
    const page = render('x', TODAY);

    button(page, 'change day ▾').click();
    fixture.detectChanges();
    button(page, 'In the past…').click();

    expect(picked).toBe(1);
    expect(changed).toEqual([]);
  });

  it('snaps the options shut when the slot is reused for the next completion', () => {
    const page = render('Completed — one', TODAY);
    button(page, 'change day ▾').click();
    fixture.detectChanges();
    expect(page.querySelector('.change-day')).not.toBeNull();

    fixture.componentRef.setInput('what', 'Completed — two');
    fixture.detectChanges();

    expect(page.querySelector('.change-day')).toBeNull();
    expect(page.textContent).toContain('change day ▾');
  });
});
