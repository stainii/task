import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { RejectedChange } from '../../ui/rejections';

/**
 * **The changes the server refused**, as a band on the overview above *Due today*
 * ([ADR-0014 §136](../../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md)) —
 * the failed-to-sync list ADR-0004 required and never located.
 *
 * **Above the work, not below it**, because a rejection is not diagnostics: it is *something you
 * believe you did that did not happen*, and it outranks today's work. Putting it behind `⋯` was
 * refused for ADR-0009's reason — you would go on believing those tasks were done, indefinitely, and
 * the only thing that could tell you is a screen you have no reason to open.
 *
 * **No inline mark on the task, and the band alone.** The variant that marked the rejection on the
 * row needed a band *as well*, because the commonest case has no row at all: a rejected completion
 * succeeded locally, the fold closed the task, and this screen does not show closed tasks. That is
 * the reason marking-on-the-task lost, not a specification for building both — #58's own body had it
 * the other way round and ADR-0014 corrected it.
 *
 * It vanishes entirely when there is nothing rejected, so the common case costs nothing.
 */
@Component({
  selector: 'app-rejected-changes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  templateUrl: './rejected-changes.html',
  styleUrl: './rejected-changes.css',
})
export class RejectedChanges {
  readonly changes = input.required<readonly RejectedChange[]>();

  /** *Fix and retry*: put this patch back in the queue and try again. */
  readonly retry = output<string>();

  /** *Discard*: forget the notice. The patch stays in the task's history either way. */
  readonly discard = output<string>();
}
