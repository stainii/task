import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatTooltip } from '@angular/material/tooltip';
import { By } from '@angular/platform-browser';

import { GlyphButton } from './glyph-button';
import { Glyph, VERBS, VerbName } from './glyph';

const verbNames = Object.keys(VERBS) as VerbName[];

@Component({
  imports: [Glyph],
  template: `<app-glyph [verb]="verb" />`,
})
class GlyphHost {
  verb: VerbName = 'edit';
}

@Component({
  imports: [GlyphButton],
  template: `<app-glyph-button [verb]="verb" [label]="label" />`,
})
class GlyphButtonHost {
  verb: VerbName = 'complete';
  label: string | undefined = undefined;
}

describe('Glyph', () => {
  it.each(verbNames)('draws %s as inline SVG on currentColor', async (verb) => {
    const fixture = TestBed.createComponent(GlyphHost);
    fixture.componentInstance.verb = verb;
    await fixture.whenStable();

    const svg = (fixture.nativeElement as HTMLElement).querySelector('svg');
    expect(svg?.getAttribute('viewBox')).toBe('0 0 24 24');
    expect(svg?.querySelectorAll('path, circle').length).toBeGreaterThan(0);
    // No `fill`/`stroke` attributes: the colour comes from `currentColor` in `styles.css`, which is
    // what lets one asset serve both themes (ADR-0019).
    expect(svg?.getAttribute('fill')).toBeNull();
    expect(svg?.getAttribute('stroke')).toBeNull();
  });

  it('stays out of the accessibility tree — the name belongs on the control', async () => {
    const fixture = TestBed.createComponent(GlyphHost);
    await fixture.whenStable();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('svg')?.getAttribute('aria-hidden'),
    ).toBe('true');
  });

  it('spends its glyphs on verbs only, and stays well inside the dozen ADR-0019 caps it at', () => {
    expect(verbNames).toEqual(['edit', 'postpone', 'complete', 'cancel']);
  });
});

/** The tooltip is a property binding, so it is read off the directive rather than the DOM. */
function tooltipOf(fixture: { debugElement: import('@angular/core').DebugElement }): string {
  return fixture.debugElement.query(By.directive(MatTooltip)).injector.get(MatTooltip).message;
}

describe('GlyphButton', () => {
  it.each(verbNames)('gives %s an accessible name and a tooltip carrying it', async (verb) => {
    const fixture = TestBed.createComponent(GlyphButtonHost);
    fixture.componentInstance.verb = verb;
    await fixture.whenStable();

    const button = (fixture.nativeElement as HTMLElement).querySelector('button');
    expect(button?.getAttribute('aria-label')).toBe(VERBS[verb].name);
    expect(tooltipOf(fixture)).toBe(VERBS[verb].name);
  });

  it('lets the caller name the verb something longer, in both places at once', async () => {
    const fixture = TestBed.createComponent(GlyphButtonHost);
    fixture.componentInstance.label = 'I already did this';
    await fixture.whenStable();

    const button = (fixture.nativeElement as HTMLElement).querySelector('button');
    expect(button?.getAttribute('aria-label')).toBe('I already did this');
    expect(tooltipOf(fixture)).toBe('I already did this');
  });
});
