import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { NOW } from '../../clock';
import { BuildSkew } from '../../pwa/build-skew';
import { PushService } from '../../sync/push';
import { SyncService } from '../../sync/sync';
import { builtLabel, syncedLabel } from '../../ui/wording';

/**
 * The boring screen (ADR-0014): what is left *after* a banner has already spoken — the two build
 * dates, the 07:30 push toggle for this device, and log out.
 *
 * **Deliberately not a peer of the two destinations**, because ADR-0009 rules that health must come
 * to you. Nobody opens a status page daily, which is the whole reason a dashboard was rejected as
 * an alert channel: it is read carefully in week one and never again, manufacturing confidence
 * throughout. So nothing here is the detection — the banners in the shell are — and everything here
 * is what you look at once something has told you to.
 *
 * The dates stay visible passively even on a healthy day, for the one question no rule can answer:
 * *have I actually pushed anything this month?*
 *
 * Built by [#63](https://github.com/stainii/task/issues/63).
 */
@Component({
  selector: 'app-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './status.html',
  styleUrl: './status.css',
})
export class Status {
  private readonly sync = inject(SyncService);
  private readonly skew = inject(BuildSkew);
  private readonly push = inject(PushService);
  private readonly now = inject(NOW);

  protected readonly lastSynced = computed(() => syncedLabel(this.sync.lastSyncedAt(), this.now()));

  /** This bundle. `App built`, not `Front end built`: the app is the thing a person installed. */
  protected readonly appBuilt = computed(() => builtLabel(this.skew.frontEndBuiltAt(), this.now()));

  /** And the server's own answer, which only the server can give (ADR-0009). */
  protected readonly serverBuilt = computed(() =>
    builtLabel(this.skew.backEndBuiltAt(), this.now()),
  );

  /** Nothing works when the store is gone: this app *is* its store. */
  protected readonly storeUnavailable = this.sync.storeUnavailable;

  protected readonly pushAvailable = this.push.available;
  protected readonly pushEnabled = this.push.enabled;
  protected readonly pushProblem = this.push.problem;

  /**
   * The tap is the gesture the permission prompt needs, so this may not be moved behind anything
   * asynchronous that happens first (ADR-0012).
   */
  protected togglePush(): void {
    void (this.pushEnabled() ? this.push.disable() : this.push.enable());
  }

  /** FE-030, entire. The app keeps working afterwards: it renders from IndexedDB, not a session. */
  protected logOut(): void {
    void this.sync.logout();
  }
}
