import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The templates list — the reminding surface (ADR-0014): every row carries when it was last done,
 * as an elapsed count *and* a date, plus a ✓ that opens the shared date confirm.
 *
 * Built by [#61](https://github.com/stainii/task/issues/61).
 */
@Component({
  selector: 'app-templates',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2>Templates</h2>
    <p>The reminding list lands with #61.</p>
  `,
})
export class Templates {}
