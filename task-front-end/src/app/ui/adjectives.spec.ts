import { describe, expect, it } from 'vitest';

import { templateNamePlaceholder } from './adjectives';

/**
 * FE-034, the last surviving row of portal's `funny-details/` — *"My crazy template name"*.
 *
 * Tested through the placeholder rather than through the word, because the placeholder is what a
 * person reads. The fraction is the seam: the pick is a number handed in, so every one of these is
 * an ordinary assertion on a literal instead of portal's `expect(first).not.toEqual(second)`, which
 * is a coin flip that lands wrong once in every few hundred runs.
 */
describe('the pimped template name', () => {
  it('says what the field is for, with a word from the list in it', () => {
    expect(templateNamePlaceholder(0)).toBe('My outgoing template name');
  });

  /**
   * The two ends, because an index off either one is not a crash — it is *My undefined template
   * name* on screen, and the type says `string` throughout.
   */
  it('reaches the last word, and never steps past it', () => {
    expect(templateNamePlaceholder(0.999999)).toBe('My dizzy template name');
    expect(templateNamePlaceholder(1)).toBe('My dizzy template name');
    expect(templateNamePlaceholder(-0.5)).toBe('My outgoing template name');
  });

  /**
   * **Every word is reachable, and each one owns an equal slice of the range.** A sweep rather than
   * a spot check: the mistakes available here — rounding instead of flooring, an off-by-one on the
   * length — all leave the function returning a perfectly ordinary word, so no single assertion can
   * see them. Counting what 994 evenly spaced picks reach can.
   */
  it('spreads its picks evenly across the whole list', () => {
    const count = 994;
    const reached = new Set(
      Array.from({ length: count }, (_, index) => templateNamePlaceholder((index + 0.5) / count)),
    );

    expect(reached.size).toBe(count);
    expect(reached).toContain('My outgoing template name');
    expect(reached).toContain('My dizzy template name');
  });
});
