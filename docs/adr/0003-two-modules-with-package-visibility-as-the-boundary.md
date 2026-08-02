# 3. Two modules, with package visibility as the boundary

Date: 2026-08-02

## Status

Accepted. Resolves [#6](https://github.com/stainii/task/issues/6).

Amends [ADR-0002](0002-one-application-event-published-as-a-fact.md) on one point — see
*Events are preferred, not mandatory*.

## Context

`task-back-end`'s package layout was inherited from portal's service split rather than chosen.
Spring Modulith infers one application module per direct subpackage of the application package, so
it currently sees six: `task`, `template`, `recurring`, `goal`, `config`, `utils`.

Nothing about that layout had ever been asserted. There were **no `@ApplicationModule`
declarations and no `@NamedInterface`s** anywhere — every `package-info.java` carried only
`@NullMarked` — so `ApplicationModules.verify()` passed while checking essentially nothing. This was
noted as a consequence of [ADR-0002](0002-one-application-event-published-as-a-fact.md).

The actual cross-package arrows in `main` were only four:

| from | to | what |
| --- | --- | --- |
| `recurring` | `task` | `Importance`, `Task`, `TaskCreationRequestedEvent` |
| `template` | `task` | `Importance`, `Task`, `TaskCreationRequestedEvent` |
| `task` | `utils` | `DateTimeUtils`, `ObjectUtils` |
| `template` | `utils` | `DateTimeUtils`, `VariableUtils` |

Two earlier decisions constrain the answer. [ADR-0001](0001-one-task-aggregate-with-triggered-templates.md)
merges `RecurringTaskTemplate` into `TaskTemplate`, so `recurring` and `template` become one module
by construction. [ADR-0002](0002-one-application-event-published-as-a-fact.md) put the
`TaskTemplateFired` type in `task` as an inbound port, on the reasoning that `template` must read
`task` and the graph must stay acyclic.

## Decision

**Four modules: `task`, `template`, `config`, `goal`.**

`task` and `template` are two modules because they are **two aggregates**. `recurring` dissolves
into `template` per ADR-0001. `goal` stays as an empty bookmark package
([#4](https://github.com/stainii/task/issues/4)). `config` survives as the one genuinely app-wide
module: nothing imports it, it is pure Spring wiring, and both module tests already pull it in via
`@ApplicationModuleTest(extraIncludes = "config")`.

**`utils` is dissolved**, because it was never shared. Every helper in it had exactly one consuming
module:

| helper | went to |
| --- | --- |
| `ObjectUtils.getAllFieldsAndTheirValues` | `task.util` (patch diffing, called from `Task`) |
| `DateTimeUtils.parseAs*` | `task.util` (patch values arrive as untyped strings) |
| `DateTimeUtils.addDaysTo` | `template.util` (task-definition day offsets) |
| `VariableUtils.fillInVariables` | `template.util` (`${variable}` substitution) |

It was a package named after a layer, not a cross-cutting concern, and it was the only reason `task`
had an outbound module dependency at all. A `shared` module can be minted later, for a real reason.

**Events are preferred, not mandatory.** ADR-0002 reads as though the event seam were the only
permitted way for modules to talk. It is not, and the distinction is mechanical rather than a matter
of taste: **an event cannot return a value.**

- **Queries go direct** — `template` calls `task`'s exposed API and waits for an answer.
- **Facts go by event** — `template` publishes, and nobody is waiting.

This was always implied: ADR-0002 itself argued the type belongs in `task` *because* `template` must
read `task`. The direct read is not an escape hatch from the event style; it is the half of the
traffic the event style never covered.

**`task` exposes a purpose-built query port.** `template` asks `task` exactly two things — *when was
this template's last occurrence completed?* and *does it have an open occurrence?* — both of which
ADR-0001 made derivable only from tasks and their patch history. `task` publishes them as a narrow
interface (`TaskOccurrences`), implemented internally over the repository and the patch fold.

`task` therefore has `templateId` and `occurrenceId` in its exposed vocabulary. Those are UUID
columns on `Task` already, not a code dependency on `template`, so no arrow is created.

**The exposed API of a module is its base package**, using Modulith's default convention. No
`@NamedInterface`; with one consumer relationship, multiple published interfaces per module would be
expressiveness nobody needs, and it keeps the boundary visible in the file tree instead of hidden in
a `package-info`.

`task`'s base package therefore shrinks to `TaskOccurrences`, `TaskTemplateFired` and `Importance`.
Aggregates move down into `<module>/domain/` — `Task`, `TaskPatch`, `TaskStatus` and
`TaskTemplate`, `TaskDefinition` and friends. The existing layer subpackages (`controller`,
`service`, `repository`, `dto`, `mapper`, `exception`) stay exactly where they are; this is the
smallest change that makes the packages tell the truth.

**`verify()` rides on Modulith's defaults** — no cycles, no reaching into another module's
internals. `@ApplicationModule(allowedDependencies = ...)` was considered and rejected: the one
arrow that matters, `task → template`, is already impossible because `template → task` exists and it
would close a cycle. The teeth come from the package moves, not from an annotation.

**Generated module documentation is committed** to `docs/modules/` rather than left in `target/`.
GitHub renders the `.puml` and `.adoc` files, so a change that quietly adds an arrow between modules
shows up as a diff — a review signal worth more than usual here, given there is no CI yet
([#23](https://github.com/stainii/task/issues/23)) and no second reviewer.

**Contexts are nothing.** Social, housagotchi, setlist and health are not modules and not
configuration. ADR-0001 made **context** a label on a task with no behaviour, so there is nothing to
put in a module.

## Consequences

- **`verify()` gains something to verify.** Once the aggregates move down, `template` importing
  `Task` is a build failure rather than a design opinion.
- **The aggregate moves are deferred, deliberately.** `Task` cannot become internal today:
  `TaskTemplateService` and `CreateDueTasks` both *construct* a `Task` and hand it over inside
  `TaskCreationRequestedEvent(List<Task>)`. It becomes internal only when ADR-0002's payload change
  lands — the event carrying rendered definitions instead of a pre-built aggregate. Rather than move
  half the types now and half later, all of `<module>/domain/` moves in one edit alongside the
  ADR-0001 model rewrite. Backlog work for [#11](https://github.com/stainii/task/issues/11).
- **`recurring`'s dissolution is the same edit.** It cannot be dissolved before
  `RecurringTaskTemplate` is absorbed into `TaskTemplate` with its `Trigger`.
- **`utils` is already gone**, and the module documentation in `docs/modules/` is already real. Both
  were free of the model rewrite.
- **`Importance` stays owned by `task`** and is imported by `template`. With no `allowedDependencies`
  declared, that arrow needs no ceremony; if a second shared enum ever appears, that is the moment to
  reconsider a shared module rather than now.
- **The helpers can now specialise to their callers.** `parseAsLocalDate`'s deliberately loose ISO
  formatter exists because patch values are untyped strings — a `task` concern, not a universal one.
  They were moved verbatim, so no behaviour changed; the freedom is newly available, not exercised.
- **`utils` had no tests at all**, which the move made visible. Not fixed here; noted for
  [#10](https://github.com/stainii/task/issues/10).
- **A committed generated artifact can go stale** if someone commits without running tests. Fixable
  as a CI check once [#23](https://github.com/stainii/task/issues/23) lands.

## Alternatives considered

- **One module.** With ~2000 lines and one author, no team boundary corresponds to the split.
  Rejected: `task` and `template` are two aggregates, and collapsing them would delete
  `TaskTemplateFired`'s reason to exist and reopen ADR-0002 on day one.
- **Three or more modules** — splitting firing out of `template`, or the patch/SSE machinery out of
  `task`. Rejected: more seams to police than there are things to police.
- **Keeping `utils` as a shared module.** Rejected: nothing in it was shared, and a package named for
  a layer accumulates. Portal's `portal-model` is on this map's out-of-scope list for the same reason.
- **Dissolving `config` into the application root package**, where Modulith treats types as belonging
  to no module. Rejected: clutters the root with security and JDBC wiring, and loses one place to
  point at and call infrastructure.
- **`@NamedInterface` plus `allowedDependencies`.** Rejected as ceremony for a two-module system, and
  because it leaves `Task` sitting in the base package still looking public.
- **`@ApplicationModule(allowedDependencies = {})` on `task` alone.** Tempting — the annotation's
  default is a literal shrug sentinel, so `{}` really does assert "depends on nothing". Rejected:
  the cycle rule already prevents the violation, and the assertion would restate the ADR rather than
  catch anything.
- **Enacting the whole layout now**, carrying `RecurringTaskTemplate` over as-is. Rejected: a large
  refactor of code ADR-0001 has already condemned.
- **Deleting the `Documenter` call.** Rejected: the diagram is the cheapest way to see an unwanted
  arrow appear.
