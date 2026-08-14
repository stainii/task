import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { MatTooltip } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

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

  constructor() {
    // Started here rather than in an initialiser, because nothing in it may gate the first paint:
    // the store renders this device's tasks with no token and no network (ADR-0004).
    void this.sync.start();
  }

  protected logIn(): void {
    void this.sync.login();
  }

  protected dismissNotice(): void {
    this.notices.dismiss();
  }

  protected logOut(): void {
    void this.sync.logout();
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
