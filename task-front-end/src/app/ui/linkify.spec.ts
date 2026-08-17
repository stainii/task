import { describe, expect, it } from 'vitest';

import { linkify } from './linkify';

/**
 * *Descriptions linkify URLs, and nothing else* (ADR-0015). The *nothing else* is the decision:
 * Markdown invites a formatting toolbar, which invites an editor.
 */
describe('linkify', () => {
  it('leaves text with no link in it as one plain run', () => {
    expect(linkify('Ring the plumber about the boiler')).toEqual([
      { text: 'Ring the plumber about the boiler' },
    ]);
  });

  it('splits a link out of the words around it', () => {
    expect(linkify('Order from https://example.com/parts today')).toEqual([
      { text: 'Order from ' },
      { text: 'https://example.com/parts', href: 'https://example.com/parts' },
      { text: ' today' },
    ]);
  });

  it('finds every link, not just the first', () => {
    expect(
      linkify('http://a.example http://b.example').filter((run) => 'href' in run),
    ).toHaveLength(2);
  });

  it('leaves a sentence’s full stop out of the link', () => {
    // A URL at the end of a sentence is the common case, and a trailing `.` in the href is a 404
    // that looks like the app mangled the address — which, having typed it correctly, you did not.
    expect(linkify('See https://example.com/parts.')).toEqual([
      { text: 'See ' },
      { text: 'https://example.com/parts', href: 'https://example.com/parts' },
      { text: '.' },
    ]);
  });

  it('keeps a closing bracket that the link opened', () => {
    expect(linkify('https://en.wikipedia.org/wiki/Boiler_(disambiguation)')[0]).toEqual({
      text: 'https://en.wikipedia.org/wiki/Boiler_(disambiguation)',
      href: 'https://en.wikipedia.org/wiki/Boiler_(disambiguation)',
    });
  });

  it('drops a bracket the link did not open', () => {
    expect(linkify('(see https://example.com/parts)')).toEqual([
      { text: '(see ' },
      { text: 'https://example.com/parts', href: 'https://example.com/parts' },
      { text: ')' },
    ]);
  });

  it('links nothing that is not http or https', () => {
    // Nothing else, and that includes the schemes a description could plausibly carry. `javascript:`
    // is the reason this is a list of two rather than a check for a colon.
    expect(linkify('javascript:alert(1) and mailto:me@example.com and www.example.com')).toEqual([
      { text: 'javascript:alert(1) and mailto:me@example.com and www.example.com' },
    ]);
  });
});
