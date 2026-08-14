import { Injectable } from '@angular/core';

import { closedAt, foldOf, IncompleteTaskHistoryError } from '../domain/fold';
import { SyncCursor, SyncFailure } from '../domain/sync';
import { Task, TaskPatch } from '../domain/task';
import { committed, open, request } from './indexed-db';

/**
 * The client's source of truth: IndexedDB.
 *
 * **The client is authoritative for display at all times.** Neither the stream nor the snapshot is
 * a precondition for the app being usable, so nothing here reaches the network and nothing here
 * waits on it — this store is what the app renders from on a cold boot with the radio off.
 *
 * IndexedDB rather than `localStorage`, per
 * [ADR-0004](../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md): `localStorage`
 * is synchronous and rewrites the whole blob on every change, which is not adequate for a patch
 * history plus an outbox that has to survive a browser kill.
 *
 * ### What is stored, and what is derived
 *
 * **The patches are the truth; the tasks are a materialised view of them.** Every write puts the
 * patch, refolds that task's whole history through {@link foldOf}, and replaces the task row.
 * Refolding rather than applying forward is not optional: a late-arriving patch can land *behind*
 * patches already folded.
 *
 * A history that cannot yet fold into a whole task — the creation patch has not arrived — leaves
 * the patches stored and produces **no task row**, rather than throwing. The row appears by itself
 * when the missing patch lands.
 *
 * ### What this store does not do
 *
 * Draining the outbox, the stream and the `4xx`/`5xx` rules belong to
 * [#56](https://github.com/stainii/task/issues/56). This store queues, hands over and forgets on
 * request; it never decides when to send, and **nothing here reaches the network**. What #56 added
 * is the durable state that survives a reload and would otherwise be lost with the tab: the
 * {@link cursor}, the {@link lastSyncedAt} timestamp and the {@link failures} list.
 */
@Injectable({ providedIn: 'root' })
export class LocalStore {
  /**
   * The client keeps closed tasks for **1 day**, then discards them locally.
   *
   * `GET /api/tasks` returns open tasks only and there is no endpoint to fetch a closed one back,
   * so a client that dropped them immediately would lose the patch id undo needs — for the thing
   * you just did, the one undo that matters most. **Undo is the immediate "oh no", not an archive**,
   * which is equally why the horizon is a day and not a month.
   */
  static readonly CLOSED_TASK_HORIZON_MS = 24 * 60 * 60 * 1000;

  static readonly DATABASE = 'task';

  /** 2 adds #56's `meta` and `failures` stores to #55's three. */
  static readonly VERSION = 2;

  /**
   * Whether the browser granted persistent storage: true, false, or null where it was never asked
   * because the API does not exist. Read by `/status` (#63) — a `false` here is the Safari
   * seven-day eviction risk being live rather than theoretical.
   */
  persistentStorageGranted: boolean | null = null;

  private db: Promise<IDBDatabase> | null = null;

  /**
   * Opens the database and asks for persistent storage.
   *
   * The request is best-effort and its answer never gates anything: Safari deletes script-writable
   * storage after 7 days without interaction, the risk is **accepted**, and PWA install
   * ([#62](https://github.com/stainii/task/issues/62)) is the mitigation. An evicted store is not
   * data loss — ADR-0004's hard-reset path recovers it from the server — **only an undrained
   * outbox is.**
   */
  async ready(): Promise<void> {
    await this.database();
    await this.requestPersistence();
  }

  private database(): Promise<IDBDatabase> {
    // Every store is created only if it is missing, so a browser holding version 1 gains version
    // 2's two stores and keeps its history. An upgrade that recreates unconditionally throws on
    // the first store it meets, and the tab that hits it has an outbox in it.
    this.db ??= this.openDatabase();
    return this.db;
  }

  private openDatabase(): Promise<IDBDatabase> {
    const opening = open(LocalStore.DATABASE, LocalStore.VERSION, (db) => {
      if (!db.objectStoreNames.contains('patches')) {
        db.createObjectStore('patches', { keyPath: 'id' }).createIndex('taskId', 'taskId');
      }

      if (!db.objectStoreNames.contains('tasks')) {
        db.createObjectStore('tasks', { keyPath: 'id' });
      }

      if (!db.objectStoreNames.contains('outbox')) {
        // Auto-incrementing key, because the outbox is an *ordered* queue: `5xx` and network
        // failures stall in place and preserve order, so the key has to be the position and not
        // the patch id.
        const outbox = db.createObjectStore('outbox', { keyPath: 'position', autoIncrement: true });
        outbox.createIndex('patchId', 'patchId', { unique: true });
      }

      // Where the client has got to: the cursor and the last time a sync actually worked. Durable
      // rather than in memory, because both are read after the tab that learned them is gone — the
      // cursor decides where the next stream resumes from, and a *last synced* that resets to
      // "now" on every reload is a fact that can never report the thing it exists to report.
      if (!db.objectStoreNames.contains('meta')) {
        db.createObjectStore('meta');
      }

      // The patches the server refused permanently. Durable for the same reason: a dropped patch
      // is data loss the user must be able to see (FE-029), and a reload is not an acknowledgement.
      if (!db.objectStoreNames.contains('failures')) {
        db.createObjectStore('failures', { keyPath: 'patchId' });
      }
    });

    // A failed open is forgotten rather than remembered as the answer. Caching the rejection would
    // make one bad moment — a version-change race, a transient quota refusal — permanent for the
    // life of the tab, and the caller's retry would get the old failure back without ever asking
    // the browser again.
    return opening.catch((error: unknown) => {
      this.db = null;
      throw error;
    });
  }

  private async requestPersistence(): Promise<void> {
    if (typeof navigator === 'undefined' || navigator.storage?.persist === undefined) {
      return;
    }
    try {
      this.persistentStorageGranted = await navigator.storage.persist();
    } catch {
      // A browser that refuses to be asked is a browser that has not granted it.
      this.persistentStorageGranted = false;
    }
  }

  /**
   * Records a patch this device made: stores it, refolds, and **queues it for sending in the same
   * transaction**.
   *
   * One transaction on purpose. A patch that is visible in the UI but absent from the outbox is an
   * edit the user believes is saved and that no server will ever hear about, and a crash between
   * two transactions is all it would take.
   *
   * A patch id already stored is a no-op — it is the idempotency key.
   */
  async recordLocalPatch(patch: TaskPatch): Promise<Task | null> {
    const db = await this.database();
    const tx = db.transaction(['patches', 'tasks', 'outbox'], 'readwrite');
    const patches = tx.objectStore('patches');

    const known = await request(patches.get(patch.id));
    if (known === undefined) {
      await request(patches.put(patch));
      await request(tx.objectStore('outbox').add({ patchId: patch.id }));
    }

    const task = await this.refold(tx, patch.taskId);
    await committed(tx);
    return task;
  }

  /**
   * Records patches that came from the server — a resync snapshot or the stream.
   *
   * They are **not** queued: the server already has them. A patch of this device's own echoing back
   * is simply a duplicate id and changes nothing; taking it out of the outbox is the sync loop's
   * job (#56), on the response that acknowledged it.
   */
  async receivePatches(incoming: readonly TaskPatch[]): Promise<Task[]> {
    const db = await this.database();
    const tx = db.transaction(['patches', 'tasks'], 'readwrite');
    const patches = tx.objectStore('patches');

    const touched = new Set<string>();
    for (const patch of incoming) {
      await request(patches.put(patch));
      touched.add(patch.taskId);
    }

    const tasks: Task[] = [];
    for (const taskId of touched) {
      const task = await this.refold(tx, taskId);
      if (task !== null) {
        tasks.push(task);
      }
    }
    await committed(tx);
    return tasks;
  }

  /** Refolds one task from its stored history and replaces its row. Caller commits. */
  private async refold(tx: IDBTransaction, taskId: string): Promise<Task | null> {
    const history = await request<TaskPatch[]>(
      tx.objectStore('patches').index('taskId').getAll(taskId),
    );
    const tasks = tx.objectStore('tasks');

    let task: Task;
    try {
      task = foldOf(taskId, history);
    } catch (error) {
      if (error instanceof IncompleteTaskHistoryError) {
        // The patches are kept; only the view of them is missing. Deleting the row rather than
        // leaving a stale one, so a task never renders from a history that no longer folds.
        await request(tasks.delete(taskId));
        return null;
      }
      throw error;
    }

    await request(tasks.put({ id: taskId, task, closedAt: closedAt(task) }));
    return task;
  }

  /** Every task this device holds: the open ones, plus the closed ones inside the horizon. */
  async tasks(): Promise<Task[]> {
    const db = await this.database();
    const rows = await request<StoredTask[]>(db.transaction('tasks').objectStore('tasks').getAll());
    return rows.map((row) => row.task);
  }

  async task(id: string): Promise<Task | null> {
    const db = await this.database();
    const row = await request<StoredTask | undefined>(
      db.transaction('tasks').objectStore('tasks').get(id),
    );
    return row?.task ?? null;
  }

  /** The patches waiting to be sent, **in the order they were made**. */
  async pending(): Promise<TaskPatch[]> {
    const db = await this.database();
    const tx = db.transaction(['outbox', 'patches']);
    const queued = await request<OutboxEntry[]>(tx.objectStore('outbox').getAll());
    const patches = tx.objectStore('patches');

    const pending: TaskPatch[] = [];
    for (const entry of queued) {
      const patch = await request<TaskPatch | undefined>(patches.get(entry.patchId));
      if (patch !== undefined) {
        pending.push(patch);
      }
    }
    return pending;
  }

  /**
   * Takes a patch out of the outbox — the server has it, or it was dropped as permanently wrong.
   *
   * The patch itself stays: it is part of the task's history either way, and the failed-to-sync
   * list is about what the *user* can see, not about forgetting what happened.
   */
  async stopSending(patchId: string): Promise<void> {
    const db = await this.database();
    const tx = db.transaction('outbox', 'readwrite');
    const outbox = tx.objectStore('outbox');
    const key = await request<IDBValidKey | undefined>(outbox.index('patchId').getKey(patchId));
    if (key !== undefined) {
      await request(outbox.delete(key));
    }
    await committed(tx);
  }

  /**
   * Discards tasks closed more than a day ago, with their history. Returns how many went.
   *
   * **A task with a patch still in the outbox is never pruned**, however old. Its history is the
   * body of the request that has not been sent yet, so discarding it would delete an edit the user
   * made and the server has never seen — the one thing eviction actually costs.
   */
  async pruneClosedTasks(now: Date): Promise<number> {
    const db = await this.database();
    const tx = db.transaction(['tasks', 'patches', 'outbox'], 'readwrite');
    const rows = await request<StoredTask[]>(tx.objectStore('tasks').getAll());
    const queued = await request<OutboxEntry[]>(tx.objectStore('outbox').getAll());
    const unsent = new Set(queued.map((entry) => entry.patchId));

    const horizon = now.getTime() - LocalStore.CLOSED_TASK_HORIZON_MS;
    let pruned = 0;
    for (const row of rows) {
      if (row.closedAt === null || Date.parse(row.closedAt) > horizon) {
        continue;
      }
      if (row.task.history.some((patch) => unsent.has(patch.id))) {
        continue;
      }
      await request(tx.objectStore('tasks').delete(row.id));
      for (const patch of row.task.history) {
        await request(tx.objectStore('patches').delete(patch.id));
      }
      pruned++;
    }
    await committed(tx);
    return pruned;
  }

  /** Where the stream should resume from, or null if this client has never synced. */
  async cursor(): Promise<SyncCursor | null> {
    return (await this.meta<SyncCursor>('cursor')) ?? null;
  }

  async setCursor(cursor: SyncCursor): Promise<void> {
    await this.putMeta('cursor', cursor);
  }

  /**
   * When a sync last actually worked, as an ISO-8601 instant — one of ADR-0009's two facts.
   *
   * *Worked* means the server answered: a patch accepted, a stream opened, a snapshot read. Not
   * "we tried", which would report a broken sync as a healthy one.
   */
  async lastSyncedAt(): Promise<string | null> {
    return (await this.meta<string>('lastSyncedAt')) ?? null;
  }

  async setLastSyncedAt(at: string): Promise<void> {
    await this.putMeta('lastSyncedAt', at);
  }

  /**
   * The context the omnibox last captured into, or null before anything has been captured.
   *
   * ADR-0018's fallback for a capture made where no context is being stood in. Durable rather than
   * in memory for the same reason `lastSyncedAt` is: the commonest capture is the first one after
   * opening the app, which is precisely when an in-memory answer has already been forgotten.
   *
   * It lives beside the cursor rather than in `localStorage` so that everything this device
   * remembers is erased by one hard reset, and nothing survives it in a second place.
   */
  async lastContext(): Promise<string | null> {
    return (await this.meta<string>('lastContext')) ?? null;
  }

  async setLastContext(context: string): Promise<void> {
    await this.putMeta('lastContext', context);
  }

  private async meta<T>(key: string): Promise<T | undefined> {
    const db = await this.database();
    return request<T | undefined>(db.transaction('meta').objectStore('meta').get(key));
  }

  private async putMeta(key: string, value: unknown): Promise<void> {
    const db = await this.database();
    const tx = db.transaction('meta', 'readwrite');
    await request(tx.objectStore('meta').put(value, key));
    await committed(tx);
  }

  /** The patches the server refused permanently, oldest first. */
  async failures(): Promise<SyncFailure[]> {
    const db = await this.database();
    const rows = await request<SyncFailure[]>(
      db.transaction('failures').objectStore('failures').getAll(),
    );
    return rows.sort((a, b) => a.at.localeCompare(b.at));
  }

  async recordFailure(failure: SyncFailure): Promise<void> {
    const db = await this.database();
    const tx = db.transaction('failures', 'readwrite');
    await request(tx.objectStore('failures').put(failure));
    await committed(tx);
  }

  /** Forgets one failure — the user has seen it and dealt with it. */
  async forgetFailure(patchId: string): Promise<void> {
    const db = await this.database();
    const tx = db.transaction('failures', 'readwrite');
    await request(tx.objectStore('failures').delete(patchId));
    await committed(tx);
  }

  /**
   * The server's resync lever: drop what came from the server and start again from a snapshot —
   * **but keep the outbox, and keep the patches it names**.
   *
   * ADR-0004 describes resync as dropping local state, and {@link hardReset} is that. Doing it
   * here would be the one loss the ADR calls real. A resync happens when the server cannot serve
   * the cursor, and by far the likeliest cause is a restored backup — precisely the moment a
   * device is most likely to hold work the server has never seen. Discarding it would delete the
   * user's edits *because* the server lost some of its own.
   *
   * The kept patches are re-folded immediately rather than left for the snapshot to restore, and
   * that is not tidiness: a task created offline exists **only** in the outbox, so clearing the
   * task rows and waiting would take it off the screen until the server echoed it back — which for
   * a device that is still offline is never. The edit would look lost while being perfectly safe,
   * which is the same lie as the opposite case.
   *
   * If its task did not survive the restore the outbox will get a `404` and put it in the
   * failed-to-sync list, which is a visible answer rather than a silent one.
   */
  async resetForResync(): Promise<void> {
    const db = await this.database();
    const tx = db.transaction(['tasks', 'patches', 'outbox', 'meta'], 'readwrite');
    const queued = await request<OutboxEntry[]>(tx.objectStore('outbox').getAll());
    const unsent = new Set(queued.map((entry) => entry.patchId));

    await request(tx.objectStore('tasks').clear());
    const patches = tx.objectStore('patches');
    const kept = new Set<string>();
    for (const patch of await request<TaskPatch[]>(patches.getAll())) {
      if (unsent.has(patch.id)) {
        kept.add(patch.taskId);
      } else {
        await request(patches.delete(patch.id));
      }
    }
    for (const taskId of kept) {
      await this.refold(tx, taskId);
    }
    await request(tx.objectStore('meta').delete('cursor'));
    await committed(tx);
  }

  /**
   * Throws everything away — ADR-0004's hard reset, the path that recovers from an evicted or
   * corrupt store by refetching from the server.
   *
   * It erases the undo horizon with everything else. That is the deal, not a defect. Unlike
   * {@link resetForResync} it also erases the outbox, because this lever is pulled deliberately by
   * a human who has decided the local copy is the problem.
   */
  async hardReset(): Promise<void> {
    const db = await this.database();
    const tx = db.transaction(['tasks', 'patches', 'outbox', 'meta', 'failures'], 'readwrite');
    for (const name of ['tasks', 'patches', 'outbox', 'meta', 'failures']) {
      await request(tx.objectStore(name).clear());
    }
    await committed(tx);
  }

  /** Drops this connection. The data stays; the next call reopens. */
  async close(): Promise<void> {
    const db = this.db;
    this.db = null;
    if (db !== null) {
      (await db).close();
    }
  }
}

interface StoredTask {
  readonly id: string;
  readonly task: Task;
  /** Denormalised out of the task so pruning does not have to refold every row to find the date. */
  readonly closedAt: string | null;
}

interface OutboxEntry {
  readonly position: number;
  readonly patchId: string;
}
