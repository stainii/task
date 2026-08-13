import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { NOW } from '../clock';
import { TaskPatch } from '../domain/task';
import { LocalStore } from '../store/local-store';
import { AuthService } from './auth';
import { EVENT_SOURCE } from './stream';
import { SendResult, SyncApi } from './sync-api';
import { SyncStatus } from './sync-status';
import { SyncService } from './sync';

/**
 * The wiring: what starts the loops, and what a local write is allowed to claim.
 *
 * The one guarantee here that no other test can make is ADR-0004's: **a write is acknowledged only
 * once it is durably in the outbox.** A failed IndexedDB write otherwise looks exactly like a
 * successful tick, and no error report can fix that — which is why ADR-0009 rejected front-end
 * telemetry in favour of this.
 */

const TASK = '11111111-1111-1111-1111-111111111111';

const CREATED: TaskPatch = {
  id: 'aaaaaaaa-0000-0000-0000-000000000001',
  taskId: TASK,
  dateTime: '2026-03-01T08:00:00Z',
  sequence: null,
  voids: null,
  changes: {
    name: 'Buy bread',
    creationDateTime: '2026-03-01T08:00:00Z',
    startDate: '2026-03-01',
    dueDate: null,
    context: 'Personal',
    importance: 'IMPORTANT',
    description: null,
    status: 'OPEN',
  },
};

describe('the sync service', () => {
  let sync: SyncService;
  let store: LocalStore;
  let status: SyncStatus;
  let sent: string[];
  let answer: SendResult;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    sent = [];
    answer = { outcome: 'accepted', status: 200 };

    TestBed.configureTestingModule({
      providers: [
        { provide: NOW, useValue: () => new Date('2026-03-05T09:00:00Z') },
        // A read side that is simply down: this file is about the write side and the wiring, and a
        // stub that never settles would make `stop()` hang rather than prove anything.
        { provide: EVENT_SOURCE, useValue: () => Promise.reject(new Error('no stream here')) },
        {
          provide: AuthService,
          useValue: {
            token: () => Promise.resolve('a-token'),
            loginRequired: { set: () => undefined },
          },
        },
        {
          provide: SyncApi,
          useValue: {
            send: (patch: TaskPatch) => {
              sent.push(patch.id);
              return Promise.resolve(answer);
            },
            snapshot: () => Promise.reject(new Error('no snapshot here')),
          },
        },
      ],
    });
    sync = TestBed.inject(SyncService);
    store = TestBed.inject(LocalStore);
    status = TestBed.inject(SyncStatus);
    await sync.start();
  });

  afterEach(async () => {
    await sync.stop();
  });

  async function until(condition: () => boolean): Promise<void> {
    for (let attempt = 0; attempt < 200; attempt++) {
      if (condition()) {
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    throw new Error('The sync service never reached the expected state.');
  }

  it('acknowledges a write only once it is durably queued', async () => {
    const task = await sync.record(CREATED);

    // Resolved means committed: by the time the caller can render the tick, the patch is in the
    // outbox and would survive a browser kill.
    expect(task?.name).toBe('Buy bread');
    expect(await store.task(TASK)).toEqual(task);
  });

  it('refuses to acknowledge a write the store could not take', async () => {
    await store.close();
    // The store cannot open: a private window, a quota refusal, an eviction mid-session.
    globalThis.indexedDB = {
      open: () => {
        throw new Error('no storage here');
      },
    } as unknown as IDBFactory;

    await expect(sync.record(CREATED)).rejects.toThrow();

    // And the drain it kicked off must not reject into nothing. Nobody awaits the pump, so an
    // escaping rejection is an unhandled one and no other effect — the state has to be *reported*.
    await until(() => sync.storeUnavailable());
  });

  it('sends what was recorded without being asked twice', async () => {
    await sync.record(CREATED);

    await until(() => sent.length === 1);
    expect(await store.pending()).toEqual([]);
    expect(status.lastSyncedAt()).toBe('2026-03-05T09:00:00.000Z');
  });

  it('waits quietly while offline and drains when the radio comes back', async () => {
    status.online.set(false);
    await sync.record(CREATED);

    // Nothing is attempted: the patch is fine and there is nowhere to send it.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(sent).toEqual([]);
    expect(sync.onlineButNotSyncing()).toBe(false);

    window.dispatchEvent(new Event('online'));

    await until(() => sent.length === 1);
    expect(await store.pending()).toEqual([]);
  });

  it('reports online-but-not-syncing once a reachable-looking server refuses to answer', async () => {
    answer = { outcome: 'unreachable', status: 503 };
    await sync.record(CREATED);

    await until(() => sync.onlineButNotSyncing());

    // The queue is intact, in order, and the banner says the true thing: the radio is fine and the
    // server is not.
    expect((await store.pending()).map((patch) => patch.id)).toEqual([CREATED.id]);
  });

  it('bumps a revision whenever sync changed the store, so the overview can re-read', async () => {
    const before = sync.revision();

    await sync.record(CREATED);

    expect(sync.revision()).toBeGreaterThan(before);
  });
});
