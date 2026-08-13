import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The task dialog, on a route (ADR-0018): a flat dialog at `/task/:id`, so hardware back closes it
 * and a deep link opens it.
 *
 * Built by [#59](https://github.com/stainii/task/issues/59).
 */
@Component({
  selector: 'app-task-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2>Task</h2>
    <p>The task dialog lands with #59.</p>
  `,
})
export class TaskPage {
  readonly id = input.required<string>();
}
