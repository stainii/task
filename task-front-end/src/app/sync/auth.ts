import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import Keycloak from 'keycloak-js';

import { ClientConfigService, KeycloakConfig } from './client-config';

/**
 * How a `Keycloak` is built — behind a token so a spec can hand this service one that misbehaves.
 *
 * The same seam as `EVENT_SOURCE` in `stream.ts`, and for the same reason: every state of this class
 * worth testing is a state of the library it wraps, and the one that cost
 * [#71](https://github.com/stainii/task/issues/71) two tickets is an `init()` that never settles.
 */
export const KEYCLOAK = new InjectionToken<(config: KeycloakConfig) => Keycloak>('keycloak', {
  providedIn: 'root',
  factory: () => (config) => new Keycloak(config),
});

/**
 * What came back when a token was asked for.
 *
 * **Three answers, not two**, and that is the whole of
 * [#94](https://github.com/stainii/task/issues/94). A `string | null` made *Keycloak says there is
 * no session* and *Keycloak did not answer me* the same fact, and they are opposites: the first is
 * a verdict only the user can act on, the second is this client's own failure to ask. Collapsed
 * into one `null`, both loops sent a request with no bearer, the server refused it exactly as it
 * should, and a `401` the client had caused was reported to the user as *sign in*. The bar went up
 * seconds after a cold start, and pressing it asked for no password — because the session had been
 * there all along.
 */
export type TokenAnswer =
  /** A bearer token, good for at least {@link AuthService.MIN_TOKEN_VALIDITY_SECONDS}. */
  | { readonly kind: 'token'; readonly value: string }
  /** The auth server answered, and this device has no session. The one answer worth prompting on. */
  | { readonly kind: 'no-session' }
  /**
   * No answer: the silent check never came back, the refresh did not, the configuration could not
   * be fetched. Says nothing about the session, so **nothing may be shown to the user about it** —
   * the loops treat it exactly as they treat a server that is not answering.
   */
  | { readonly kind: 'unknown' };

const NO_SESSION: TokenAnswer = { kind: 'no-session' };
const UNKNOWN: TokenAnswer = { kind: 'unknown' };

/**
 * Authentication, on ADR-0004's terms: **authenticate to sync, not to see.**
 *
 * Nothing here runs at boot. The app renders from IndexedDB with no token and no network, and
 * Keycloak is initialised the first time something actually needs to talk to the server — which is
 * the literal reading of the rule, and the only way a cold start offline can work at all. That is
 * why this is not `provideKeycloak`: an `APP_INITIALIZER` gates the first paint on the auth
 * server being reachable, and `onLoad: 'login-required'` is deleted for exactly that reason
 * ([#14](https://github.com/stainii/task/issues/14)).
 *
 * **It never redirects on its own.** {@link token} returns a token if one can be had silently and
 * says why it could not otherwise; the redirect happens only in {@link login}, which the outbox
 * raises when a `401`/`403` stalls it *and* the device is online. An expired token degrades the
 * client to offline mode rather than bouncing it to a login screen.
 *
 * `keycloak-js` directly rather than `keycloak-angular`: what that library adds is the bootstrap
 * provider and interceptor helpers, and the provider is the thing this design cannot use.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  /**
   * How much validity a token must have left, in seconds, before it is handed out.
   *
   * A patch that leaves with four seconds of token left is a `401` that stalls the whole outbox
   * behind it, and the retry is a round trip away.
   */
  private static readonly MIN_TOKEN_VALIDITY_SECONDS = 30;

  /**
   * How long anything this service waits on is given before the answer is *not now*, in
   * milliseconds.
   *
   * One number for both waits, because there is only one rule to express: **nothing the library is
   * asked for may be awaited for ever** ([#71](https://github.com/stainii/task/issues/71)). Neither
   * of them is bounded by `keycloak-js` — the silent `check-sso` iframe resolves on a `postMessage`
   * and on nothing else, and a token refresh is a `fetch`, which has no timeout of its own either.
   *
   * Ten seconds is the library's own `messageReceiveTimeout` — **borrowed by analogy, not because it
   * applies here.** It bounds `keycloak-js`'s *other* hidden iframe, the third-party-cookie probe,
   * and that is the point: the library has already decided how long one of its iframes may be given
   * to post back, and the silent `check-sso` iframe is the same shape and the same round trip. Taking
   * its answer beats minting a number, and it is generous — both waits are same-origin, so a device
   * that is going to answer at all answers in well under a second.
   *
   * The two iframes' fates are worth reading together, because they are this ticket in miniature: the
   * probe's 504 rejects at ten seconds and the client recovers on its next attempt; the silent
   * check's identical 504 used to be awaited for ever.
   */
  static readonly ANSWER_TIMEOUT_MS = 10_000;

  private readonly clientConfig = inject(ClientConfigService);
  private readonly newKeycloak = inject(KEYCLOAK);

  /**
   * Whether a human has to intervene before this client can sync again.
   *
   * Distinct from *not authenticated*: offline there is nothing to prompt for and the outbox simply
   * waits. This is set only when the device is online and the server has refused it.
   */
  readonly loginRequired = signal(false);

  private keycloak: Keycloak | null = null;
  private initialising: Promise<Keycloak | null> | null = null;

  /**
   * A bearer token, or the reason there is none — see {@link TokenAnswer}.
   *
   * Not having one is an ordinary answer, not a failure: it is what a device with no session gets
   * and what an offline device gets. **Which of the two it was is the part that matters**, because
   * only one of them is something the user can do anything about.
   */
  async token(): Promise<TokenAnswer> {
    const keycloak = await this.instance();
    if (keycloak === null) {
      // The configuration or the silent check never came back. Nothing was refused; nothing is known.
      return UNKNOWN;
    }
    if (!keycloak.authenticated) {
      return NO_SESSION;
    }
    try {
      await bounded(
        keycloak.updateToken(AuthService.MIN_TOKEN_VALIDITY_SECONDS),
        AuthService.ANSWER_TIMEOUT_MS,
      );
    } catch {
      // Either the refresh token is gone, or the refresh never came back at all — and `keycloak-js`
      // rejects the same way for both, so this frame cannot tell them apart and must not guess. The
      // instance goes with it, so the next ask runs the silent check against the SSO cookie again:
      // a session that is genuinely over comes back as `no-session` from *there*, which is an answer
      // rather than an inference.
      this.keycloak = null;
      return UNKNOWN;
    }
    const value = keycloak.token;
    return value === undefined ? UNKNOWN : { kind: 'token', value };
  }

  /** Raises the login prompt — a full-page redirect, returning to where the user was. */
  async login(): Promise<void> {
    const keycloak = await this.instance();
    if (keycloak === null) {
      // No config, so no auth server to send them to. Offline: there is nothing to prompt for.
      return;
    }
    await keycloak.login({ redirectUri: window.location.href });
  }

  /** #63's log-out item. Returns to the app, which still renders everything from IndexedDB. */
  async logout(): Promise<void> {
    const keycloak = await this.instance();
    await keycloak?.logout({ redirectUri: window.location.origin });
  }

  /**
   * The Keycloak instance, initialised on first use, or null if it could not be configured.
   *
   * `check-sso` **through a silent iframe**, never a navigation: `check-sso` without
   * `silentCheckSsoRedirectUri` sends the whole page to the auth server and back, which on a device
   * with no signal is a browser error page instead of an app. `silentCheckSsoFallback: false`
   * keeps it that way when the iframe cannot be used — falling back to the navigation would
   * reintroduce the failure the iframe exists to avoid, on precisely the browsers most likely to
   * block it.
   *
   * **Neither a failed initialisation nor a negative one is remembered as a verdict**, only as *not
   * now*: the next attempt re-runs it, because the usual cause is that the network was not there
   * yet.
   *
   * The second half of that was [#94](https://github.com/stainii/task/issues/94), and it is the
   * sharper bug of the two. Only a *thrown* `init()` used to be retried; one that **resolved**
   * saying *no session* was cached, so a silent check that answered no — Keycloak restarting behind
   * the proxy on a nightly deploy, a round trip lost on a radio still waking up — was the client's
   * answer for the rest of the page's life. It never looked again, the sign-in bar was the only way
   * out, and a plain reload fixed it: proof that the session had been there the whole time.
   *
   * **And a slow one is a failed one, after {@link ANSWER_TIMEOUT_MS}** — which is not belt-and-braces
   * but the whole of [#71](https://github.com/stainii/task/issues/71). `#checkSsoSilently` waits for
   * its hidden iframe to post back and has no timeout and no error path: lose the radio while it is
   * in flight, the iframe gets an offline document instead of the auth server, and `init()` never
   * settles. Every later `token()` then awaits the *same* pending promise — `initialising` is only
   * cleared in its `finally` — so both loops stop making requests at all, for the life of the page,
   * with nothing anywhere reporting a fault. `reachable` stays true because nothing ever failed,
   * `loginRequired` stays false because nothing was refused, and no banner has a state to fire on.
   *
   * That is [#69](https://github.com/stainii/task/issues/69)'s shape one layer down. #69 fixed *the
   * retry refuses itself*; this is *the retry never returns*, and it is a real device's bug for the
   * same reason: a phone that loses signal during the seconds the app is starting up never syncs
   * again that session.
   */
  private instance(): Promise<Keycloak | null> {
    if (this.keycloak !== null) {
      return Promise.resolve(this.keycloak);
    }
    this.initialising ??= this.initialise().finally(() => (this.initialising = null));
    return this.initialising;
  }

  private async initialise(): Promise<Keycloak | null> {
    let keycloak: Keycloak;
    try {
      const config = await this.clientConfig.config();
      keycloak = this.newKeycloak(config.keycloak);
      await bounded(
        keycloak.init({
          onLoad: 'check-sso',
          silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
          silentCheckSsoFallback: false,
          pkceMethod: 'S256',
          // The session-status iframe polls the auth server for ever, which for an app that is
          // expected to spend days offline is a permanent background request that can only fail.
          // The bounded stream lifetime is what re-checks the session here (ADR-0004).
          checkLoginIframe: false,
        }),
        AuthService.ANSWER_TIMEOUT_MS,
      );
    } catch {
      return null;
    }
    if (keycloak.authenticated) {
      // Only a session is worth keeping. An instance that came back unauthenticated is a *moment*,
      // not a state: `keycloak-js` never revisits that flag on its own, so caching one here is
      // caching the answer `null` to every later ask.
      this.keycloak = keycloak;
      this.loginRequired.set(false);
    }
    return keycloak;
  }
}

/**
 * The same promise, with a deadline — rejecting rather than resolving, so a caller's existing
 * failure path is the timeout's path too.
 *
 * The abandoned promise is left to its own devices because there is no way to reclaim it: what it
 * holds is a hidden iframe and a `window` `message` listener, both private to `keycloak-js`, and if
 * it does eventually answer it answers onto an instance nobody holds any more. So a device stuck in
 * this state leaks **one iframe and one listener per attempt** — stated rather than waved away. It is
 * bounded by the pump's own backoff, which caps at a minute, and it is the cheaper of the two
 * failures by a wide margin: the alternative is the caller still holding the promise, which is the
 * client not syncing again at all.
 */
function bounded<T>(work: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const deadline = setTimeout(
      () => reject(new Error(`It took longer than ${ms}ms, so it is not happening now.`)),
      ms,
    );
    work.then(resolve, reject).finally(() => clearTimeout(deadline));
  });
}
