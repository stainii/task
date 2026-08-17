# task

A monorepo holding three projects:

- `task-back-end/` — Java / Spring Boot, built with Maven
- `task-front-end/` — Angular
- `task-workspace/` — the IntelliJ project config; this is the directory to open in the IDE

## Running, debugging, restoring

How to get from a clean clone to a running app, the architecture in one page, what to do when the
usual things break, and where the decisions live: **`docs/operating-manual.md`**. It is the entry
point — start there rather than reconstructing any of it.

The decision log has an index: **`docs/adr/README.md`**. A new ADR must be listed in it, and
`DecisionLogIndexTest` fails the build if it is not.

## Quality bar

What "green" means, the gates on each stack, and the testing and code conventions every ticket is
built to: **`docs/quality-bar.md`**. Read it before writing code.

## Agent skills

### Issue tracker

Issues live as GitHub issues in `stainii/task`, driven via the `gh` CLI. External pull requests are also a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles use their canonical strings verbatim: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and one `docs/adr/` at the repo root, shared by back-end and front-end. See `docs/agents/domain.md`.

### Angular skills

`angular-developer` is installed and the front-end already follows it — there is no modernization backlog, and Signal Forms was considered for the task dialog and deliberately declined. Read before proposing either. See `docs/agents/angular-skills.md`.
