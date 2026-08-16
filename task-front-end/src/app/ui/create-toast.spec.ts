import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { CreateToast } from './create-toast';

/**
 * *Added “X” in Y*, and the due date the capture deliberately did not get (ADR-0018).
 *
 * Its own component and its own spec since #67, which took the corner off the screens that raised
 * toasts and gave it to the shell. What the omnibox still owns is what the buttons *do*; what this
 * owns is that they are there and say the right words.
 */
describe('CreateToast', () => {
  let fixture: ComponentFixture<CreateToast>;

  async function open(name: string, context: string): Promise<HTMLElement> {
    fixture = TestBed.createComponent(CreateToast);
    fixture.componentRef.setInput('name', name);
    fixture.componentRef.setInput('context', context);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function texts(page: HTMLElement, selector: string): string[] {
    return [...page.querySelectorAll(selector)].map((node) => node.textContent?.trim() ?? '');
  }

  beforeEach(() => {
    TestBed.configureTestingModule({});
  });

  it('names what was added and where it landed', async () => {
    const page = await open('Ramen lappen', 'housagotchi');

    expect(page.textContent).toContain('Ramen lappen');
    expect(page.textContent).toContain('housagotchi');
  });

  it('offers the three the ADR names, and Add details for everything else', async () => {
    const page = await open('Ramen lappen', 'house');

    // The first chip carries the word that makes the row a sentence; the rest inherit it.
    expect(texts(page, '.due')).toEqual(['due today', 'tomorrow', 'in 3 days']);
    expect(page.querySelector('.details')).toBeTruthy();
  });

  it('says how many days out each chip is, so the screen that raised it need not re-read the label', async () => {
    const page = await open('Ramen lappen', 'house');
    const days: number[] = [];
    fixture.componentInstance.due.subscribe((value: number) => days.push(value));

    page.querySelector<HTMLElement>('.due[data-days="1"]')?.click();

    expect(days).toEqual([1]);
  });

  it('asks for the dialog on Add details', async () => {
    const page = await open('Ramen lappen', 'house');
    const asked: unknown[] = [];
    fixture.componentInstance.details.subscribe(() => asked.push(true));

    page.querySelector<HTMLElement>('.details')?.click();

    expect(asked.length).toBe(1);
  });
});
