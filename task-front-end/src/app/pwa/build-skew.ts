import { inject, Injectable, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';

import { ClientConfigService } from '../sync/client-config';
import { buildDatesDiffer, BUILT_AT } from './build-stamp';

/**
 * **ADR-0009's second banner: a *persistent* build-date mismatch.**
 *
 * The two images are tagged with one commit SHA (ADR-0007) *specifically because* the fold exists
 * in Java and TypeScript and drift between them would be silent — but nothing verified that at
 * runtime until here. This does, and it catches the other half-completed deploy too: one container
 * recreated and the other not.
 *
 * ### Why it needs no threshold, and why *persistent* is load-bearing
 *
 * A plain mismatch fires every morning. ADR-0007 deploys nightly, so between the deploy and `ngsw`
 * swapping the cached bundle the front end is legitimately a day behind — routine, daily, and
 * therefore wallpaper within a week. So the mismatch only becomes a banner once the service worker
 * has been asked for a new version and has none. Then it is rare, and it means something.
 *
 * That is a *state* the browser can answer, not a number someone had to guess at — which is the
 * property ADR-0009 required of both banners.
 *
 * ### And it never latches
 *
 * Every check recomputes from scratch. A banner that outlives the fault it announced is the same
 * furniture as one that fires daily.
 */
@Injectable({ providedIn: 'root' })
export class BuildSkew {
  private readonly updates = inject(SwUpdate);
  private readonly clientConfig = inject(ClientConfigService);

  /** When this bundle was built, or null in development, where nothing stamped it. */
  readonly frontEndBuiltAt = signal(inject(BUILT_AT));

  /** When the server says *it* was built, or null until a check has reached it. */
  readonly backEndBuiltAt = signal<string | null>(null);

  /** Whether to say so. Both dates stay visible on `/status` either way. */
  readonly persistentMismatch = signal(false);

  /**
   * Re-read the server's build date and decide whether to speak.
   *
   * Called by the shell at boot, which for a daily-driver PWA is the latency ADR-0009 accepted: the
   * first thing that reports a stopped deploy is opening the app.
   *
   * **It never throws.** The caller is the shell, and there is nothing it could usefully do about
   * any of the reasons this fails.
   */
  async check(): Promise<void> {
    let serverBuiltAt: string;
    try {
      serverBuiltAt = (await this.clientConfig.refresh()).buildTime;
    } catch {
      // Offline, or a server that is down — which is the *other* banner's subject, and reading it
      // as a stale deploy here would be a second alarm for one fault.
      return;
    }
    this.backEndBuiltAt.set(serverBuiltAt);

    if (!buildDatesDiffer(this.frontEndBuiltAt(), serverBuiltAt)) {
      this.persistentMismatch.set(false);
      return;
    }
    this.persistentMismatch.set(!(await this.workerStillHasAChance()));
  }

  /**
   * Whether something is coming that would fix this on its own.
   *
   * Erring towards *yes* — and so towards silence — wherever the answer cannot be had. An alarm
   * raised because the update machinery could not be asked is an alarm about the wrong thing.
   */
  private async workerStillHasAChance(): Promise<boolean> {
    if (!this.updates.isEnabled) {
      // Nothing will ever swap this bundle, so waiting for it would be waiting for ever — and
      // silence that never ends is indistinguishable from health.
      return false;
    }
    try {
      return await this.updates.checkForUpdate();
    } catch {
      return true;
    }
  }
}
