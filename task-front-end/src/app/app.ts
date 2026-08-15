import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { MatTooltip } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { BuildSkew } from './pwa/build-skew';
import { PushService } from './sync/push';
import { SyncService } from './sync/sync';
import { Notices } from './ui/notices';
import { Omnibox } from './ui/omnibox';

/** The two destinations (ADR-0014). Everything else is somewhere you are *sent*. */
type Destination = 'tasks' | 'templates' | 'elsewhere';

/**
 * The shell: an appbar with an omnibox and a `⋯`, plus two tabs.
 *
 * The tabs are not `routerLinkActive`, because the Tasks destination is two routes — `/` and
 * ADR-0014's `/in/:value` — and entering a context must not look like leaving Tasks.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, MatMenu, MatMenuItem, MatMenuTrigger, MatTooltip, Omnibox],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly router = inject(Router);
  private readonly sync = inject(SyncService);
  private readonly notices = inject(Notices);
  private readonly skew = inject(BuildSkew);
  private readonly push = inject(PushService);

  /** What a screen said on its way out. */
  protected readonly notice = this.notices.message;

  /**
   * Whether a sync is waiting on a human — ADR-0004's stall prompt.
   *
   * Distinct from Keycloak's deleted `onLoad: 'login-required'`, which gates the app at boot and
   * makes an offline cold start impossible. This one is raised *because a sync needs it*, at the
   * moment it is needed, and never while the device is offline: there is nothing to prompt for.
   */
  protected readonly loginRequired = this.sync.loginRequired;

  /**
   * **ADR-0009's first banner: online but not syncing.**
   *
   * The two halves are what let it need no number. A train tunnel is `online === false` and says
   * nothing; a server that will not answer a device with a working radio is wrong *immediately* —
   * and it is precisely the case ADR-0004 is built to conceal, since the outbox stalls on `5xx` by
   * design and the app renders from IndexedDB regardless. Without this, four days of outage and
   * four days of bad signal are the same experience.
   */
  protected readonly notSyncing = this.sync.onlineButNotSyncing;

  /**
   * **ADR-0009's second banner: a *persistent* build-date mismatch.**
   *
   * *Persistent* is the load-bearing word — see `BuildSkew`. A plain mismatch fires every morning
   * after a nightly deploy, and daily signal is wallpaper within a week.
   */
  protected readonly buildSkew = this.skew.persistentMismatch;

  constructor() {
    // Started here rather than in an initialiser, because nothing in it may gate the first paint:
    // the store renders this device's tasks with no token and no network (ADR-0004).
    void this.sync.start();

    // Both are *on every app open*, and both are deliberately unawaited. The build check is how a
    // stopped deploy gets reported at all — for a daily driver that is under a day's latency, which
    // is the same delay ADR-0008 accepted for backups. The push restore is ADR-0012's repair: the
    // server prunes any endpoint a push service calls `410 Gone`, and re-sending what this browser
    // holds is the only thing that would ever put the row back. Neither prompts, and neither
    // reaches the network before the store has rendered.
    void this.skew.check();
    void this.push.restore();
  }

  protected logIn(): void {
    void this.sync.login();
  }

  protected dismissNotice(): void {
    this.notices.dismiss();
  }

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  protected readonly destination = computed<Destination>(() => {
    const url = this.url();
    // `/task/:id` is a dialog **over** the overview (ADR-0018), so it counts as Tasks for the same
    // reason `/in/:value` does: opening a task is not leaving the destination it belongs to, and a
    // tab that goes dark while a dialog is open reads as having left the app.
    if (url === '/' || url.startsWith('/in/') || url.startsWith('/task/')) {
      return 'tasks';
    }
    if (url.startsWith('/templates')) {
      return 'templates';
    }
    return 'elsewhere';
  });
}
