import { FetchEventSourceInit } from '@microsoft/fetch-event-source';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { NOW } from '../clock';
import { Snapshot } from '../domain/sync';
import { Task, TaskPatch } from '../domain/task';
import { LocalStore } from '../store/local-store';
import { AuthService } from './auth';
import { EVENT_SOURCE, PatchStream } from './stream';
import { SyncApi } from './sync-api';
import { SyncStatus } from './sync-status';
import { radio, until } from '../testing';

/**
 * The read side: the boot path, the resume, and the resync.
 *
 * This is the least observable mechanism in the whole contract — the connection ends by design
 * several times an hour, and a resume that quietly skips a patch produces two devices that disagree
 * with no error anywhere. So the fake transport here is driven the way the real one behaves,
 * including the part that surprises: a clean close is the *normal* case, not the end.
 */

const TASK_A = '11111111-1111-1111-1111-111111111111';
const TASK_B = '22222222-2222-2222-2222-222222222222';

function creation(id: string, taskId: string, name: string, dateTime: string): TaskPatch {
  return {
    id,
    taskId,
    dateTime,
    sequence: null,
    voids: null,
    changes: {
      name,
      creationDateTime: dateTime,
      startDate: '2026-03-01',
      dueDate: null,
      context: 'Personal',
      importance: 'IMPORTANT',
      description: null,
      status: 'OPEN',
    },
  };
}

const A_CREATED = creation(
  'aaaaaaaa-0000-0000-0000-000000000001',
  TASK_A,
  'Buy bread',
  '2026-03-01T08:00:00Z',
);
const B_CREATED = creation(
  'bbbbbbbb-0000-0000-0000-000000000001',
  TASK_B,
  'Call the plumber',
  '2026-03-02T08:00:00Z',
);
const A_RENAMED: TaskPatch = {
  id: 'aaaaaaaa-0000-0000-0000-000000000002',
  taskId: TASK_A,
  dateTime: '2026-03-03T08:00:00Z',
  sequence: 41,
  voids: null,
  changes: { name: 'Buy sourdough' },
};

/** One connection at a time, driven by hand — the same shape `fetchEventSource` presents. */
class FakeTransport {
  readonly urls: string[] = [];
  readonly headers: Record<string, string>[] = [];
  private open: {
    init: FetchEventSourceInit;
    done: () => void;
    failed: (error: unknown) => void;
  } | null = null;

  readonly source = (input: RequestInfo, init: FetchEventSourceInit): Promise<void> =>
    new Promise<void>((resolve, reject) => {
      this.urls.push(String(input));
      this.headers.push({ ...init.headers });
      this.open = { init, done: resolve, failed: reject };
      // The library resolves rather than rejects when the caller aborts.
      init.signal?.addEventListener('abort', () => resolve());
    });

  get connections(): number {
    return this.urls.length;
  }

  async accept(status = 200): Promise<void> {
    const connection = this.require();
    try {
      await connection.init.onopen?.(
        new Response(null, { status, headers: { 'content-type': 'text/event-stream' } }),
      );
    } catch (error) {
      // Faithful to the library: `onopen` throwing goes to `onerror`, and an `onerror` that throws
      // rejects the whole call. That is how this client switches the library's retry off.
      this.raise(error);
    }
  }

  emit(event: string, data: string, id = ''): void {
    this.require().init.onmessage?.({ event, data, id });
  }

  /** The server closing the stream cleanly, which it does every 15–30 minutes by design. */
  closeCleanly(): void {
    const connection = this.require();
    this.open = null;
    connection.done();
  }

  /** The socket dying. */
  drop(): void {
    this.raise(new Error('connection reset'));
  }

  private raise(error: unknown): void {
    const connection = this.require();
    this.open = null;
    try {
      connection.init.onerror?.(error);
      connection.failed(error);
    } catch (thrown) {
      connection.failed(thrown);
    }
  }

  private require(): {
    init: FetchEventSourceInit;
    done: () => void;
    failed: (error: unknown) => void;
  } {
    if (this.open === null) {
      throw new Error('No connection is open.');
    }
    return this.open;
  }
}

/** Only `history` is read off a snapshot task, so only `history` is built. */
function snapshotTask(history: TaskPatch[]): Task {
  return { history } as unknown as Task;
}

describe('the patch stream', () => {
  let store: LocalStore;
  let stream: PatchStream;
  let status: SyncStatus;
  let auth: AuthService;
  let transport: FakeTransport;
  let snapshots: Snapshot[];

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    transport = new FakeTransport();
    snapshots = [];

    TestBed.configureTestingModule({
      providers: [
        { provide: EVENT_SOURCE, useValue: transport.source },
        { provide: NOW, useValue: () => new Date('2026-03-05T09:00:00Z') },
        {
          provide: SyncApi,
          useValue: {
            snapshot: () => {
              const next = snapshots.shift();
              return next === undefined
                ? Promise.reject(new Error('No snapshot was expected.'))
                : Promise.resolve(next);
            },
          },
        },
      ],
    });
    store = TestBed.inject(LocalStore);
    stream = TestBed.inject(PatchStream);
    status = TestBed.inject(SyncStatus);
    auth = TestBed.inject(AuthService);
    auth.token = () => Promise.resolve('a-token');
    await store.ready();
  });

  afterEach(async () => {
    await stream.stop();
    vi.restoreAllMocks();
  });

  it('takes a snapshot on first run and streams from its watermark, not from now', async () => {
    snapshots.push({ epoch: 3, watermark: 40, tasks: [snapshotTask([A_CREATED])] });

    stream.start();
    await until('The stream', () => transport.connections === 1);

    // The watermark, not the moment the stream attached: every patch landing between the two would
    // otherwise be lost for ever, invisibly, since both calls succeed.
    expect(transport.urls[0]).toBe('/api/task-patches?since=40&epoch=3');
    expect(await store.cursor()).toEqual({ epoch: 3, sequence: 40 });
    expect((await store.task(TASK_A))?.name).toBe('Buy bread');
    expect(transport.headers[0]['Authorization']).toBe('Bearer a-token');
  });

  it('folds a streamed patch and advances the cursor to its event id', async () => {
    await store.setCursor({ epoch: 3, sequence: 40 });

    stream.start();
    await until('The stream', () => transport.connections === 1);
    await transport.accept();
    transport.emit('patch', JSON.stringify(A_CREATED), '3:39');
    transport.emit('patch', JSON.stringify(A_RENAMED), '3:41');

    await until('The stream', () => status.revision() >= 2);

    expect((await store.task(TASK_A))?.name).toBe('Buy sourdough');
    expect(await store.cursor()).toEqual({ epoch: 3, sequence: 41 });
  });

  it('does not treat a heartbeat as a place in the history', async () => {
    await store.setCursor({ epoch: 3, sequence: 40 });

    stream.start();
    await until('The stream', () => transport.connections === 1);
    await transport.accept();
    transport.emit('heartbeat', 'keepalive');

    expect(await store.cursor()).toEqual({ epoch: 3, sequence: 40 });
  });

  it('reconnects from the persisted cursor after the server closes the stream on purpose', async () => {
    await store.setCursor({ epoch: 3, sequence: 40 });

    stream.start();
    await until('The stream', () => transport.connections === 1);
    await transport.accept();
    transport.emit('patch', JSON.stringify(A_CREATED), '3:41');
    await until('The stream', () => status.revision() >= 1);

    transport.closeCleanly();
    await until('The stream', () => transport.connections === 2);

    // No gap and no duplicate: the second connection asks for what came *after* the last patch it
    // actually stored — not after the cursor it started with, and not from the beginning.
    expect(transport.urls[1]).toBe('/api/task-patches?since=41&epoch=3');
  });

  it('resyncs on the server`s say-so, and keeps the outbox while doing it', async () => {
    await store.setCursor({ epoch: 3, sequence: 40 });
    await store.receivePatches([B_CREATED]);
    // A local edit that has not been sent yet — the thing a resync must never take with it.
    await store.recordLocalPatch(A_CREATED);
    snapshots.push({ epoch: 4, watermark: 7, tasks: [] });

    stream.start();
    await until('The stream', () => transport.connections === 1);
    await transport.accept();
    transport.emit('resync', '4');

    await until('The stream', () => transport.connections === 2);

    expect(transport.urls[1]).toBe('/api/task-patches?since=7&epoch=4');
    // What came from the server is gone; what this device has not sent is not.
    expect(await store.task(TASK_B)).toBeNull();
    expect((await store.pending()).map((patch) => patch.id)).toEqual([A_CREATED.id]);
    expect((await store.task(TASK_A))?.name).toBe('Buy bread');
  });

  it('stalls and prompts on `401` rather than looping on it', async () => {
    await store.setCursor({ epoch: 3, sequence: 40 });

    stream.start();
    await until('The stream', () => transport.connections === 1);
    await transport.accept(401);

    await until('The stream', () => TestBed.inject(AuthService).loginRequired());
    expect(transport.connections).toBe(1);
  });

  it('reports the server unreachable when the socket dies', async () => {
    await store.setCursor({ epoch: 3, sequence: 40 });

    stream.start();
    await until('The stream', () => transport.connections === 1);
    await transport.accept();
    transport.drop();

    await until('The stream', () => !status.reachable());
    expect(status.online()).toBe(true);
  });

  /**
   * The read side's half of [#69](https://github.com/stainii/task/issues/69).
   *
   * `connect` declines to dial while the radio is believed off, so a reconnect loop that never asks
   * the radio again can only repeat the belief it started with. Miss one `online` event and the
   * device stops receiving anything anyone else does — silently, because a stream that is not
   * connected looks exactly like a server with nothing to say.
   */
  it('reconnects when the radio comes back even though the browser never fires `online`', async () => {
    await store.setCursor({ epoch: 3, sequence: 40 });
    radio(false);
    status.online.set(false);

    stream.start();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(transport.connections).toBe(0);

    radio(true);

    await until('The stream', () => transport.connections === 1, 1_000);
  });
});
