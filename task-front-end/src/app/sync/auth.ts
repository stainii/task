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
 * `null` otherwise; the redirect happens only in {@link login}, which the outbox raises when a
 * `401`/`403` stalls it *and* the device is online. An expired token degrades the client to offline
 * mode rather than bouncing it to a login screen.
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
   * Ten seconds is the library's own `messageReceiveTimeout`: the number it already uses for *how
   * long a hidden iframe is given to post back*, which is precisely the longer of the two waits.
   * Borrowed rather than picked, and generous — both are same-origin round trips to the auth server,
   * so a device that is going to answer at all answers in well under a second.
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
   * A bearer token, or null if one cannot be had without asking the user.
   *
   * Null is an ordinary answer, not a failure: it is what an offline device gets, and what a device
   * whose session has expired gets. The caller decides what that means — the outbox stalls, and the
   * stream waits.
   */
  async token(): Promise<string | null> {
    const keycloak = await this.instance();
    if (keycloak === null || !keycloak.authenticated) {
      return null;
    }
    try {
      await bounded(
        keycloak.updateToken(AuthService.MIN_TOKEN_VALIDITY_SECONDS),
        AuthService.ANSWER_TIMEOUT_MS,
      );
    } catch {
      // The refresh token is gone or expired, or the refresh never came back at all. Nothing to do
      // silently; the outbox will stall and raise the prompt if the device is online.
      return null;
    }
    return keycloak.token ?? null;
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
   * A failed initialisation is not remembered as a verdict, only as *not now*: the next attempt
   * re-runs it, because the usual cause is that the network was not there yet.
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
    this.keycloak = keycloak;
    if (keycloak.authenticated) {
      this.loginRequired.set(false);
    }
    return keycloak;
  }
}

/**
 * The same promise, with a deadline — rejecting rather than resolving, so a caller's existing
 * failure path is the timeout's path too.
 *
 * The abandoned promise is left to its own devices deliberately. There is nothing to cancel: it is
 * a `keycloak-js` internal waiting on a `postMessage`, and if it does eventually answer it answers
 * onto an instance nobody holds any more. What must not happen is the *caller* still holding it.
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
