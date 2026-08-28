# Firing fixtures

Shared golden fixtures for **when a template comes round**, required by
[ADR-0013](../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md) and built by
[#68](https://github.com/stainii/task/issues/68): **no firing rule without a fixture.**

The third of its kind, after [`/fold-fixtures/`](../fold-fixtures/README.md) and
[`/render-fixtures/`](../render-fixtures/README.md), and here for the same reason —
`docs/quality-bar.md` §5: _a third implementation of anything gets the same treatment. The test is
not "is this logic complicated" — it is "does this rule exist in more than one place"._

It exists in two now. The server asks _when did you last come round?_ to decide what to fire; the
authoring screen asks _when will you come round next?_ to print the dates under the rule it has just
read back as a sentence. **`every 14 weeks on Saturday` is legible as a sentence and still
unverifiable as one** — only the dates say whether the rule you typed is the rule you meant, which
is what ADR-0013 means by calling the scheduled case the strongest reason the preview exists at all.

A preview that quietly disagreed with the scheduler would be worse than no preview: it would show a
date no task will ever carry, and nothing would ever report it.

## What a fixture pins

**Trigger + `active_since` + the day the last task closed + a floor → the next dates, in order.**
That is the phase arithmetic, the _on or after_ boundary, day-of-month clamping, ISO week parity,
and each shape's answer _length_.

`lastClosedOn` is **the day the task was closed**, not the day it fired
([ADR-0022](../docs/adr/0022-a-min-max-round-starts-when-you-closed-it.md)). Fixture `03` is named
for that rule and, until [#75](https://github.com/stainii/task/issues/75), pinned the closure to the
same day as the floor — the one configuration where the two readings cannot disagree. **A fixture
that names a rule and cannot fail on it** is the shape
[#32](https://github.com/stainii/task/issues/32) already paid for once, so it is worth stating
plainly: give a fixture the value that distinguishes the rule from its nearest wrong neighbour, or
it pins nothing.

The three shapes answer with different lengths, and the difference is the model showing through
rather than an inconsistency:

- **`MANUAL` lists nothing.** Its dates come from an anchor someone types.
- **`MIN_MAX` lists exactly one**, however many are asked for. The round after the next one starts at
  a closure that has not happened, so a second date would be a guess dressed as a schedule — and
  drawing it as a schedule would draw it as the calendar it deliberately is not (ADR-0001).
- **`CALENDAR` lists as many as are asked for**, because a rule enumerates its own firings with
  nobody typing anything.

`MIN_MAX`'s one date **may lie before the floor**, and fixture `04` pins that: a template past its
round is already due, and _this fires on 11 March_ is true where a tidied-up future date would not
be. A calendar rule's dates never do.

## The contract

- One `*.json` file per case.
- **Both suites enumerate this directory dynamically** — a JUnit `@ParameterizedTest` over the files,
  vitest over the same glob. Adding a file adds a test on both sides, with nothing to register.
- **Both suites assert they ran a non-zero number of fixtures.** A path that silently matches nothing
  is how [#32](https://github.com/stainii/task/issues/32)'s pitest run spent four months measuring
  its own exclusion.
- **Both suites also assert that asking for zero dates gives none**, for every fixture. It is the one
  answer all three shapes have to agree on.

## The file format

```jsonc
{
  "rule": "the one sentence this fixture exists to pin",
  "trigger": {
    "type": "CALENDAR",
    "calendarRule": "WEEKS",
    "calendarInterval": 2,
    "calendarWeekdays": "TUESDAY,THURSDAY",
  }, // the flat wire shape
  "activeSince": "2026-03-04", // the floor of the enumeration, and the phase every rule counts from
  "lastClosedOn": null, // the day the last task was CLOSED; read by MIN_MAX alone, null when nothing has
  "from": "2026-03-04", // the date to look forward from — today, in the preview
  "count": 4,
  "expected": ["2026-03-05", "2026-03-17", "2026-03-19", "2026-03-31"],
}
```

`trigger` is the **flat wire shape**, for `/render-fixtures/`'s reason: it is the same JSON the API
sends, so a fixture is readable by the client with no second conversion to keep honest.

## The extra assertion the Java side makes

`FiringFixtureTest` also asserts, for every `CALENDAR` fixture, that **each listed date is a date the
scheduler would fire on** — `latestFiringDateOn` asked _on_ that date returns that date. So these
files do not only pin two implementations of a preview against each other: they pin the preview
against the code that creates real tasks, and a forward rule that drifted a day off its backward
mirror fails here rather than in the app.

## Status

**Both sides run them.**

- Java: `task-back-end/src/test/java/…/template/domain/FiringFixtureTest.java`, over
  `Trigger#nextFiringDates` and `CalendarRule#firstOccurrenceOnOrAfter`
- TypeScript: `task-front-end/src/app/domain/firing.fixtures.spec.ts`, over
  `task-front-end/src/app/domain/firing.ts`

Conventions: `docs/quality-bar.md`.
