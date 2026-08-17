import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { RejectedChange } from '../../ui/rejections';
import { RejectedChanges } from './rejected-changes';

const COMPLETION: RejectedChange = {
  patchId: 'p1',
  taskId: 'a',
  name: 'Boeken tandarts voor Elise',
  act: 'marked complete',
  when: 'Tuesday',
  why: 'You completed it on this device, so it has already left your list.',
};

const CREATION: RejectedChange = {
  patchId: 'p2',
  taskId: 'b',
  name: 'Offerte dakwerken opvolgen',
  act: 'created',
  when: 'Tuesday',
  why: 'It is on this device only — the server never took it.',
};

let fixture: ComponentFixture<RejectedChanges>;

async function render(changes: RejectedChange[]): Promise<HTMLElement> {
  fixture = TestBed.createComponent(RejectedChanges);
  fixture.componentRef.setInput('changes', changes);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('the rejected-changes band', () => {
  it('leaves no trace at all when nothing was refused', async () => {
    // The common case has to cost nothing: a band saying *all synced* is a claim nobody asked for,
    // in the position reserved for something being wrong.
    expect((await render([])).textContent?.trim()).toBe('');
  });

  it('names the act and the reason, and gives you the two verbs', async () => {
    const band = await render([COMPLETION]);

    expect(band.querySelector('.act')?.textContent?.trim()).toBe(
      'Boeken tandarts voor Elise — marked complete',
    );
    expect(band.querySelector('.why')?.textContent?.trim()).toBe(
      'Tuesday. You completed it on this device, so it has already left your list.',
    );
    expect(
      [...band.querySelectorAll('button')].map((button) => button.textContent?.trim()),
    ).toEqual(['Fix and retry', 'Discard']);
  });

  it('says nothing about a status code', async () => {
    // *Rejected (400)* is **constant** — a validation refusal is a 400 essentially always — so it
    // would be a fixed phrase in the spot the eye reaches first, on a band whose entire job is to
    // say what went wrong *this time*. The technical half lives on `/status`.
    const band = await render([COMPLETION, CREATION]);

    expect(band.textContent).not.toContain('400');
    expect(band.textContent).not.toContain('Rejected');
  });

  it('keeps the two verbs as words, not glyphs', async () => {
    // ADR-0019 applied, not excepted. Drawing the glyph variant found the collision the rule
    // predicts: the bin for *discard this change* would sit one band above the glyph for *cancel
    // this task* — two destructive icons of similar weight meaning entirely different things.
    const band = await render([COMPLETION]);

    expect(band.querySelectorAll('svg')).toHaveLength(0);
  });

  it('says how many were refused, in the heading', async () => {
    expect(
      (await render([COMPLETION, CREATION])).querySelector('.band-title')?.textContent,
    ).toContain('2 changes the server refused');
  });

  it('says it in the singular for one', async () => {
    expect((await render([COMPLETION])).querySelector('.band-title')?.textContent).toContain(
      '1 change the server refused',
    );
  });

  it('hands back the patch each verb was pressed on', async () => {
    const band = await render([COMPLETION, CREATION]);
    const retried: string[] = [];
    const discarded: string[] = [];
    fixture.componentInstance.retry.subscribe((id: string) => retried.push(id));
    fixture.componentInstance.discard.subscribe((id: string) => discarded.push(id));

    band.querySelectorAll<HTMLElement>('.retry')[1].click();
    band.querySelectorAll<HTMLElement>('.discard')[0].click();
    await fixture.whenStable();

    expect(retried).toEqual(['p2']);
    expect(discarded).toEqual(['p1']);
  });
});
