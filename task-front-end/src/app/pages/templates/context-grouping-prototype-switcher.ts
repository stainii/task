import { ChangeDetectionStrategy, Component, HostListener, inject, input } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

/**
 * PROTOTYPE (issue #80, wayfinder map #76) — throwaway. Not for production.
 *
 * Floating bottom bar that cycles the `?variant=` query param so the three context-grouping
 * variants on the templates page can be flipped through without leaving the route.
 */
@Component({
  selector: 'app-context-grouping-prototype-switcher',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="prototype-switcher">
      <button type="button" (click)="cycle(-1)" aria-label="Previous variant">←</button>
      <span class="label">{{ current() }} — {{ labels[current()] }}</span>
      <button type="button" (click)="cycle(1)" aria-label="Next variant">→</button>
    </div>
  `,
  styles: [
    `
      .prototype-switcher {
        position: fixed;
        z-index: 999999;
        left: 50%;
        bottom: 12px;
        transform: translateX(-50%);
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 8px 14px;
        background: #1a1a2e;
        color: #fff;
        border-radius: 999px;
        box-shadow: 0 4px 16px rgba(0, 0, 0, 0.35);
        font: 13px system-ui, sans-serif;
      }
      button {
        color: #fff;
        background: rgba(255, 255, 255, 0.15);
        border: none;
        border-radius: 999px;
        width: 26px;
        height: 26px;
        cursor: pointer;
        font-size: 14px;
        line-height: 1;
      }
      .label {
        white-space: nowrap;
      }
    `,
  ],
})
export class ContextGroupingPrototypeSwitcher {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly current = input.required<string>();

  protected readonly order = ['A', 'B', 'C'];
  protected readonly labels: Record<string, string> = {
    A: 'Plain headers, always expanded',
    B: 'Count badge + collapsible groups',
    C: 'Quick-jump context chips',
  };

  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (target && ['INPUT', 'TEXTAREA'].includes(target.tagName)) {
      return;
    }
    if (target?.isContentEditable) {
      return;
    }
    if (event.key === 'ArrowLeft') {
      this.cycle(-1);
    } else if (event.key === 'ArrowRight') {
      this.cycle(1);
    }
  }

  protected cycle(direction: 1 | -1): void {
    const index = this.order.indexOf(this.current());
    const next = this.order[(index + direction + this.order.length) % this.order.length];
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { variant: next },
      queryParamsHandling: 'merge',
    });
  }
}
