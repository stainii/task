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

## Status

Empty. The fold does not exist yet on either side — it arrives with the backlog
([#11](https://github.com/stainii/task/issues/11)). The first ticket to write a fold rule writes
the first fixture here.

Conventions: `docs/quality-bar.md`.
