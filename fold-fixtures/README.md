# Fold fixtures

Shared golden fixtures for the task patch fold, required by
[ADR-0004](../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md): **no fold rule without a
fixture.**

The fold exists twice — once in Java, once in TypeScript — and the two drifting apart would be
silent and would corrupt real data. These files are the single specification both implementations
are tested against.

## The contract

- One `*.json` file per case: the input patches, and the task they must fold to.
- **Both suites enumerate this directory dynamically** — a JUnit `@ParameterizedTest` over the
  files, vitest over the same glob. Adding a file adds a test on both sides, with nothing to
  register anywhere.
- **Both suites assert they ran a non-zero number of fixtures.** A path that silently matches
  nothing is how [#32](https://github.com/stainii/task/issues/32)'s pitest run spent four months
  measuring its own exclusion.

## The file format

```jsonc
{
  "rule": "the one sentence this fixture exists to pin",
  "taskId": "…",
  "patches": [ { "id": "…", "taskId": "…", "dateTime": "…Z", "sequence": 1, "voids": null, "changes": {} } ],
  "expected": { "id": "…", "name": "…", … }   // the whole task, every field, nulls included
}
```

Two things about `patches` are deliberate:

- **They are listed in arrival order, not fold order.** Several fixtures list them in an order the
  fold must ignore. The Java suite additionally folds every fixture reversed and rotated and
  requires the same task, because a fixture file can only ever list its patches in *some* order and
  arrival order is the one thing the fold may not depend on.
- **`sequence` is present and sometimes contradicts the date-times.** It is the server's delivery
  clock and the fold must never read it;
  [`13-sequence-never-orders-the-fold.json`](13-sequence-never-orders-the-fold.json) is the fixture
  that would catch it if one did.

`expected` names every field including the nulls, so a field the fold forgets to produce fails
here rather than in the app.

## Status

**Both sides run.** Written by [#45](https://github.com/stainii/task/issues/45) alongside the Java
fold; [#55](https://github.com/stainii/task/issues/55) added the TypeScript fold and pointed it at
the same files.

- Java: `task-back-end/src/test/java/…/task/domain/FoldFixtureTest.java`
- TypeScript: `task-front-end/src/app/domain/fold.fixtures.spec.ts`

The TypeScript runner enumerates this directory with `node:fs` at test time rather than importing
the files, so it stays outside the front-end's `tsconfig` and a new fixture needs no build change.
It asserts the same three things the Java runner does: the folded task field by field, one history
entry per distinct patch id, and the same result from the patches reversed and rotated.

Conventions: `docs/quality-bar.md`.
