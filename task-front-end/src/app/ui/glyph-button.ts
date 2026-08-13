import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatTooltip } from '@angular/material/tooltip';

import { Glyph, VERBS, VerbName } from './glyph';

/**
 * An icon-only control for a verb.
 *
 * ADR-0019 requires that such a control **always** carries an accessible name and a tooltip with
 * the verb's full name — "accessibility is not a tax paid here; it is where the word goes", since
 * choosing a glyph is what buys the verb its full name back at zero horizontal cost. That pairing
 * lives here rather than in each caller, so it cannot be forgotten one screen at a time.
 *
 * `label` overrides the verb's own name for the cases where the same glyph means something longer:
 * the templates list's ✓ is *I already did this*, not *Complete* (ADR-0014).
 */
@Component({
  selector: 'app-glyph-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Glyph, MatTooltip],
  template: `
    <button
      type="button"
      class="app-glyph-button"
      [class]="'tone-' + tone()"
      [attr.aria-label]="name()"
      [matTooltip]="name()"
    >
      <app-glyph [verb]="verb()" />
    </button>
  `,
  styles: `
    .app-glyph-button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 40px;
      height: 40px;
      padding: 0;
      border: 0;
      border-radius: 999px;
      background: transparent;
      color: var(--app-muted);
      cursor: pointer;
    }

    .app-glyph-button:hover {
      background: var(--app-chip);
    }

    .app-glyph-button.tone-good {
      color: var(--app-complete);
    }

    .app-glyph-button.tone-danger {
      color: var(--app-cancel);
    }
  `,
})
export class GlyphButton {
  readonly verb = input.required<VerbName>();

  /** Overrides the verb's own name, for both the tooltip and the accessible name. */
  readonly label = input<string>();

  /**
   * `danger` is for cancel, which ADR-0019 separates from the constructive verbs. Colour says
   * *category*, never *which*, so it is a supplement to the separation, not a substitute for it.
   */
  readonly tone = input<'neutral' | 'good' | 'danger'>('neutral');

  protected readonly name = computed(() => this.label() ?? VERBS[this.verb()].name);
}
