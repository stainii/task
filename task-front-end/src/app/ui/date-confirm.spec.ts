import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { DateConfirm } from './date-confirm';

/**
 * The shared *when did you do it?* confirm (ADR-0014).
 *
 * It is one component rather than one per capture path on purpose: the omnibox and #61's templates
 * list differ only in **how the thing was chosen**, and converge here before anything is written.
 * Two confirms would be two chances to write a different `completedOn` for the same gesture — and
 * since #67 there is one *instance* too, painted by the shell.
 *
 * **Escape is not here.** It is a cancellation, but the confirm no longer owns the key: it is the
 * topmost overlay while it is up, and `overlays.spec.ts` is where that is asserted.
 */
describe('DateConfirm', () => {
  let fixture: ComponentFixture<DateConfirm>;

  async function open(what: string, today = '2026-08-14'): Promise<HTMLElement> {
    fixture = TestBed.createComponent(DateConfirm);
    fixture.componentRef.setInput('what', what);
    fixture.componentRef.setInput('today', today);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function date(page: HTMLElement): HTMLInputElement {
    const input = page.querySelector<HTMLInputElement>("input[type='date']");
    if (input === null) {
      throw new Error('The confirm has no date field.');
    }
    return input;
  }

  function type(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  beforeEach(() => {
    TestBed.configureTestingModule({});
  });

  it('asks one question, about the thing being marked done, defaulting to today', async () => {
    const page = await open('Beddengoed wassen');

    // Portal's field was a *required* datepicker labelled "When did you do it?", and ADR-0014
    // amended itself to keep it: the whole reason this action exists is that you did the thing away
    // from the app, so a path that can only mean *today* forces the lie ADR-0011 was written to end.
    expect(page.textContent).toContain('When did you do it?');
    expect(page.textContent).toContain('Beddengoed wassen');
    expect(date(page).value).toBe('2026-08-14');
    expect(page.querySelectorAll('input').length).toBe(1);
  });

  it('confirms with today when the date is left alone', async () => {
    const page = await open('Beddengoed wassen');
    const confirmed: string[] = [];
    fixture.componentInstance.confirmed.subscribe((on: string) => confirmed.push(on));

    page.querySelector<HTMLElement>('.confirm')?.click();

    expect(confirmed).toEqual(['2026-08-14']);
  });

  it('confirms with the day the work actually happened', async () => {
    const page = await open('Beddengoed wassen');
    const confirmed: string[] = [];
    fixture.componentInstance.confirmed.subscribe((on: string) => confirmed.push(on));

    type(date(page), '2026-08-11');
    page.querySelector<HTMLElement>('.confirm')?.click();

    expect(confirmed).toEqual(['2026-08-11']);
  });

  it('cancels without saying anything happened', async () => {
    const page = await open('Beddengoed wassen');
    const confirmed: string[] = [];
    const cancelled: unknown[] = [];
    fixture.componentInstance.confirmed.subscribe((on: string) => confirmed.push(on));
    fixture.componentInstance.cancelled.subscribe(() => cancelled.push(true));

    page.querySelector<HTMLElement>('.dismiss')?.click();

    expect(confirmed).toEqual([]);
    expect(cancelled.length).toBe(1);
  });
});
