import { describe, expect, it } from 'vitest';

import { parseCursor } from './sync';

/**
 * The cursor is one string on the wire and two numbers in the client, and every failure of that
 * translation is silent: a cursor that reads as something the server did not say produces a stream
 * that looks healthy while skipping whatever it could not name.
 */
describe('parsing a cursor', () => {
  it('reads an `epoch:sequence` event id', () => {
    expect(parseCursor('3:41')).toEqual({ epoch: 3, sequence: 41 });
  });

  it.each([
    ['41', 'a bare sequence, which is a cursor with no lineage'],
    ['', 'nothing at all'],
    [
      '3:',
      'a missing sequence — and `Number("")` is 0, which would resume from the start of history',
    ],
    [':41', 'a missing epoch, for the same reason'],
    ['3:41x', 'a sequence that is not a number'],
    ['x:41', 'an epoch that is not a number'],
    ['3:41:9', 'more parts than the format has'],
  ])('refuses %s (%s)', (raw) => {
    expect(parseCursor(raw)).toBeNull();
  });
});
