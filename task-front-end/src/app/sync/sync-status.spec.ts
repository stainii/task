import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { NOW } from '../clock';
import { LocalStore } from '../store/local-store';
import { SyncStatus } from './sync-status';

/**
 * The facts ADR-0009's banners are made of, and the one rule that keeps them facts:
 * **the store takes it before the signal says it.**
 *
 * `lastSyncedAt` is the ADR's *first* fact and `/status` renders it, so the state where the answer
 * matters most — a browser that has stopped storing anything — is the one where the old order
 * invented it ([#70](https://github.com/stainii/task/issues/70)).
 */

const MONDAY = new Date('2026-08-17T09:00:00.000Z');
const TUESDAY = new Date('2026-08-18T09:00:00.000Z');

describe('what the sync loop knows about itself', () => {
  let status: SyncStatus;
  let stored: string | null;
  let now: Date;
  let storing: (at: string) => Promise<void>;

  beforeEach(() => {
    stored = null;
    now = MONDAY;
    storing = (at) => {
      stored = at;
      return Promise.resolve();
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: NOW, useValue: () => now },
        {
          provide: LocalStore,
          useValue: {
            lastSyncedAt: () => Promise.resolve(stored),
            setLastSyncedAt: (at: string) => storing(at),
          },
        },
      ],
    });
    status = TestBed.inject(SyncStatus);
  });

  it('remembers when a sync last worked, durably and in the signal', async () => {
    await status.succeeded();

    expect(stored).toBe(MONDAY.toISOString());
    expect(status.lastSyncedAt()).toBe(MONDAY.toISOString());
    expect(status.reachable()).toBe(true);
  });

  /**
   * The defect this file was opened for. A quota refusal, an evicted database or a private window
   * makes the write fail, and the old order had already told the signal it succeeded — so `/status`
   * answered *when did a sync last work* with an instant nothing on this device can vouch for, and
   * nothing ever took it back.
   */
  it('does not claim a sync the store refused to take', async () => {
    await status.succeeded();
    now = TUESDAY;
    storing = () => Promise.reject(new Error('no storage here'));

    await status.succeeded();

    expect(status.lastSyncedAt()).toBe(MONDAY.toISOString());
  });

  /**
   * And it has to be *caught*. Every caller of `succeeded()` is a fire-and-forget loop —
   * `Outbox#drainOnce`, `PatchStream#receive`, `TemplateService#refresh` — so a rejection escaping
   * here is an unhandled one and no other effect at all: the same silence
   * [#69](https://github.com/stainii/task/issues/69) closed one layer along.
   */
  it('reports the store as unavailable rather than rejecting into nothing', async () => {
    storing = () => Promise.reject(new Error('no storage here'));

    await expect(status.succeeded()).resolves.toBeUndefined();

    expect(status.storeUnavailable()).toBe(true);
  });

  /**
   * The rule is about facts that mirror the store, and `reachable` is not one of them: the server
   * answering is something this client just observed, true whether or not IndexedDB then took a
   * note of it. Blaming the network for a dead database is the wrong banner, and the one that never
   * clears — the distinction `TemplateService#refresh` already draws.
   */
  it('still says the server answered, because it did', async () => {
    status.unreachable();
    storing = () => Promise.reject(new Error('no storage here'));

    await status.succeeded();

    expect(status.reachable()).toBe(true);
  });
});
