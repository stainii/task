import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { SyncService } from '../sync/sync';
import { QueuedIndicator } from './queued-indicator';

/**
 * FE-027, and the one measured failure behind it: **in portal, forty offline changes looked exactly
 * like zero.** Every test here is about the number being sayable, because a variant that cannot
 * distinguish three from forty reproduces the defect this affordance was raised to fix.
 *
 * The four states are ADR-0015's table, and they are not symmetrical
 * (*The queued indicator speaks while draining, not only while offline*).
 */

const online = signal(true);
const queued = signal(0);

let fixture: ComponentFixture<QueuedIndicator>;

async function show(radio: boolean, waiting: number): Promise<string> {
  online.set(radio);
  queued.set(waiting);
  fixture = TestBed.createComponent(QueuedIndicator);
  await fixture.whenStable();
  return ((fixture.nativeElement as HTMLElement).textContent ?? '').trim();
}

function glyph(): Element | null {
  return (fixture.nativeElement as HTMLElement).querySelector('svg');
}

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [{ provide: SyncService, useValue: { online, queued } }],
  });
});

describe('the queued indicator', () => {
  it('costs nothing in the state the app is almost always in', async () => {
    // Online with an empty queue is the overwhelmingly common case, and ADR-0015's word for this
    // affordance is *quiet*: nothing at all, not a placeholder and not a tick.
    expect(await show(true, 0)).toBe('');
    expect(glyph()).toBeNull();
  });

  it('says it is syncing while the queue drains, in words', async () => {
    // The author's ruling against the recommendation: it was offered as the variant to leave out,
    // because the queue drains in seconds and the message mostly flickers. Accepted cost — the
    // appbar is no longer motionless in normal use — taken because *did that save?* is asked in
    // exactly the moment this answers it.
    expect(await show(true, 12)).toBe('Syncing 12…');
    expect(glyph()).toBeNull();
  });

  it('draws the one state that never needs explaining, and says nothing beside it', async () => {
    expect(await show(false, 0)).toBe('');
    expect(glyph()).not.toBeNull();
  });

  it('says how many are waiting, as a word beside the glyph', async () => {
    // Not a badge *on* the glyph: rendered, that is indistinguishable from an unread-notification
    // count — a thing demanding action — while nothing here is wrong and there is nothing to act on.
    expect(await show(false, 40)).toBe('40 waiting');
    expect(glyph()).not.toBeNull();
  });

  it('tells three from forty, which is the whole of FE-027', async () => {
    expect(await show(false, 3)).toBe('3 waiting');
    expect(await show(false, 40)).toBe('40 waiting');
  });

  it('carries Offline as its accessible name, like every other glyph in the app', async () => {
    await show(false, 40);

    const indicator = (fixture.nativeElement as HTMLElement).querySelector('.indicator');
    expect(indicator?.getAttribute('aria-label')).toBe('Offline');
  });
});
