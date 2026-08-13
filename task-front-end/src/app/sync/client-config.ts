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

  private async fetchConfig(): Promise<ClientConfig> {
    const response = await fetch('/api/config', { headers: { accept: 'application/json' } });
    if (!response.ok) {
      throw new Error(`GET /api/config answered ${response.status}.`);
    }
    this.cached = (await response.json()) as ClientConfig;
    return this.cached;
  }
}
