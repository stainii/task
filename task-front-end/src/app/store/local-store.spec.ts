import { IDBFactory } from 'fake-indexeddb';
import { beforeEach, describe, expect, it } from 'vitest';

import { TaskPatch } from '../domain/task';
import { LocalStore } from './local-store';

/**
 * The store's own tests. What they cannot reach is the browser itself — a real service worker, a
 * real eviction, a real process kill — which is what Playwright (#64) is for. What they can reach,
 * and what actually goes wrong, is the bookkeeping: an edit that is visible but unqueued, a queue
 * that comes back in the wrong order, and a prune that takes an unsent patch with it.
 */

const TASK = '11111111-1111-1111-1111-111111111111';

function patch(id: string, dateTime: string, changes: Record<string, string | null>): TaskPatch {
  return { id, taskId: TASK, dateTime, sequence: null, voids: null, changes };
}

/** A creation patch carries every field, because the fold replays from it. */
const CREATED = patch('aaaaaaaa-0000-0000-0000-000000000001', '2026-03-01T08:00:00Z', {
  name: 'Buy bread',
  creationDateTime: '2026-03-01T08:00:00Z',
  startDate: '2026-03-01',
  dueDate: null,
  context: 'Personal',
  importance: 'IMPORTANT',
  description: null,
  status: 'OPEN',
});

const RENAMED = patch('aaaaaaaa-0000-0000-0000-000000000002', '2026-03-02T08:00:00Z', {
  name: 'Buy sourdough',
});

const COMPLETED = patch('aaaaaaaa-0000-0000-0000-000000000003', '2026-03-03T08:00:00Z', {
  status: 'COMPLETED',
  completedOn: '2026-03-03',
});

describe('the local store', () => {
  let store: LocalStore;

  beforeEach(async () => {
    // A fresh factory per test, so nothing shares a database with anything else.
    globalThis.indexedDB = new IDBFactory();
    store = new LocalStore();
    await store.ready();
  });

  it('folds a patch this device made and queues it for sending', async () => {
    const task = await store.recordLocalPatch(CREATED);

    expect(task?.name).toBe('Buy bread');
    expect(await store.task(TASK)).toEqual(task);
    expect(await store.pending()).toEqual([CREATED]);
  });

  it('refolds rather than applying forward, so a late patch does not win a field it should lose', async () => {
    await store.recordLocalPatch(CREATED);
    await store.recordLocalPatch(
      patch('aaaaaaaa-0000-0000-0000-000000000009', '2026-03-05T08:00:00Z', { name: 'Tuesday' }),
    );
    // Written on Monday, offline, and only now arriving.
    await store.receivePatches([
      patch('aaaaaaaa-0000-0000-0000-00000000000a', '2026-03-04T08:00:00Z', { name: 'Monday' }),
    ]);

    expect((await store.task(TASK))?.name).toBe('Tuesday');
  });

  it('treats a patch id it already holds as a no-op rather than queueing it twice', async () => {
    await store.recordLocalPatch(CREATED);
    await store.recordLocalPatch(CREATED);

    expect(await store.pending()).toEqual([CREATED]);
    expect((await store.task(TASK))?.history).toHaveLength(1);
  });

  it('does not queue patches that came from the server', async () => {
    await store.receivePatches([
      { ...CREATED, sequence: 1 },
      { ...RENAMED, sequence: 2 },
    ]);

    expect((await store.task(TASK))?.name).toBe('Buy sourdough');
    expect(await store.pending()).toEqual([]);
  });

  it('holds patches whose creation patch has not arrived, and produces the task when it does', async () => {
    await store.receivePatches([RENAMED]);
    expect(await store.task(TASK)).toBeNull();
    expect(await store.tasks()).toEqual([]);

    await store.receivePatches([CREATED]);
    expect((await store.task(TASK))?.name).toBe('Buy sourdough');
  });

  it('keeps the outbox, in order, across a lost connection', async () => {
    await store.recordLocalPatch(CREATED);
    await store.recordLocalPatch(RENAMED);
    await store.recordLocalPatch(COMPLETED);

    // As close to a browser kill as a test can get: drop the connection, open the database again.
    await store.close();
    const reopened = new LocalStore();
    await reopened.ready();

    expect((await reopened.pending()).map((queued) => queued.id)).toEqual([
      CREATED.id,
      RENAMED.id,
      COMPLETED.id,
    ]);
    expect((await reopened.task(TASK))?.status).toBe('COMPLETED');
  });

  it('stops sending a patch without forgetting it happened', async () => {
    await store.recordLocalPatch(CREATED);
    await store.recordLocalPatch(RENAMED);

    await store.stopSending(CREATED.id);

    expect(await store.pending()).toEqual([RENAMED]);
    expect((await store.task(TASK))?.name).toBe('Buy sourdough');
  });

  describe('the one-day horizon on closed tasks', () => {
    it('discards a task closed more than a day ago, with its history', async () => {
      await store.receivePatches([CREATED, RENAMED, COMPLETED]);

      const pruned = await store.pruneClosedTasks(new Date('2026-03-04T09:00:00Z'));

      expect(pruned).toBe(1);
      expect(await store.tasks()).toEqual([]);
      // The history goes too, which is what makes undo have a horizon at all.
      expect(await store.task(TASK)).toBeNull();
    });

    it('keeps a task closed within the day, because undo is the immediate "oh no"', async () => {
      await store.receivePatches([CREATED, RENAMED, COMPLETED]);

      expect(await store.pruneClosedTasks(new Date('2026-03-03T20:00:00Z'))).toBe(0);
      expect(await store.tasks()).toHaveLength(1);
    });

    it('dates a task closed by voiding its creation patch from the void', async () => {
      // There is no status patch to read the closing moment off, so the void itself is the moment.
      const undone = {
        ...patch('aaaaaaaa-0000-0000-0000-00000000000f', '2026-03-02T09:00:00Z', {}),
        voids: CREATED.id,
      };
      await store.receivePatches([CREATED, undone]);
      expect((await store.task(TASK))?.status).toBe('COMPLETED');

      expect(await store.pruneClosedTasks(new Date('2026-03-03T08:59:00Z'))).toBe(0);
      expect(await store.pruneClosedTasks(new Date('2026-03-03T09:01:00Z'))).toBe(1);
    });

    it('never discards an open task, however old', async () => {
      await store.receivePatches([CREATED]);

      expect(await store.pruneClosedTasks(new Date('2027-01-01T00:00:00Z'))).toBe(0);
      expect(await store.tasks()).toHaveLength(1);
    });

    it('never discards a task whose patch is still waiting to be sent', async () => {
      await store.receivePatches([CREATED, RENAMED]);
      await store.recordLocalPatch(COMPLETED);

      // Long past the horizon, but the completion has never reached the server: pruning here
      // deletes the body of a request nobody has made.
      expect(await store.pruneClosedTasks(new Date('2027-01-01T00:00:00Z'))).toBe(0);
      expect(await store.pending()).toEqual([COMPLETED]);
    });
  });

  describe('the last context captured into', () => {
    it('survives the tab that learned it', async () => {
      // ADR-0018's fallback when you are not standing in a context: *the last one used*. Durable
      // rather than in memory, because the commonest capture is the first one after opening the
      // app, which is exactly the moment an in-memory answer has already been forgotten.
      await store.setLastContext('housagotchi');
      await store.close();

      expect(await store.lastContext()).toBe('housagotchi');
    });

    it('has no answer before anything has been captured', async () => {
      expect(await store.lastContext()).toBeNull();
    });
  });

  describe('the sync state it holds for #56', () => {
    it('remembers the cursor and the last successful sync across a connection', async () => {
      await store.setCursor({ epoch: 3, sequence: 41 });
      await store.setLastSyncedAt('2026-03-05T09:00:00Z');
      await store.close();

      expect(await store.cursor()).toEqual({ epoch: 3, sequence: 41 });
      expect(await store.lastSyncedAt()).toBe('2026-03-05T09:00:00Z');
    });

    it('has no cursor before the first sync, which is what makes first run first run', async () => {
      expect(await store.cursor()).toBeNull();
    });

    describe('a resync', () => {
      it('drops what came from the server', async () => {
        await store.receivePatches([CREATED, RENAMED]);
        await store.setCursor({ epoch: 3, sequence: 41 });

        await store.resetForResync();

        expect(await store.tasks()).toEqual([]);
        // No cursor, so the next connection takes a snapshot — in the server's new lineage.
        expect(await store.cursor()).toBeNull();
      });

      it('keeps the outbox, and keeps the task its patches fold into visible', async () => {
        await store.recordLocalPatch(CREATED);
        await store.receivePatches([RENAMED]);

        await store.resetForResync();

        // The unsent creation survives with its task: a resync is caused by the *server* losing
        // history, and it may not take this device's unsent work with it.
        expect(await store.pending()).toEqual([CREATED]);
        expect((await store.task(TASK))?.name).toBe('Buy bread');
        // The server's own patch went, so the rename is gone until the snapshot brings it back.
        expect((await store.task(TASK))?.history).toHaveLength(1);
      });
    });
  });

  it('empties everything on a hard reset', async () => {
    await store.recordLocalPatch(CREATED);

    await store.hardReset();

    expect(await store.tasks()).toEqual([]);
    expect(await store.pending()).toEqual([]);
    expect(await store.task(TASK)).toBeNull();
    expect(await store.cursor()).toBeNull();
  });

  it('records whether the browser granted persistent storage', async () => {
    const fresh = new LocalStore();
    expect(fresh.persistentStorageGranted).toBeNull();

    Object.defineProperty(navigator, 'storage', {
      configurable: true,
      value: { persist: () => Promise.resolve(true) },
    });
    await fresh.ready();

    expect(fresh.persistentStorageGranted).toBe(true);
  });
});
