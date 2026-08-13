import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { TaskPatch } from '../domain/task';
import { AuthService } from './auth';
import { bearerToken } from './bearer-token';
import { SyncApi } from './sync-api';

/**
 * The one translation in the client that decides whether work survives: **status to verdict**.
 *
 * Getting a row of this table wrong is not a wrong error message. A `5xx` read as *drop* loses the
 * patch; a `400` read as *stall* freezes every write behind it on that device for ever, while the
 * app carries on looking fine because local state is authoritative for display.
 */

const PATCH: TaskPatch = {
  id: 'aaaaaaaa-0000-0000-0000-000000000001',
  taskId: '11111111-1111-1111-1111-111111111111',
  dateTime: '2026-03-01T08:00:00Z',
  sequence: null,
  voids: null,
  changes: { name: 'Buy bread' },
};

describe('the sync API', () => {
  let api: SyncApi;
  let http: HttpTestingController;
  let token: string | null;

  beforeEach(() => {
    token = 'a-token';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([bearerToken])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { token: () => Promise.resolve(token) } },
      ],
    });
    api = TestBed.inject(SyncApi);
    http = TestBed.inject(HttpTestingController);
  });

  async function sending(
    respond: (request: ReturnType<HttpTestingController['expectOne']>) => void,
  ) {
    const outcome = api.send(PATCH);
    await Promise.resolve();
    respond(http.expectOne('/api/task-patches'));
    return outcome;
  }

  it.each([
    [200, 'accepted'],
    [400, 'rejected'],
    [413, 'rejected'],
    [404, 'orphan'],
    [401, 'unauthenticated'],
    [403, 'unauthenticated'],
    [500, 'unreachable'],
    [503, 'unreachable'],
  ])('reads %i as %s', async (status, expected) => {
    const outcome = await sending((request) =>
      status < 300
        ? request.flush('', { status, statusText: 'ok' })
        : request.flush('', { status, statusText: 'no' }),
    );

    expect(outcome.outcome).toBe(expected);
    expect(outcome.status).toBe(status);
  });

  it('reads a request that never got an answer as the world being down, not the patch being wrong', async () => {
    // Status 0 is a dead link, DNS, a reset connection — the case the outbox must stall on. Reading
    // it as a `4xx` would drop an edit made in a tunnel.
    const outcome = await sending((request) => request.error(new ProgressEvent('error')));

    expect(outcome.outcome).toBe('unreachable');
    expect(outcome.status).toBe(0);
  });

  it('carries the bearer token, and adds nothing else', async () => {
    void api.send(PATCH);
    await Promise.resolve();

    const request = http.expectOne('/api/task-patches');
    expect(request.request.headers.get('Authorization')).toBe('Bearer a-token');
    request.flush('');
  });

  it('sends the request bare when there is no token, rather than failing locally', async () => {
    // The server's `401` is what the outbox knows how to read. Failing here instead would make the
    // same situation produce a different outcome depending on how far the client happened to get.
    token = null;
    void api.send(PATCH);
    await Promise.resolve();

    const request = http.expectOne('/api/task-patches');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush('');
  });

  it('never asks for a token to fetch the endpoint that says where tokens come from', async () => {
    TestBed.inject(HttpClient).get('/api/config').subscribe();
    await Promise.resolve();

    const request = http.expectOne('/api/config');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('retries nothing: what the outbox does not own fails fast', async () => {
    const failed = api.send(PATCH);
    await Promise.resolve();
    http.expectOne('/api/task-patches').flush('', { status: 503, statusText: 'nope' });

    await failed;
    // One request, and one only. Retrying here would be a second retry policy beside the outbox's,
    // out of order and without the queue — which is what FE-028's interceptor used to do.
    http.expectNone('/api/task-patches');
  });

  it('reads the snapshot with its watermark', async () => {
    const snapshot = api.snapshot();
    await Promise.resolve();
    http.expectOne('/api/tasks').flush({ epoch: 3, watermark: 40, tasks: [] });

    expect(await snapshot).toEqual({ epoch: 3, watermark: 40, tasks: [] });
  });
});
