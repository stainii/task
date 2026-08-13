import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Snapshot } from '../domain/sync';
import { TaskPatch } from '../domain/task';

/**
 * What the server did with a patch, in the client's vocabulary rather than HTTP's.
 *
 * The whole outbox turns on this one distinction (ADR-0004): **`4xx` means the patch is permanently
 * wrong, so drop it and keep going; `5xx` and network mean the patch is fine and the world is not,
 * so stop and preserve order** — with `401`/`403` carved out, because they say nothing about the
 * patch at all.
 */
export type SendOutcome =
  /** `2xx`. Accepted and durable — including the `200` a replayed patch id gets, which is the same thing. */
  | 'accepted'
  /** `400`, `413` and every other `4xx`: it will never be accepted. Drop it, and let the user see it. */
  | 'rejected'
  /** `404`: a patch for a task the server has never heard of. */
  | 'orphan'
  /** `401`/`403`: the patch is fine and this client is not authenticated. */
  | 'unauthenticated'
  /** `5xx`, a dead connection, a DNS failure: the world is not ready. */
  | 'unreachable';

/** The verdict plus the status that produced it, because the failed-to-sync list shows the number. */
export interface SendResult {
  readonly outcome: SendOutcome;
  /** The HTTP status, or 0 where the request never got an answer. */
  readonly status: number;
}

/**
 * The two HTTP calls of the sync contract. The third — the stream — cannot be one of these, because
 * it needs headers `EventSource` cannot send.
 */
@Injectable({ providedIn: 'root' })
export class SyncApi {
  private readonly http = inject(HttpClient);

  /**
   * `GET /api/tasks`: **first run and hard reset only**, never the normal boot path.
   *
   * The watermark it carries is what makes the overlap safe — the stream resumes from it rather
   * than from the moment the stream happened to attach.
   */
  snapshot(): Promise<Snapshot> {
    return firstValueFrom(this.http.get<Snapshot>('/api/tasks'));
  }

  /** `POST /api/task-patches`: the only way this client writes. */
  async send(patch: TaskPatch): Promise<SendResult> {
    try {
      await firstValueFrom(this.http.post('/api/task-patches', patch, { responseType: 'text' }));
      return { outcome: 'accepted', status: 200 };
    } catch (error) {
      const status = error instanceof HttpErrorResponse ? error.status : 0;
      return { outcome: outcomeOf(error), status };
    }
  }
}

function outcomeOf(error: unknown): SendOutcome {
  if (!(error instanceof HttpErrorResponse)) {
    return 'unreachable';
  }
  // Status 0 is Angular's rendering of "the request never got an answer" — offline, DNS, a reset
  // connection. Exactly the case the outbox must stall on rather than drop.
  if (error.status === 0 || error.status >= 500) {
    return 'unreachable';
  }
  if (error.status === 401 || error.status === 403) {
    return 'unauthenticated';
  }
  if (error.status === 404) {
    return 'orphan';
  }
  if (error.status >= 400) {
    return 'rejected';
  }
  // A non-error status arriving down the error path is a client bug rather than a server verdict,
  // and dropping the patch on it would be data loss on a guess.
  return 'unreachable';
}
