import { describe, expect, it } from 'vitest';

import { addDays, daysUntil, maxIso, today } from './dates';

/**
 * These tests only mean anything in a zone that has daylight saving, which is why the `test` script
 * pins `TZ=Europe/Brussels` — the same zone as the back-end's `task.time-zone`. Under `TZ=UTC` the
 * arithmetic ADR-0019 warns about (`new Date(t + n * 86400000)`) passes every one of them, so the
 * zone is the test rather than a detail of it.
 */

/** The CET→CEST boundary: 2026-03-29 is 23 hours long. */
const SPRING_FORWARD = '2026-03-29';

/** The CEST→CET boundary: 2026-10-25 is 25 hours long. */
const FALL_BACK = '2026-10-25';

describe('today', () => {
  it('is the calendar date the clock is standing on, not a UTC one', () => {
    // 00:30 Brussels on 1 March is still 23:30 UTC on 28 February. A client that read UTC would
    // spend an hour every night in yesterday, and the bands would move with it.
    expect(today(new Date('2026-02-28T23:30:00Z'))).toBe('2026-03-01');
  });
});

describe('addDays', () => {
  it('adds calendar days, not milliseconds, across the spring-forward boundary', () => {
    expect(addDays(SPRING_FORWARD, 1)).toBe('2026-03-30');
  });

  it('adds calendar days across the autumn boundary', () => {
    expect(addDays(FALL_BACK, 1)).toBe('2026-10-26');
  });

  // The intervals #47's canary uses on the back end, for the same reason: 45 days of fixed
  // milliseconds across a boundary lands at 23:00 the day before, and reads as 44.
  it.each([
    [1, '2026-03-30'],
    [3, '2026-04-01'],
    [7, '2026-04-05'],
    [30, '2026-04-28'],
    [31, '2026-04-29'],
    [45, '2026-05-13'],
    [90, '2026-06-27'],
    [365, '2027-03-29'],
  ])('adds %i days to the boundary date', (days, expected) => {
    expect(addDays(SPRING_FORWARD, days)).toBe(expected);
  });

  it('goes back across the boundary without losing a day', () => {
    // The discriminating direction. Local midnight on 30 March is 22:00 UTC on the 29th; take a
    // fixed 24 hours off it and you land at 23:00 on the **28th**, one day short. Forwards the same
    // arithmetic survives, because midnight plus an hour is still the same date — which is exactly
    // why a test that only goes forwards proves nothing.
    expect(addDays('2026-03-30', -1)).toBe('2026-03-29');
    expect(addDays('2026-10-26', -1)).toBe('2026-10-25');
  });

  it('crosses month and year ends', () => {
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2024-02-28', 1)).toBe('2024-02-29');
  });
});

describe('daysUntil', () => {
  it('counts whole calendar days, so a task due today is due in zero', () => {
    expect(daysUntil('2026-03-29', '2026-03-29')).toBe(0);
  });

  it('counts a day across the short day as one, not as zero', () => {
    // The 23-hour day. `Math.floor((b - a) / 86400000)` gives 0 here, which is how a task due
    // tomorrow reads as due today and sorts into the wrong band.
    expect(daysUntil('2026-03-28', '2026-03-29')).toBe(1);
  });

  it('counts a day across the long day as one, not as two', () => {
    expect(daysUntil('2026-10-24', '2026-10-25')).toBe(1);
  });

  it('spans the boundary without losing or gaining a day', () => {
    // 28 to 30 March is 47 hours of wall clock. A count built on elapsed milliseconds floors that
    // to **1**, so a task due in two days reports as due tomorrow — and on the other boundary 49
    // hours reads as 3. This is the pair that catches the arithmetic ADR-0019 found.
    expect(daysUntil('2026-03-28', '2026-03-30')).toBe(2);
    expect(daysUntil('2026-10-24', '2026-10-26')).toBe(2);
  });

  it('is negative for a date in the past, by whole days', () => {
    expect(daysUntil('2026-03-29', '2026-03-27')).toBe(-2);
  });
});

describe('maxIso', () => {
  it('returns the later of two dates', () => {
    expect(maxIso('2024-06-07', '2026-08-13')).toBe('2026-08-13');
    expect(maxIso('2026-08-13', '2024-06-07')).toBe('2026-08-13');
  });

  it('lets a real date win over null, whichever side it is on', () => {
    // The floor #88 needs: a server value the client has pruned past, or a fresh local completion
    // the server has not heard of yet — either one beats the other's absence.
    expect(maxIso('2026-08-13', null)).toBe('2026-08-13');
    expect(maxIso(null, '2026-08-13')).toBe('2026-08-13');
  });

  it('is null only when both sides are', () => {
    expect(maxIso(null, null)).toBeNull();
  });
});
