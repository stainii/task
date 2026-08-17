# Render fixtures

Shared golden fixtures for **task template rendering**, required by
[ADR-0011](../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md): **no rendering rule
without a fixture.**

The sibling of [`/fold-fixtures/`](../fold-fixtures/README.md) and
[`/firing-fixtures/`](../firing-fixtures/README.md), and here for the same reason as both of them.
The server and the front-end each render a template: the server when a template fires, the front-end
when it shows you what running a template is about to create, and when *"I already did this"* mints a
task offline. Two implementations of one rule drifting apart would be silent, and would put a wrong date
on a real task.

## What a fixture pins

A rendering: **template + firing → the tasks it describes.** That is `${…}` substitution, the anchor
arithmetic, the trigger's fallback due date, and the two failures that must produce *no* tasks rather
than some.

It stops at the description. Turning one into a `Task` is `task`'s business and it is not rendering.

## The contract

- One `*.json` file per case.
- **Both suites enumerate this directory dynamically** — a JUnit `@ParameterizedTest` over the files,
  vitest over the same glob. Adding a file adds a test on both sides, with nothing to register.
- **Both suites assert they ran a non-zero number of fixtures.** A path that silently matches nothing
  is how [#32](https://github.com/stainii/task/issues/32)'s pitest run spent four months measuring
  its own exclusion.

## The file format

```jsonc
{
  "rule": "the one sentence this fixture exists to pin",
  "template": {
    "name": "…",
    "context": "…",                 // may contain ${…}
    "activeSince": "2026-03-01",
    "trigger": { "type": "MIN_MAX", "minDays": 10, "maxDays": 13 },   // the flat wire shape
    "taskDefinitions": [
      { "name": "…", "description": null, "startDateOffsetDays": -14,
        "dueDateOffsetDays": -7, "importance": "IMPORTANT" }          // null importance means default
    ]
  },
  "firing": {
    "firingDate": "2026-03-02",     // the date the template came round
    "anchor": "2026-04-15",         // what the offsets are measured from; null for a manual run without one
    "variables": { "school": "Sint-Jan" }
  },
  "expected": {
    "context": "…",
    "definitions": [ { "name": "…", "description": null, "importance": "IMPORTANT",
                       "startDate": "…", "dueDate": null } ]          // every field, nulls included
  }
}
```

Three things about it are deliberate:

- **`trigger` is the flat wire shape**, the same JSON the API sends, so a fixture is readable by the
  client without a second conversion to keep honest.
- **The fallback due date is not in the file.** It is asked of the trigger, exactly as the real code
  does — a fixture that stated it would let the two disagree and still pass.
- **`expected` names every field including the nulls**, so a field the renderer forgets to produce
  fails here rather than in the app.

A fixture that pins a *refusal* replaces `expected` with `"expectedError"`, holding a fragment of the
message. Two of them exist, and they are the point of TODO-022: a template that cannot render one of
its tasks produces **none**, where portal produced a task called `"No name"`.

## Status

**Both sides now run them.** Written by [#50](https://github.com/stainii/task/issues/50) alongside
the template API, and joined by the TypeScript half in
[#61](https://github.com/stainii/task/issues/61) — which is the half that made the duplication real:
the front-end previews what running a template will create, and mints the task for *"I already did
this"* offline, where no server call is possible.

- Java: `task-back-end/src/test/java/…/template/domain/RenderFixtureTest.java`
- TypeScript: `task-front-end/src/app/domain/render.fixtures.spec.ts`, over
  `task-front-end/src/app/domain/render.ts`

Both enumerate this directory dynamically and both assert a non-zero count, so **adding a file here
adds a test on both sides with nothing to register** — and a fixture only one of them can satisfy
fails on the other.

Conventions: `docs/quality-bar.md`.
