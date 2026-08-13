import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach, describe, expect, it } from 'vitest';

import { NOW } from '../clock';
import { TaskPatch } from '../domain/task';
import { LocalStore } from '../store/local-store';
import { AuthService } from './auth';
import { Outbox } from './outbox';
import { SendResult, SyncApi } from './sync-api';
import { SyncStatus } from './sync-status';

/**
 * The outbox rules, which are the whole of ADR-0004's write side.
 *
 * Every test here is about the *difference between two failures*: the patch is permanently wrong,
 * or the world is temporarily wrong. Getting that backwards is not a cosmetic bug in either
 * direction — one loses a week of work into a pile nobody reads, the other freezes every write on
 * the device for ever while the app still looks fine.
 */

const TASK = '11111111-1111-1111-1111-111111111111';

function patch(id: string, changes: Record<string, string | null> = { name: 'x' }): TaskPatch {
  return {
    id,
    taskId: TASK,
    dateTime: '2026-03-01T08:00:00Z',
    sequence: null,
    voids: null,
    changes,
  };
}

/** A creation patch carries every field, because the fold replays from it. */
const CREATED = patch('aaaaaaaa-0000-0000-0000-000000000001', {
  name: 'Buy bread',
  creationDateTime: '2026-03-01T08:00:00Z',
  startDate: '2026-03-01',
  dueDate: null,
  context: 'Personal',
  importance: 'IMPORTANT',
  description: null,
  status: 'OPEN',
});
const RENAMED = patch('aaaaaaaa-0000-0000-0000-000000000002', { name: 'Buy sourdough' });
const POSTPONED = patch('aaaaaaaa-0000-0000-0000-000000000003', { startDate: '2026-03-04' });

/** Answers whatever the test told it to, and records the order it was asked. */
class FakeApi {
  readonly sent: string[] = [];
  answers = new Map<string, SendResult>();
  fallback: SendResult = { outcome: 'accepted', status: 200 };

  send(patch: TaskPatch): Promise<SendResult> {
    this.sent.push(patch.id);
    return Promise.resolve(this.answers.get(patch.id) ?? this.fallback);
  }

  snapshot(): Promise<never> {
    throw new Error('The outbox never reads.');
  }
}

describe('the outbox', () => {
  let store: LocalStore;
  let outbox: Outbox;
  let api: FakeApi;
  let status: SyncStatus;
  let auth: AuthService;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [
        { provide: SyncApi, useValue: api },
        { provide: NOW, useValue: () => new Date('2026-03-05T09:00:00Z') },
      ],
    });
    store = TestBed.inject(LocalStore);
    outbox = TestBed.inject(Outbox);
    status = TestBed.inject(SyncStatus);
    auth = TestBed.inject(AuthService);
    await store.ready();
  });

  async function queue(...patches: TaskPatch[]): Promise<void> {
    for (const each of patches) {
      await store.recordLocalPatch(each);
    }
  }

  it('drains strictly in order, which is what makes the first patch the create', async () => {
    await queue(CREATED, RENAMED, POSTPONED);

    expect(await outbox.drain()).toBe('drained');

    expect(api.sent).toEqual([CREATED.id, RENAMED.id, POSTPONED.id]);
    expect(await store.pending()).toEqual([]);
  });

  it('drops a `400` and keeps going, where the user can see it', async () => {
    api.answers.set(RENAMED.id, { outcome: 'rejected', status: 400 });
    await queue(CREATED, RENAMED, POSTPONED);

    expect(await outbox.drain()).toBe('drained');

    // The one behind it still went: a permanently wrong patch may not freeze the queue.
    expect(api.sent).toEqual([CREATED.id, RENAMED.id, POSTPONED.id]);
    expect(await store.pending()).toEqual([]);
    expect(outbox.failures().map((failure) => failure.patchId)).toEqual([RENAMED.id]);
    expect(outbox.needsAttention().map((failure) => failure.patchId)).toEqual([RENAMED.id]);
  });

  it('records a `404` but does not ask a human about it', async () => {
    api.answers.set(RENAMED.id, { outcome: 'orphan', status: 404 });
    await queue(CREATED, RENAMED);

    await outbox.drain();

    // An orphan is a client bug, not the user's work going missing (ADR-0004): kept as evidence,
    // not put on the overview.
    expect(outbox.failures().map((failure) => failure.patchId)).toEqual([RENAMED.id]);
    expect(outbox.needsAttention()).toEqual([]);
  });

  it('surfaces the status the server actually gave, not the one the rule is named after', async () => {
    api.answers.set(CREATED.id, { outcome: 'rejected', status: 413 });
    await queue(CREATED);

    await outbox.drain();

    expect(outbox.failures()[0].status).toBe(413);
  });

  it('stalls on `401` and preserves the queue, rather than discarding a week of work', async () => {
    api.answers.set(CREATED.id, { outcome: 'unauthenticated', status: 401 });
    await queue(CREATED, RENAMED);

    expect(await outbox.drain()).toBe('unauthenticated');

    // Nothing behind it was tried, and nothing was dropped: `401` says nothing about the patch.
    expect(api.sent).toEqual([CREATED.id]);
    expect((await store.pending()).map((each) => each.id)).toEqual([CREATED.id, RENAMED.id]);
    expect(outbox.failures()).toEqual([]);
    expect(auth.loginRequired()).toBe(true);
  });

  it('does not prompt for a login when the radio went while the request was in flight', async () => {
    await queue(CREATED);
    api.send = async (each) => {
      api.sent.push(each.id);
      // The tunnel, mid-request. A `401` from a connection that is already gone says nothing about
      // the session, and a prompt nobody can act on is worse than a queue that quietly waits.
      status.online.set(false);
      return { outcome: 'unauthenticated', status: 401 };
    };

    expect(await outbox.drain()).toBe('unauthenticated');

    expect(auth.loginRequired()).toBe(false);
    expect((await store.pending()).map((each) => each.id)).toEqual([CREATED.id]);
  });

  it('stalls on `5xx` in place and reports the server unreachable', async () => {
    api.answers.set(CREATED.id, { outcome: 'unreachable', status: 503 });
    await queue(CREATED, RENAMED);

    expect(await outbox.drain()).toBe('unreachable');

    expect(api.sent).toEqual([CREATED.id]);
    expect((await store.pending()).map((each) => each.id)).toEqual([CREATED.id, RENAMED.id]);
    expect(status.reachable()).toBe(false);
  });

  it('makes no request at all while the radio is off', async () => {
    await queue(CREATED);
    status.online.set(false);

    expect(await outbox.drain()).toBe('unreachable');

    // A week offline is a week of silence, not a week of failed requests. And `reachable` stays
    // true: an untried server is not a broken one, which is what keeps the banner quiet on a train.
    expect(api.sent).toEqual([]);
    expect(status.reachable()).toBe(true);
  });

  it('picks up a patch made while it was already draining', async () => {
    let release = (): void => undefined;
    const held = new Promise<void>((resolve) => (release = resolve));
    api.send = async (each) => {
      api.sent.push(each.id);
      if (each.id === CREATED.id) {
        await held;
      }
      return { outcome: 'accepted', status: 200 };
    };

    await queue(CREATED);
    const draining = outbox.drain();
    await store.recordLocalPatch(RENAMED);
    release();

    expect(await draining).toBe('drained');
    expect(api.sent).toEqual([CREATED.id, RENAMED.id]);
  });

  it('remembers failures across a reload, because a reload is not an acknowledgement', async () => {
    api.answers.set(CREATED.id, { outcome: 'rejected', status: 400 });
    await queue(CREATED);
    await outbox.drain();

    // Everything in memory is thrown away; only the store survives a reload.
    outbox.failures.set([]);
    await outbox.restore();

    expect(outbox.failures().map((failure) => failure.patchId)).toEqual([CREATED.id]);

    await outbox.forget(CREATED.id);
    expect(outbox.failures()).toEqual([]);
    // The patch itself stays: it is part of the task's history whatever the server thought of it.
    expect(await store.task(TASK)).not.toBeNull();
  });
});
