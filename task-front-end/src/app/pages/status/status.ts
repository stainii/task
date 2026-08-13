import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The boring screen (ADR-0014): what is left *after* a banner has already spoken — the two build
 * dates, the 07:30 push toggle for this device, and log out. Deliberately not a peer of the two
 * destinations, because ADR-0009 rules that health must come to you.
 *
 * Built by [#63](https://github.com/stainii/task/issues/63).
 */
@Component({
  selector: 'app-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2>Status</h2>
    <p>The build dates, the push toggle and log out land with #63.</p>
  `,
})
export class Status {}
