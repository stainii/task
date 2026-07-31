# task

A monorepo holding three projects:

- `task-back-end/` — Java / Spring Boot, built with Maven
- `task-front-end/` — Angular
- `task-workspace/` — the IntelliJ project config; this is the directory to open in the IDE

## Agent skills

### Issue tracker

Issues live as GitHub issues in `stainii/task`, driven via the `gh` CLI. External pull requests are also a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles use their canonical strings verbatim: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and one `docs/adr/` at the repo root, shared by back-end and front-end. See `docs/agents/domain.md`.
