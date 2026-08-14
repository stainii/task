# task

A monorepo holding three projects:

- `task-back-end/` — Java / Spring Boot, built with Maven
- `task-front-end/` — Angular
- `task-workspace/` — the IntelliJ project config; this is the directory to open in the IDE

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
