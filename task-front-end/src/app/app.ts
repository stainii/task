import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { MatTooltip } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

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
  imports: [RouterOutlet, RouterLink, MatMenu, MatMenuItem, MatMenuTrigger, MatTooltip],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly router = inject(Router);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  protected readonly destination = computed<Destination>(() => {
    const url = this.url();
    if (url === '/' || url.startsWith('/in/')) {
      return 'tasks';
    }
    if (url.startsWith('/templates')) {
      return 'templates';
    }
    return 'elsewhere';
  });
}
