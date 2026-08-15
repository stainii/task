import { Injectable } from '@angular/core';

/**
 * Where the auth server is — fetched at runtime, never baked into the bundle.
 *
 * [ADR-0007](../../../../docs/adr/0007-the-box-pulls-nightly-behind-a-dump.md): a realm URL
 * compiled in at build time makes the image environment-specific, and the image is the thing both
 * environments are supposed to share. So `GET /api/config`, which the back-end leaves
 * unauthenticated because a client that must present a token to find out where tokens come from
 * can never obtain one.
 *
 * **Its failure is not fatal.** *Authenticate to sync, not to see*
 * ([ADR-0004](../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)) means a cold
 * boot offline renders from IndexedDB with no token and no config; the app simply cannot sync until
 * it reaches the network. Nothing here is awaited on the way to the first paint.
 */
export interface ClientConfig {
  readonly keycloak: KeycloakConfig;

  /**
   * When the **back end** was built, as an ISO-8601 instant.
   *
   * ADR-0009's answer to *did the deploy stop happening?*, and it rides here rather than on
   * `/actuator/info` so there is one public endpoint rather than two. Only the back end can state
   * this: `ngsw` serves a cached bundle, so a server build date compiled into the front end reports
   * when this device's cache was built, which after a failed deploy looks exactly like success.
   */
  readonly buildTime: string;
}

export interface KeycloakConfig {
  readonly url: string;
  readonly realm: string;
  readonly clientId: string;
}

@Injectable({ providedIn: 'root' })
export class ClientConfigService {
  private cached: ClientConfig | null = null;
  private inFlight: Promise<ClientConfig> | null = null;

  /**
   * The configuration, fetching it once and remembering it.
   *
   * **Only a success is remembered.** A failed fetch is a device that has not reached the server
   * yet, which is the ordinary state of this app rather than an error — caching the rejection
   * would make the first offline boot permanent for the life of the tab.
   *
   * Plain `fetch`, not `HttpClient`: the bearer interceptor asks the auth service for a token, and
   * the auth service cannot be configured until this call has returned.
   */
  async config(): Promise<ClientConfig> {
    if (this.cached !== null) {
      return this.cached;
    }
    this.inFlight ??= this.fetchConfig().finally(() => (this.inFlight = null));
    return this.inFlight;
  }

  /**
   * The configuration again, from the server, ignoring what is remembered.
   *
   * The auth half never changes in the life of a deployment; the build date is the whole point.
   * Reading it through the cache would freeze it at whatever the tab first saw, so an installed PWA
   * left open across a night of deploys could never notice a skew — the banner would be a
   * one-shot check disguised as a standing one.
   */
  async refresh(): Promise<ClientConfig> {
    this.cached = null;
    return this.config();
  }

  private async fetchConfig(): Promise<ClientConfig> {
    const response = await fetch('/api/config', { headers: { accept: 'application/json' } });
    if (!response.ok) {
      throw new Error(`GET /api/config answered ${response.status}.`);
    }
    this.cached = (await response.json()) as ClientConfig;
    return this.cached;
  }
}
