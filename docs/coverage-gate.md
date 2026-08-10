# Coverage gate: the traceability table

**Date**: 2026-08-10
**Ticket**: [#16](https://github.com/stainii/task/issues/16) — the paper half. The shipped half runs before [#17](https://github.com/stainii/task/issues/17).
**Inputs**: [`docs/portal-inventory.md`](portal-inventory.md) (#3), the four triage tickets ([#12](https://github.com/stainii/task/issues/12), [#13](https://github.com/stainii/task/issues/13), [#14](https://github.com/stainii/task/issues/14), [#15](https://github.com/stainii/task/issues/15)), the backlog [#44](https://github.com/stainii/task/issues/44)–[#65](https://github.com/stainii/task/issues/65) (#11), and [`docs/repo-health.md`](repo-health.md) (#18).

> This table is the artefact that lets the old portal be switched off knowing what was signed up to lose. It is not a summary — it is the check, written down.

## What this gate is, and is not

Three of #16's four checks run here, **before** the backlog is built, because that is when a gap is cheap to close. #11 found REC-012 exactly this way. The fourth — **shipped coverage**, every survivor closed and working — cannot run until #44–#65 are done, and is [#66](https://github.com/stainii/task/issues/66).

## Results

| Check | Result |
|---|---|
| **1. Verdict coverage** — every row has a keep/transform/drop verdict | **PASS**, 142 / 142. No orphans. |
| **2. Build coverage** — every survivor reaches the backlog, and no backlog issue is invented | **PASS**. 77 of 82 survivors are cited by an issue in #44–#65; 5 are discharged through existing tickets. All 22 backlog issues cite at least one row. |
| **3. Substance coverage** — the verdict is still *true* | **PASS with one correction**: REC-002. Six rows carry stale wording; one had a stale verdict. |
| **4. Health-check coverage** — every defect in `docs/repo-health.md` has a disposition | **PASS**, 8 / 8. Two are decisions not to fix. |
| **5. Shipped coverage** | **NOT RUN** — [#66](https://github.com/stainii/task/issues/66). |

### The tally has moved twice

| | Survivors | Drops |
|---|---|---|
| As triaged by #12–#15 | 84 | 58 |
| After ADR-0016 flipped REC-018 (#40) | 83 | 59 |
| After this gate flipped TODO-021 | **82** | **60** |

#11's *83 survivors* was correct when written and is now one out of date. The count is not a constant, which is the argument for check 3 existing at all.

## Check 3: what the substance check found

The two counting checks pass and have found one thing between them in the map's life — REC-012. The staleness problem has found six, every one by accident. So check 3 was added here rather than left as a stated limit.

**The staleness is not random rot. It clusters on one ADR.**

[ADR-0001](adr/0001-one-task-aggregate-with-triggered-templates.md) deleted **`Execution`** and **`activeTask`**, and six of #13's verdicts are worded around them: **REC-002, REC-004, REC-007, REC-010, REC-014** and **SOC-004**. Five carry correctly — their substance survives, only the mechanism named in the verdict is gone, and the citing backlog issue says so.

**REC-002 does not.** Its verdict is *keep as landed — the full execution log stays*, and the execution log does not stay. The capability survives, as completed tasks carrying `completedOn`, which is a **transform**. The verdict also says *D6 … carried as-is*, which HEALTH-06 directly contradicts (`addExecution` dies with `Execution`). Corrected in the table below and in the ledger.

That reframes what a future gate should look for: **staleness is caused by a design ticket deleting a concept, so it is predictable.** The check is not *re-read 142 rows* — it is *when an ADR deletes a named thing, grep the ledger and the triage tables for that name*. `Execution`, `activeTask`, `DeviationBase`, `variableNames`, `flowId`, `expectedDurationInHours` and `Source` are the seven deletions this map has made; all seven are now accounted for.

Of the earlier five instances, four were caught because the design ticket noticed and said so — the mechanism that works. One, REC-003, was not: the explicit start date went missing for two months in ADR-0001's restructure and came back only because #41 needed a calendar floor and rebuilt it as `active_since`. That is the cost this check exists to avoid, and it is the same ADR.

## The drop list, in one place

**60 rows are dropped.** Dropping should be a decision you can see and disagree with, not something that happens by omission. Grouped by why:

- **The subscription machinery** (10) — TODO-007, TODO-027…035, plus FE-012's UI. Every event producer is out of scope, so user-authored SpEL over a bus has nothing to fire it. Replaced by Spring Modulith application events written only where a listener exists ([ADR-0002](adr/0002-one-application-event-published-as-a-fact.md)).
- **The RabbitMQ wire and its payloads** (10) — TODO-037…041, REC-008, REC-009, REC-017, CON-001, RES-008. One deployable needs no bus.
- **`portal-social`** (11) — SOC-001…003, SOC-005…012. `Person` minus photos, minus notes, minus a cross-database join is a **name**, which a template already has. A person becomes a recurring template in the `social` context.
- **The gamification layer and the skins** (8) — FE-017…024. Computed client-side from min/max, so a creature can return later as a pure view with no schema change. Housagotchi's *due tasks* half needs nothing built: it is *a task exists* and *it is overdue*, both already on the overview.
- **Deleted model concepts** (4) — TODO-021 `DeviationBase`, TODO-044 the zone-guessing deserializer, REC-018 the cron property, FE-026 the module menu.
- **Build, test and deploy residue** (17) — RES-002…005, RES-008, RES-009, RES-012, RES-014, RES-015, TST-001…004, CON-003…005, RES-010. Portal's test suites drop because the fold's real spec is ADR-0004's shared golden fixtures; `portal-email` drops as a service, and the requirement it served is ADR-0012's push.

Two drops are worth re-reading before cutover because they are the ones that lose a *capability* rather than an implementation: **SOC-005** (photos — social loses them permanently) and **FE-017/FE-020** (the creature and Sporty Spice — years of daily use, dropped on the author's call).

## Check 4: health-check dispositions

`docs/repo-health.md`'s defects have no ledger row, so #11 gave them their own prefix. Every one has a disposition; two are decisions not to fix.

| Id | Defect | Disposition |
|---|---|---|
| HEALTH-01 | **D1** `Period.getDays()` — a template with `min > 30` never fires | #47 (the class is deleted), #49 (the predicate is replaced) |
| HEALTH-02 | **D2** `Task.patch()` re-appends on replay | #45 — the sorted fold replaces it |
| HEALTH-03 | **D3** patch ids reachable only over SSE | #45 (write half), #46 (read half) |
| HEALTH-04 | **D4** `RecurringTaskTemplateController` inconsistent | #47 (model), #50 (surface) |
| HEALTH-05 | **D5** null `changes` yields 500, no `@ControllerAdvice` | #46 |
| HEALTH-06 | **D6** the `addExecution` copy-on-write workaround | **No ticket, by decision** — it dies with `Execution`. Recorded on #47 |
| HEALTH-07 | **D7** the module diagram reorders itself | #48 — sort the `Documenter` output; #23 may then add the CI gate it had to leave out |
| HEALTH-08 | **F1–F3** front-end wiring defects | **No ticket, by decision** — resolved by deletion in #30, kept verbatim in `docs/repo-health.md` as the evidence that discard was right |

**D1 is the one that matters most before #17**: it fails silently — the task simply never appears. It is covered twice (#47 deletes the class, #49 replaces the predicate) and #47's *done when* names the canary: a template with `min > 30` days fires.

The `HEALTH-nn` ids lived only in issue prose until now; `docs/repo-health.md` has been annotated with them so the two documents can be read against each other.

## The five survivors discharged outside the backlog

Not omissions — each is owned by a ticket that predates #11.

| Row | Owner | Why it is not a backlog issue |
|---|---|---|
| CON-006 | #22, #28 | Keycloak is shared infrastructure; the realm decision is ADR-0010's, not a build task |
| RES-001 | #24 | The Dockerfiles are continuous deployment's, and #24 is `wayfinder:deferred` |
| RES-006 | #29 | The server-setup notes become the operating manual |
| RES-007 | #27, #28 | Split: what is *scraped* is ADR-0009's, what is *exposed* is ADR-0010's |
| RES-011 | #15 | Read and discharged inside its own triage ticket; the eight findings are folded into the ledger |

## The traceability table

`Carried by` names the backlog issue(s) that cite the row, or the ticket that discharged it. `—` means the row is dropped and needs no home. `Substance` is empty where the verdict is still true as written.

### `portal-todo` — TODO-001…048

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| TODO-001 | keep | #45 |  |
| TODO-002 | keep | #45 |  |
| TODO-003 | keep | #45 |  |
| TODO-004 | transform | #45 |  |
| TODO-005 | keep → **transform** | #45 | **Superseded.** #12 kept undo as a *compensating forward patch*; ADR-0004 makes it a **void patch**. Amendment recorded on #7 and on the map. |
| TODO-006 | transform | #45 |  |
| TODO-007 | drop | — |  |
| TODO-008 | keep | #46 | Narrowed by ADR-0004 to first-run and hard-reset only; stated in #46. |
| TODO-009 | transform | #45, #46 | `POST /api/tasks` is deleted outright by ADR-0004 — the first patch creates. Client-minted ids survive as the substance. |
| TODO-010 | keep | #48, #50 |  |
| TODO-011 | keep / transform ids | #45, #46 |  |
| TODO-012 | keep | #46 | `?since=` becomes a **sequence**, not a timestamp (ADR-0004). The capability is the substance; the key changed. |
| TODO-013 | keep transport / transform reconnect | #46 |  |
| TODO-014 | keep / transform DTO | #46 | `DELETE /api/task-patches/{id}` is deleted; undo is a void patch (ADR-0004). Same amendment as TODO-005. |
| TODO-015 | keep `findByStatus` / drop flowId query | #46 |  |
| TODO-016 | keep | #46 |  |
| TODO-017 | keep services / drop `Source` | #46 |  |
| TODO-018 | keep | #46 |  |
| TODO-019 | keep | #47 | **Partly overturned.** The `variableNames` half is deleted by ADR-0013 (inferred from `${…}`). Recorded on #47 and in #11's resolution. |
| TODO-020 | keep | #47 |  |
| TODO-021 | keep → **drop** | #47 | **Overturned.** `DeviationBase` is deleted by ADR-0013 — one anchor, two offsets. Recorded on #47 and in #11's resolution. |
| TODO-022 | keep | #47, #50 |  |
| TODO-023 | keep | #47 |  |
| TODO-024 | keep | #50 | *Keep as landed* is now a **rewrite**: D4's four fixes plus deactivate-not-delete (ADR-0013). Visible in #50; the row's wording is stale. |
| TODO-025 | keep | #47 |  |
| TODO-026 | keep | #47 |  |
| TODO-027 | drop | — |  |
| TODO-028 | drop | — |  |
| TODO-029 | drop | — |  |
| TODO-030 | drop | — |  |
| TODO-031 | drop | — |  |
| TODO-032 | drop | — |  |
| TODO-033 | drop | — |  |
| TODO-034 | drop | — |  |
| TODO-035 | drop | — |  |
| TODO-036 | keep | #45 |  |
| TODO-037 | drop | #48 |  |
| TODO-038 | drop | — |  |
| TODO-039 | drop | — |  |
| TODO-040 | drop | — |  |
| TODO-041 | drop | — |  |
| TODO-042 | transform | #44 |  |
| TODO-043 | transform | #44 |  |
| TODO-044 | drop | #44 |  |
| TODO-045 | keep | #44 |  |
| TODO-046 | keep | #45 |  |
| TODO-047 | keep | #46 |  |
| TODO-048 | keep | #46 |  |

### `portal-recurring-tasks` — REC-001…018

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| REC-001 | keep | #47 | Absorbed into `TaskTemplate` as the `MinMax` trigger (ADR-0001). |
| REC-002 | keep → **transform** | #47 | **STALE — the finding of this gate.** The verdict keeps *the full execution log*; ADR-0001 **deletes `Execution`**. This is a **transform**, not a keep: the capability survives as completed tasks carrying `completedOn`. Its clause *D6 carried as-is* is also contradicted by HEALTH-06. |
| REC-003 | transform | #47 | Lost in ADR-0001's restructure, recovered by ADR-0017 as **`active_since`**. Found by accident in #41. |
| REC-004 | transform | #50 | Three of four fixes carry. The fourth — *`Execution` ids move client-side* — is **void** (`Execution` deleted); its requirement survives as ADR-0011's client-minted completion in #60, not in #50. |
| REC-005 | keep endpoint → **transform** | #47, #50, #52 | **Overturned.** No successor endpoint: ADR-0011 makes it a client affordance. Recorded in #8 and corrected in the ledger. |
| REC-006 | transform | #47, #49 |  |
| REC-007 | keep as requirement | #49 | `Execution` and `activeTask` are both deleted. Reframed by ADR-0011: completion is a task fact the template **reads**. Substance intact, wording stale. |
| REC-008 | drop | — |  |
| REC-009 | drop | — |  |
| REC-010 | keep | #47, #49 | The `activeTask` guard named in the verdict is deleted; ADR-0017's *no open task* predicate does the same job. |
| REC-011 | transform | #47, #52 |  |
| REC-012 | keep | #47 | #11's one genuine build-coverage omission, found mechanically and homed on #47. |
| REC-013 | keep | #48, #50 |  |
| REC-014 | keep minus `source` | #47 | `ExecutionDto` dies with `Execution` (ADR-0001); the start-date field it *gains* is now `active_since`. |
| REC-015 | keep | #47 |  |
| REC-016 | keep | #44 |  |
| REC-017 | drop | — |  |
| REC-018 | keep → **drop** | #47, #49 | **Overturned** to `drop` by ADR-0016 — the cron becomes an hourly `fixedDelay` constant. Recorded in #40 and corrected in the ledger. |

### `portal-social` — SOC-001…012

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| SOC-001 | drop | — |  |
| SOC-002 | drop | — |  |
| SOC-003 | drop | — |  |
| SOC-004 | transform | #50 | Points at *the generic REC-005 execution*, which no longer exists as an endpoint (ADR-0011). Carried by #50 + #60, not by the row's own pointer. |
| SOC-005 | drop | — |  |
| SOC-006 | drop | — |  |
| SOC-007 | drop | — |  |
| SOC-008 | drop | — |  |
| SOC-009 | drop | — |  |
| SOC-010 | drop | — |  |
| SOC-011 | drop | — |  |
| SOC-012 | drop | — |  |

### `portal-front-end` — FE-001…034

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| FE-001 | transform | #55 |  |
| FE-002 | keep | #56 |  |
| FE-003 | transform | #55 |  |
| FE-004 | transform | #57 | Recovered by ADR-0018 from `task.comparator.ts`: adopted whole minus the `expectedDurationInHours` term. The row's `MISSING` status was itself an artefact of that dead field. |
| FE-005 | keep | #54, #57 | The `goals` bucket is renamed **`long-game`** (ADR-0006) so the UI stops squatting on a reserved term. |
| FE-006 | transform | #57 |  |
| FE-007 | transform | #59 |  |
| FE-008 | keep | #57 |  |
| FE-009 | transform | #56 |  |
| FE-010 | transform | #61 |  |
| FE-011 | transform | #60, #61 |  |
| FE-012 | drop | — |  |
| FE-013 | transform | #55 |  |
| FE-014 | transform | #54, #58 |  |
| FE-015 | transform | #61 |  |
| FE-016 | transform | #60, #61 |  |
| FE-017 | drop | — |  |
| FE-018 | drop | — |  |
| FE-019 | drop | — |  |
| FE-020 | drop | — |  |
| FE-021 | drop | — |  |
| FE-022 | drop | — |  |
| FE-023 | drop | — |  |
| FE-024 | drop | — |  |
| FE-025 | transform | #54 |  |
| FE-026 | drop | #54 |  |
| FE-027 | keep | #58 |  |
| FE-028 | transform | #56 |  |
| FE-029 | keep | #56 | ADR-0014 locates it: a band on the overview (#58), not behind `⋯`. |
| FE-030 | transform | #54, #56, #63 | The profile half shrinks to a single log-out item (ADR-0014). |
| FE-031 | transform | #54 |  |
| FE-032 | transform | #54 |  |
| FE-033 | transform | #55 |  |
| FE-034 | keep | #65 |  |

### Database schemas — DB-001…005

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| DB-001 | transform | #52, #53 |  |
| DB-002 | transform | #52, #53 |  |
| DB-003 | transform | #52, #53 |  |
| DB-004 | transform | #52, #53 |  |
| DB-005 | keep | #45 |  |

### Cross-service contracts — CON-001…006

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| CON-001 | drop | — |  |
| CON-002 | transform | #52 |  |
| CON-003 | drop | — |  |
| CON-004 | drop | — |  |
| CON-005 | drop | — |  |
| CON-006 | transform | #22, #28 | Discharged outside the backlog: neutral realm with `task` as one client, hardened in ADR-0010. |

### Tests — TST-001…004

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| TST-001 | drop | — |  |
| TST-002 | drop | — |  |
| TST-003 | drop | — |  |
| TST-004 | drop | #64 |  |

### Residual — RES-001…015

| Row | Verdict | Carried by | Substance |
|---|---|---|---|
| RES-001 | transform | #24 | Discharged outside the backlog — #24 writes the Dockerfiles. |
| RES-002 | drop | — |  |
| RES-003 | drop | — |  |
| RES-004 | drop | — |  |
| RES-005 | drop | — |  |
| RES-006 | transform | #29 | Discharged outside the backlog — the server-setup notes become the operating manual. |
| RES-007 | transform | #27, #28 | Discharged outside the backlog: what is *scraped* is ADR-0009's, what is *exposed* is ADR-0010's. |
| RES-008 | drop | — |  |
| RES-009 | drop | — |  |
| RES-010 | drop | #51 | The service dies; the requirement it served is ADR-0012's push. |
| RES-011 | transform | #15 | Discharged inside its own triage ticket — the eight README findings are folded into the ledger. |
| RES-012 | drop | — |  |
| RES-013 | transform | #62 |  |
| RES-014 | drop | — |  |
| RES-015 | drop | #62 | Art drops with the gamification layer; the *nothing to do* image is kept for #38 to accept or reject. |
