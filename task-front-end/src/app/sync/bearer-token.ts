import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';

import { AuthService } from './auth';

/**
 * Attaches the bearer token to `/api` calls — and **retries nothing**.
 *
 * FE-028's interceptor is transformed rather than ported. Portal's re-logged in on an expired token
 * and retried the request, which is the right instinct in the wrong place: for a patch, retrying is
 * the outbox's job and only the outbox can do it in order and across a browser kill; for a template
 * it is wrong outright, because ADR-0004 makes templates online-write-only and a failed template
 * edit must be **visibly** unavailable rather than quietly pending.
 *
 * So: the token goes on, nothing comes back around. Whatever the outbox does not own fails fast.
 *
 * `/api/config` is skipped deliberately — it is the endpoint that says where tokens come from, so
 * asking for a token to call it is circular. It uses plain `fetch` today; the guard is here so that
 * stays true if anything ever moves it onto `HttpClient`.
 */
export const bearerToken: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || request.url.startsWith('/api/config')) {
    return next(request);
  }

  const auth = inject(AuthService);
  return from(auth.token()).pipe(
    switchMap((answer) =>
      // No token is not an error here. The request goes out bare and comes back `401`, which is
      // what the outbox reads as *stall and preserve order* — the alternative, failing locally,
      // would be a different error for the same situation depending on how far the client got.
      //
      // That holds for both of the token-less answers, and deliberately: this frame does not know
      // whose request it is carrying. **Deciding what a missing token means is the loops' job** —
      // `PatchStream` and `Outbox` ask before they dial and never let an `unknown` reach the
      // network (#94), so the bare request this sends is one whose `401` is a real verdict.
      next(
        answer.kind === 'token'
          ? request.clone({ setHeaders: { Authorization: `Bearer ${answer.value}` } })
          : request,
      ),
    ),
  );
};
