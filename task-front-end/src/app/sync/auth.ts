import { inject, Injectable, signal } from '@angular/core';
import Keycloak from 'keycloak-js';

import { ClientConfigService } from './client-config';

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

  private readonly clientConfig = inject(ClientConfigService);

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
      await keycloak.updateToken(AuthService.MIN_TOKEN_VALIDITY_SECONDS);
    } catch {
      // The refresh token is gone or expired. Nothing to do silently; the outbox will stall and
      // raise the prompt if the device is online.
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
      keycloak = new Keycloak(config.keycloak);
      await keycloak.init({
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        silentCheckSsoFallback: false,
        pkceMethod: 'S256',
        // The session-status iframe polls the auth server for ever, which for an app that is
        // expected to spend days offline is a permanent background request that can only fail.
        // The bounded stream lifetime is what re-checks the session here (ADR-0004).
        checkLoginIframe: false,
      });
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
