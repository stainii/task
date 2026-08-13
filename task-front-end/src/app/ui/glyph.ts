import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The verb vocabulary (ADR-0019: verbs are glyphs, facts are words).
 *
 * Four glyphs, against portal's seventeen. **The clock is reserved for `postpone`** — a clock also
 * reads as "ask me from", and one glyph meaning two things one screen apart is the collision this
 * vocabulary was chosen to avoid.
 *
 * Tripwire: if this passes roughly a dozen entries, the no-icon-font trade flips and ADR-0019 says
 * to revisit it. Adding a glyph for a *fact* is not the way to grow this list — a fact gets a word.
 */
export const VERBS = {
  edit: {
    name: 'Edit',
    paths: ['M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17v3Z', 'M14.5 6.5 17.5 9.5'],
  },
  postpone: {
    name: 'Postpone',
    paths: ['M12 9v4l3 2', 'M17.5 4.5 21 7'],
    circles: [{ cx: 12, cy: 13, r: 8 }],
  },
  complete: {
    name: 'Complete',
    paths: ['M4 12.5 9.5 18 20 6.5'],
  },
  cancel: {
    name: 'Cancel',
    paths: ['M6 6 18 18M18 6 6 18'],
  },
} as const satisfies Record<string, Verb>;

interface Verb {
  readonly name: string;
  readonly paths: readonly string[];
  readonly circles?: readonly { readonly cx: number; readonly cy: number; readonly r: number }[];
}

export type VerbName = keyof typeof VERBS;

/**
 * One verb, drawn as inline SVG on `currentColor`: one asset serves both themes, there is no font
 * to load and nothing to cache offline (ADR-0019).
 *
 * The glyph itself is `aria-hidden` — an icon-only control's accessible name belongs on the
 * control, which is what {@link GlyphButton} guarantees. Where a glyph sits inside something that
 * already reads as text, that text is the name.
 */
@Component({
  selector: 'app-glyph',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg class="app-glyph" viewBox="0 0 24 24" aria-hidden="true">
      @for (circle of drawing().circles ?? []; track circle.cx + '-' + circle.cy) {
        <circle [attr.cx]="circle.cx" [attr.cy]="circle.cy" [attr.r]="circle.r" />
      }
      @for (path of drawing().paths; track path) {
        <path [attr.d]="path" />
      }
    </svg>
  `,
})
export class Glyph {
  readonly verb = input.required<VerbName>();

  protected readonly drawing = computed<Verb>(() => VERBS[this.verb()]);
}
