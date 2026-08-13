import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * One authoring screen, trigger first (ADR-0013), at `/templates/:id`.
 *
 * Built by [#61](https://github.com/stainii/task/issues/61).
 */
@Component({
  selector: 'app-template-authoring',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2>Template</h2>
    <p>Authoring lands with #61.</p>
  `,
})
export class TemplateAuthoring {
  readonly id = input.required<string>();
}
