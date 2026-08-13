import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * ADR-0006's overview, at `/` and — once you enter a context — at `/in/:value`.
 *
 * Entering is a route, not in-page state (ADR-0014): a push notification, a shared link and a
 * restored session are then the same mechanism. The parameter is `value`, not `context`, because
 * ADR-0006 makes the grouping axis a property of the card row alone and a route naming the axis
 * would hard-code it into every stored URL.
 *
 * The bands, the cap, the cards and the task panel are
 * [#57](https://github.com/stainii/task/issues/57) and
 * [#58](https://github.com/stainii/task/issues/58).
 */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2>{{ value() ?? 'Everything' }}</h2>
    <p>The overview lands with #57 and #58.</p>
  `,
})
export class Overview {
  /** Bound from the route parameter; absent at `/`, which is every context at once. */
  readonly value = input<string>();
}
