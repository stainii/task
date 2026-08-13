import { Injectable } from '@angular/core';

import { closedAt, foldOf, IncompleteTaskHistoryError } from '../domain/fold';
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
 * Draining the outbox, the stream, the cursor and the `4xx`/`5xx` rules all belong to
 * [#56](https://github.com/stainii/task/issues/56). This store queues, hands over and forgets on
 * request; it never decides when to send.
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
  static readonly VERSION = 1;

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
    this.db ??= open(LocalStore.DATABASE, LocalStore.VERSION, (db) => {
      const patches = db.createObjectStore('patches', { keyPath: 'id' });
      patches.createIndex('taskId', 'taskId');

      db.createObjectStore('tasks', { keyPath: 'id' });

      // Auto-incrementing key, because the outbox is an *ordered* queue: `5xx` and network failures
      // stall in place and preserve order, so the key has to be the position and not the patch id.
      const outbox = db.createObjectStore('outbox', { keyPath: 'position', autoIncrement: true });
      outbox.createIndex('patchId', 'patchId', { unique: true });
    });
    return this.db;
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

  /**
   * Throws everything away — ADR-0004's hard reset, the path that recovers from an evicted or
   * corrupt store by refetching from the server.
   *
   * It erases the undo horizon with everything else. That is the deal, not a defect.
   */
  async hardReset(): Promise<void> {
    const db = await this.database();
    const tx = db.transaction(['tasks', 'patches', 'outbox'], 'readwrite');
    for (const name of ['tasks', 'patches', 'outbox']) {
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
