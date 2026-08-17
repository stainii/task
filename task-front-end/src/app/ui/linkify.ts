/**
 * A description, split into the bits that are links and the bits that are not.
 *
 * **URLs only, and nothing else** (ADR-0015). Markdown was rejected in the same breath: it invites a
 * formatting toolbar, which invites an editor, and a task's *extra information* field is a place to
 * paste an order number and a link to the shop.
 *
 * Returned as runs rather than as a string of HTML, which is the whole security argument: nothing
 * here builds markup, so nothing can be injected through a description. The template renders a run
 * with an `href` as an anchor and everything else as text, and Angular escapes both.
 */
export interface TextRun {
  readonly text: string;
  /** Present exactly when this run is a link, and always equal to {@link text}. */
  readonly href?: string;
}

/**
 * `http` and `https`, and deliberately no others.
 *
 * A bare `www.example.com` is not linked because linking it means **guessing a scheme**, and the two
 * guesses are not equivalent. `mailto:` opens a mail client for a string that was only ever typed as
 * a note. And `javascript:` is why this is a list of two schemes rather than a search for a colon.
 */
const LINK = /https?:\/\/[^\s]+/g;

/** Punctuation a sentence ends with, which a URL at the end of one would otherwise swallow. */
const TRAILING = /[.,;:!?'"]+$/;

export function linkify(text: string): TextRun[] {
  const runs: TextRun[] = [];
  let cursor = 0;

  for (const match of text.matchAll(LINK)) {
    const href = trim(match[0]);
    const start = match.index;
    if (start > cursor) {
      runs.push({ text: text.slice(cursor, start) });
    }
    runs.push({ text: href, href });
    cursor = start + href.length;
  }

  if (cursor < text.length) {
    runs.push({ text: text.slice(cursor) });
  }
  return runs;
}

/**
 * Trims what the sentence owns off the end of the address.
 *
 * Brackets are **balanced rather than stripped**, because Wikipedia is the counter-example that
 * matters: `.../Boiler_(disambiguation)` ends in a `)` the URL itself opened, while `(see
 * https://example.com)` ends in one it did not. Counting is the only rule that gets both, and both
 * are ordinary things to paste into a note.
 */
function trim(candidate: string): string {
  let url = candidate.replace(TRAILING, '');
  while (url.endsWith(')') && count(url, ')') > count(url, '(')) {
    url = url.slice(0, -1).replace(TRAILING, '');
  }
  return url;
}

function count(text: string, character: string): number {
  return [...text].filter((one) => one === character).length;
}
