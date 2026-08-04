# Portal inventory: what exists, and what already landed in `task`

**Date**: 2026-07-31
**Purpose**: the feature ledger for the migration map ([#3](https://github.com/stainii/task/issues/3)). One addressable row per capability in the in-scope portal services, with its status in `task-back-end` / `task-front-end`.
**Scope**: `portal-todo`, `portal-recurring-tasks`, `portal-social`, and the `portal-front-end` modules that ride on them. Everything else lives in [Residual](#9-residual) for #15 to triage.
**Method**: source read directly from `../portal` and this repo, on disk, at the date above. Nothing was run; nothing was decided.

> **This ledger makes no decisions.** Verdicts (keep / transform / drop) are for #12–#15. The "status in `task`" column is a factual observation, not a recommendation, and "MISSING" is not an argument for building it.

## How to read a row

| Column | Meaning |
|---|---|
| **Id** | Stable citation key. Triage verdicts and backlog issues must cite it; #16 checks coverage against these ids. |
| **Capability** | What the thing does, in domain terms. |
| **Where it lives** | Class / file / endpoint in portal. |
| **Status in `task`** | See vocabulary below. |
| **Notes / fidelity** | For LANDED and PARTIAL rows: does the ported code do what the original did, and what differs? |

**Status vocabulary**

- **LANDED** — an equivalent exists in `task` and behaves equivalently as far as reading the code shows.
- **PARTIAL** — an equivalent exists but is narrower, differently shaped, or knowingly incomplete. Every PARTIAL row is a backlog candidate.
- **MISSING** — no equivalent in `task`.
- **OBSOLETE** — the thing only exists to serve an architecture `task` does not have (microservices, RabbitMQ, Eureka, service-to-service REST). There is nothing to port, but the *capability it served* may still need a home; where that is so, the row says which row carries it.
- **PLUMBING** — internal machinery with nothing to decide (base classes, config, boilerplate DTOs). Recorded so the row is covered, not because it needs a verdict.

**Companion ledger.** This ledger covers *portal* features. Defects in `task`'s own already-migrated code are in [`docs/repo-health.md`](repo-health.md) (#18) and are cited here as **D1–D6** / **F1–F3**. Both feed #11; #16 checks both.

**Class accounting.** Back-end rows are one-per-class, so the class counts are mechanically checkable: `portal-todo` 48 main classes, `portal-recurring-tasks` 20, `portal-social` 18 — see [§10](#10-coverage-accounting). Front-end rows are one-per-feature with the constituent files listed, since per-file rows would be unusable for HITL triage.

---

## 1. `portal-todo` — domain and API

Persistence is **MongoDB** (`portal-todo` database, collections `task`, `taskPatch`, `subscription`, `taskTemplate`). `task` uses **Postgres via Spring Data JDBC**, so *every* row in this service carries a data-shape change; that is the subject of the data-migration ticket, not of each row.

### 1.1 Tasks

| Id | Capability | Where it lives | Status in `task` | Notes / fidelity |
|---|---|---|---|---|
| TODO-001 | **Task aggregate** — id, flowId, name, creationDateTime, startDateTime, dueDateTime, expectedDurationInHours, context, importance, description, status, history | `model/task/Task.java` | **PARTIAL** | `task`'s `Task` drops **`flowId`** (no event bus to correlate with) and **`expectedDurationInHours`** (which the front-end sorting used — see FE-004). `startDateTime`/`dueDateTime` (`LocalDateTime`) became `startDate`/`dueDate` (`LocalDate`) — a real semantic narrowing: times of day can no longer be expressed. Adds `version` for optimistic locking. |
| TODO-002 | **Task statuses** OPEN / COMPLETED / CANCELLED, plus case-insensitive `parse` | `model/task/TaskStatus.java` | **LANDED** | Copied verbatim, including `parse`. |
| TODO-003 | **Importance** I_DO_NOT_REALLY_CARE / NOT_SO_IMPORTANT / IMPORTANT / VERY_IMPORTANT | `model/Importance.java` | **LANDED** | Identical four values. |
| TODO-004 | **Patch application** — apply a field-level patch to a task, then re-apply any newer patches (offline-first replay) | `Task.patch()` | **PARTIAL** | Ported, and ported *with the recursion bug intact* — see **D2**. Portal's version patched 8 fields; `task`'s patches 7 (no `expectedDurationInHours`, per TODO-001). |
| TODO-005 | **Undo a patch** — recompute the task without that patch and emit a compensating patch; undoing the creation patch completes the task instead | `Task.undoPatch()` / `calculateUndoPatchChanges()` | **LANDED** | Logic ported near-verbatim, including the "you can't un-create a task, so complete it" rule. |
| TODO-006 | **TaskPatch aggregate** — id, taskId, flowId, dateTime, changes map; `@JsonAnySetter` so unknown JSON fields become changes | `model/task/TaskPatch.java` | **PARTIAL** | Ported without `flowId`; `changes` persists as JSONB (`config/jdbcconverters/`). `dateTime` moved `Instant` → `LocalDateTime`, which loses the timezone anchor for a model whose whole point is merging edits from several devices. |
| TODO-007 | **TaskPatchResult** — reports whether a patch completed / uncompleted / rescheduled the task | `model/task/TaskPatchResult.java` | **MISSING** | Existed only to decide which outbound events to publish (TODO-020). With no event bus there is no consumer — but "was this task just completed?" is also the hook any future reminder/notification feature would need. |
| TODO-008 | **List active tasks** `GET /api/task/` | `controllers/TaskController` + `TaskService.findAllActiveTasks` | **LANDED** | `GET /api/tasks`, same `findByStatus(OPEN)` semantics. |
| TODO-009 | **Create task** `POST /api/task/` — defaults status/startDate, clears client history, writes a creation patch, publishes TaskCreated | `TaskController.create` + `TaskService.create` | **PARTIAL** | `POST /api/tasks` creates and emits over SSE. The creation patch moved into `Task.builderForInitialTask()`. Portal rejected a task without an id (client-generated); `task` generates ids server-side by default and rejects duplicates — a change with offline-first consequences (client-minted ids). |
| TODO-010 | **Create tasks from a template** `POST /api/task/from-template/` | `TaskController.createFromTemplate` | **LANDED** (moved) | Now `POST /api/task-templates/{id}/tasks`, in the `template` module, via an in-process `TaskCreationRequestedEvent`. |
| TODO-011 | **Patch a task** `PATCH /api/task/{id}` — server fills taskId/flowId from the path | `TaskController.patch` | **PARTIAL** | Now `POST /api/task-patches` with the taskId in the body. No 400 on a malformed body (**D5**), and the response no longer returns the patched task (portal returned `TaskPatchResult`). |
| TODO-012 | **Fetch patches since a timestamp** `GET /api/task/patch/?since=` — the reconnect/catch-up call | `TaskPatchController.getAllTaskPatchesSince` | **LANDED** (merged) | Folded into the SSE endpoint: `GET /api/task-patches?since=` replays then tails. |
| TODO-013 | **Tail patches over SSE** `GET /api/task/patch/?tail` | `TaskPatchController.tail` + `TaskPatchSseEmitterService` | **LANDED** | Same mechanism; `task` adds named events + heartbeats. Portal's `// TODO: find a way to write an integration test for SSE` and the nginx 30s-disconnect worry both survive as comments. |
| TODO-014 | **Undo a patch** `DELETE /api/task/patch/{id}` | `TaskPatchController.undoPatch` | **PARTIAL** | Endpoint exists; patch ids are only obtainable over SSE, so a cold-started client cannot undo — **D3**. |
| TODO-015 | **Task repository** — `findByStatus`, `findFirstByFlowIdOrderByCreationDateTimeDesc` | `repositories/TaskRepository` | **PARTIAL** | `findByStatus` landed. The flowId lookup did not — it was the event-correlation path (TODO-022). |
| TODO-016 | **TaskPatch repository** — `findByDateTimeAfter` | `repositories/TaskPatchRepository` | **LANDED** | Same query, used by the SSE replay. |
| TODO-017 | **TaskService / TaskPatchService orchestration** | `services/TaskService`, `services/TaskPatchService` | **PARTIAL** | Both exist. `task`'s `TaskPatchService` drops the `Source` (USER vs EVENT) distinction, which portal used to suppress event publication for event-originated patches. |
| TODO-018 | **SSE emitter registry** — register listeners, drop dead emitters | `services/TaskPatchSseEmitterService` | **LANDED** | Reimplemented as `TaskPatchSseEmitterService` + `service/helper/SseEmitters`. Portal's version had a latent bug (`List.remove(Collection)`); `task`'s does not. |

### 1.2 Task templates

| Id | Capability | Where it lives | Status in `task` | Notes / fidelity |
|---|---|---|---|---|
| TODO-019 | **TaskTemplate** — named template, list of task definitions, list of variable names | `model/template/TaskTemplate.java` | **LANDED** | `template.TaskTemplate`; variable names became a `TaskTemplateVariableName` record table rather than a string list. |
| TODO-020 | **TaskDefinition** — name, start/due-date deviation days + base, expectedDurationInHours, context, importance, description | `model/template/TaskDefinition.java` | **PARTIAL** | Ported except **`expectedDurationInHours`**, dropped with TODO-001. |
| TODO-021 | **DeviationBase** START_DATE / DUE_DATE | `model/template/DeviationBase.java` | **LANDED** | Verbatim. |
| TODO-022 | **Template → tasks expansion** — fill `${variables}`, compute each task's start/due date as a deviation from the main task's dates | `mappers/TaskMapper.mapToNewTask(TaskTemplate…)` | **LANDED** | Now `TaskTemplateService.createTasksWithTemplate`. One behavioural difference: portal fell back to `"No name"` when a name resolved empty; `task` throws `IllegalStateException`. |
| TODO-023 | **TaskTemplateEntry** — the template + variable values + main-task start/due dates | `dtos/TaskTemplateEntry.java` | **LANDED** | `template.dto.TaskTemplateEntry`; the template now comes from the path, not the body, and dates narrowed to `LocalDate`. |
| TODO-024 | **Template CRUD** `GET/POST/PUT/DELETE /api/template/` | `controllers/TaskTemplateController` + `services/TaskTemplateService` | **LANDED** | `/api/task-templates`, plus existence checks and typed exceptions portal did not have. |
| TODO-025 | **Template repository** | `repositories/TaskTemplateRepository` | **LANDED** | Spring Data JDBC equivalent. |
| TODO-026 | **Variable substitution** `${name}` → value, null-safe | `utils/StringUtils.fillInVariables` | **LANDED** | Copied to `utils/VariableUtils`. |

### 1.3 Event-driven task creation (subscriptions)

This is the machinery that let *any* portal service cause a todo to appear, without either side knowing about the other. It is the largest single block of functionality with no counterpart in `task`.

| Id | Capability | Where it lives | Status in `task` | Notes / fidelity |
|---|---|---|---|---|
| TODO-027 | **Subscription** — for events from `origin`, a SpEL `creationCondition` that creates a task and a `completeCondition` that completes matching tasks | `model/subscription/Subscription.java` | **MISSING** | Nothing equivalent. |
| TODO-028 | **SubscriptionMappingToTask** — SpEL expressions mapping an event to a task's name / description / due date / context / importance | `model/subscription/SubscriptionMappingToTask.java` | **MISSING** | The user-authored rules that made portal composable. |
| TODO-029 | **Subscription matching** — evaluate conditions against an incoming event | `services/SubscriptionService` | **MISSING** | |
| TODO-030 | **Event → task creation / completion** — on each received event, create tasks for firing subscriptions and complete tasks whose flow ended | `services/EventService` | **MISSING** | The consumer half of the RabbitMQ contract (CON-001). |
| TODO-031 | **Event → task mapping** (SpEL evaluation into a new `Task`) | `mappers/TaskMapper.mapToNewTask(FiringSubscription)` | **MISSING** | |
| TODO-032 | **Event → completing patch** (find newest task by flowId, patch status COMPLETED) | `mappers/TaskPatchMapper.mapToTaskPatchThatCompletesATask` | **MISSING** | Depends on TODO-015's flowId query. |
| TODO-033 | **Subscription CRUD** `GET/POST/PUT /api/subscription/` | `controllers/SubscriptionController` | **MISSING** | No delete existed in portal either. |
| TODO-034 | **Subscription repository** — `findByOrigin` | `repositories/SubscriptionRepository` | **MISSING** | |
| TODO-035 | **FiringSubscription** — subscription + the event that fired it | `dtos/FiringSubscription.java` | **PLUMBING** | Internal pair type for TODO-029/031. |
| TODO-036 | **Creation-patch mapper** — turn a new task into its first patch | `mappers/TaskPatchMapper.mapToPatchThatCreatesATask` | **LANDED** | Now `Task.builderForInitialTask()`. |

### 1.4 Outbound events

| Id | Capability | Where it lives | Status in `task` | Notes / fidelity |
|---|---|---|---|---|
| TODO-037 | **In-process task events** TaskCreated / TaskPatched / TaskCompleted / TaskRescheduled / TaskCancelled + the `TaskEvent` interface | `events/*.java` (6 classes) | **MISSING** | `task` has one in-process event, `TaskCreationRequestedEvent`, pointing the other way (template/recurring → task). The lifecycle signals are gone; TODO-007 is their trigger. |
| TODO-038 | **Publish schedule/cancellation events to the bus** on create / reschedule / complete / cancel | `services/PublishTaskEventsService` | **OBSOLETE** | Existed to tell other microservices. With one deployable, in-process listeners replace it — but see TODO-037 for the lost signal. |
| TODO-039 | **Schedule-event mapper** (`type=schedule`, task name, due date, FlowAction.START) | `mappers/ScheduleEventMapper` | **OBSOLETE** | Part of CON-001's payload shape. |
| TODO-040 | **Cancellation-event mapper** (`type=cancellation`, FlowAction.END) | `mappers/CancellationEventMapper` | **OBSOLETE** | Idem. |
| TODO-041 | **Event publisher / listener** (Spring Cloud Stream `eventChannel-in-0` / `-out-0` on `eventTopic`) | `messaging/EventPublisher`, `messaging/EventListener` | **OBSOLETE** | No RabbitMQ in `task`. |

### 1.5 Plumbing and utilities

| Id | Capability | Where it lives | Status in `task` | Notes / fidelity |
|---|---|---|---|---|
| TODO-042 | **Application bootstrap** — `@SpringBootApplication`, `@EnableMongoRepositories`, sets default TimeZone to `Europe/Brussels`, `APPLICATION_NAME = "Todo"` | `PortalTodoApplication.java` | **PARTIAL** | `TaskBackEndApplication` exists; **the timezone default is not set anywhere in `task`**, and `LocalDate`/`LocalDateTime` fields now depend on the JVM default. Worth a row of its own at triage. |
| TODO-043 | **Jackson configuration** — a `Clock` bean, lenient deserialization, JavaTime/Jdk8 modules, dates as ISO strings | `ModuleConfiguration.java` | **PARTIAL** | `task` configures only `fail-on-null-for-primitives: false`. **No `Clock` bean** — `task` calls `LocalDate.now()` / `LocalDateTime.now()` directly, which is why time-dependent logic (D1) is awkward to test. |
| TODO-044 | **Timezone-aware `LocalDateTime` deserializer** | `jsondeserializers/LocalDateTimeDeserializer.java` | **MISSING** | Related to TODO-042. |
| TODO-045 | **Lenient date parsing** — accepts a wide family of ISO-ish formats; `addDaysTo` | `utils/DateTimeUtils.java` | **PARTIAL** | `task`'s `DateTimeUtils` keeps `addDaysTo` and a `parseAsLocalDate`; the loose multi-format parser is reduced. |
| TODO-046 | **Reflection field/value dump** (used to build the creation patch and undo diffs) | `utils/ObjectUtils.java` | **LANDED** | Same approach in `task`'s `utils/ObjectUtils`. |
| TODO-047 | **`TaskNotFoundException` (404)** | `exceptions/TaskNotFoundException.java` | **PARTIAL** | Exists, but `task` has **no `@ControllerAdvice`**, so nothing maps it to a status code — **D5**. |
| TODO-048 | **`TaskPatchNotFoundException` (404)** | `exceptions/TaskPatchNotFoundException.java` | **PARTIAL** | Idem. |

---

## 2. `portal-recurring-tasks`

One deployable, deployed **four times** with a different `deployment-name` (`housagotchi`, `setlist`, `health`, `social-recurring-tasks`), each with its own database. Storage is **Postgres via JPA/Liquibase**.

| Id | Capability | Where it lives | Status in `task` | Notes / fidelity |
|---|---|---|---|---|
| REC-001 | **RecurringTask** — name, min/max days between executions, executions list; invariants (both > 0, max ≥ min) | `model/RecurringTask.java` | **LANDED** | `recurring/RecurringTaskTemplate`, same invariants in `checkData`. `task` adds `creationDate`, `activeTask`, `importance`, `context`, `description` — it now knows how to *become* a task, which portal delegated to subscriptions. |
| REC-002 | **Execution** — a dated record that the task was done | `model/Execution.java` | **LANDED** | `recurring/Execution`; `long` id → `UUID`, and note **D6** (the `addExecution` copy-on-write workaround). |
| REC-003 | **Last-execution derivation** — newest execution date, else null | `mappers/RecurringTaskDtoMapper` | **PARTIAL** | `getLastExecutionDateOrCreationDate()` falls back to the **creation date** instead of null, so a never-executed template becomes due; portal never fired for one (see REC-010). A deliberate-looking improvement, unrecorded. |
| REC-004 | **CRUD** `GET/POST/PUT/DELETE /api/recurring-task/`, `/{id}` | `controllers/RecurringTaskController` | **PARTIAL** | `/api/recurring-task-templates` — but inconsistent routing/verbs and no service layer (**D4**). |
| REC-005 | **Register an execution** `POST /api/recurring-task/{id}/execution/`, publishing an execution event when the source is USER | `RecurringTaskController.addExecution` + `RecurringTaskService.addExecution` | **PARTIAL** | Endpoint landed. The event publication is gone (no bus), and with it the **USER vs EVENT source distinction** (`dtos/Source`) that prevented echo loops. |
| REC-006 | **Overdue scheduler** — daily cron: publish a reminder event for tasks hitting their *min* date and a final warning for those hitting *max*, cancelling the earlier reminder | `schedulers/PublishOvertimeRecurringTasks` | **PARTIAL** | `recurring/scheduler/CreateDueTasks` runs the same daily cron but **creates a task directly** instead of publishing a reminder. Two behaviours lost: the *escalation* (min → urgent-at-max, with cancellation of the superseded reminder) and the `urgent` flag. Also **D1**: `Period.getDays()` means anything with `min > 30` never fires at all. |
| REC-007 | **Event-driven execution** — an END event on this deployment's flowId registers an execution (this is how completing a todo ticked off the recurring task) | `messaging/EventListener` | **MISSING** | The loop *recurring task → task → done → execution recorded* is broken: `task` creates the task (REC-006) but nothing writes the execution back when it is completed. **The single most load-bearing gap in this service.** |
| REC-008 | **Reminder-event mapper** — `type=reminder`, `urgent`, task name, lastExecution, min/max due dates | `mappers/event/ReminderEventMapper` | **OBSOLETE** | Payload shape of CON-001; the fields it carried are what housagotchi/health/setlist rendered. |
| REC-009 | **Execution / cancellation event mappers** | `mappers/event/ExecutionEventMapper`, `CancellationEventMapper` | **OBSOLETE** | |
| REC-010 | **"Turns overdue today" predicates** — fires only on the exact day `lastExecution + min` (and `+ max`), skipping max==min templates for the escalation | `PublishOvertimeRecurringTasks.isTodayEqualTo…` | **PARTIAL** | `task` uses `>= min` (fires every day until done, guarded by the `activeTask` flag) instead of exactly-on-the-day. A better design; also the reason `activeTask` exists, and REC-007's gap means it may never be cleared. |
| REC-011 | **Multi-tenancy by deployment** — `deployment-name` property drives the app name, JMX domain, event source and flowId prefix; four deployments, four databases | `application.yml`, all event mappers | **MISSING** | `task` is a single deployment with a single set of recurring templates. **The grouping that made housagotchi/setlist/health distinct apps has no representation** — no field on `RecurringTaskTemplate` says which "app" a template belongs to. Blocks FE-007/FE-008/FE-009. |
| REC-012 | **RecurringTask repository** | `repositories/RecurringTaskRepository` | **LANDED** | |
| REC-013 | **Service layer** — findAll / findById / create / update / delete / addExecution | `services/RecurringTaskService` | **MISSING** | `task` has **no service layer here at all**; the controller talks to the repository (**D4**). |
| REC-014 | **DTO layer** — `RecurringTaskDto`, `ExecutionDto` | `dtos/*.java` | **LANDED** | `recurring/dto/*`; `ExecutionDto` lost its `source` (REC-005). |
| REC-015 | **Mapper base class + entity/DTO mappers** | `mappers/Mapper`, `ExecutionMapper`, `RecurringTaskMapper`, `RecurringTaskDtoMapper` | **LANDED** | Replaced by MapStruct `RecurringTaskTemplateMapper`. |
| REC-016 | **Application bootstrap + Jackson config** | `PortalRecurringTasks`, `ModuleConfiguration` | **PLUMBING** | Subsumed by TODO-042/043. |
| REC-017 | **Event publisher / listener wiring** | `messaging/EventPublisher`, `messaging/EventListener` (bean) | **OBSOLETE** | See CON-001. |
| REC-018 | **Cron configuration** `0 0 4 * * *` | `application.yml` | **LANDED** | Same expression in `task`'s `application.yml`. |

---

## 3. `portal-social`

A thin app over `portal-recurring-tasks` (deployment `social-recurring-tasks`) plus `portal-image`. It owns only the person record; the contact rhythm *is* a recurring task.

> **Correction (#19, 2026-08-03): these rows were read from an abandoned working tree.** `portal-social`'s committed HEAD is **Spring Boot 2.7.18 / Java 14**; the 3.4.7 / Java 17 state on disk is an **uncommitted, unfinished** upgrade attempt (5 modified source files and a `hibernate-java8` 5.6.15 pin). #19 fixed the source of truth as **committed HEAD for behaviour, production for data**, so the rows below describe code one major version ahead of the real HEAD. Blast radius is small — [#13](https://github.com/stainii/task/issues/13) drops 11 of these 12 rows and `portal-social` disappears entirely — so this is recorded rather than re-triaged. The persisted *shape* is unaffected either way: #19 diffed the whole upgrade run across `Task`, `TaskPatch`, `Execution` and `RecurringTask` and found only `javax`→`jakarta` imports and string-formatting idioms, with no field renamed and no type changed.

| Id | Capability | Where it lives | Status in `task` | Notes / fidelity |
|---|---|---|---|---|
| SOC-001 | **Person** — name, colour thumbnail, sepia thumbnail, recurringTaskId, latestUpdates | `model/Person.java` | **MISSING** | |
| SOC-002 | **Person ↔ recurring task link** — a person's min/max days between contacts and last contact are *stored in the recurring-tasks service*, not here | `PersonService.enrichAndMap`, `services/RecurringTasksService` | **MISSING** | The design premise the map's "social as a kind of recurring task" note builds on: portal already modelled contact rhythm as a recurring task. |
| SOC-003 | **Person CRUD** `GET/POST/PUT/DELETE /api/person/`, `/{id}` | `controllers/PersonController` | **MISSING** | |
| SOC-004 | **Register a contact** `POST /api/person/{id}/contact/` — stores `latestUpdates` and adds an execution to the person's recurring task | `PersonController.addContact`, `PersonService.addContact` | **MISSING** | |
| SOC-005 | **Photo handling** — base64 upload, remote 300×300 crop into a colour and a sepia thumbnail, delete-on-remove | `services/ImageService`, `dtos/ImageLabel` | **DROPPED (declared)** | The map already rules `portal-image` out of scope; social loses its photos. Recorded here so the row is covered — the *person record* rows (SOC-001/003) still need a verdict independently. |
| SOC-006 | **Manual cross-service rollback** — undo thumbnails and the remote recurring task when a later step fails, since there is no distributed transaction | `PersonService.create/update`, `RecurringTasksService.rollback*` | **OBSOLETE** | Pure microservice tax: one database and one transaction make all of it disappear. A textbook *transform*, not *keep*. |
| SOC-007 | **`SavePersonHelper`** — `REQUIRES_NEW` commit so the rollback logic above can see committed state | `services/SavePersonHelper.java` | **OBSOLETE** | Same reason. |
| SOC-008 | **Duplicate-name guard** — refuse a person whose name already exists | `PersonService.create` | **MISSING** | Carries a portal TODO: it *should* have linked to the existing recurring task instead of failing. |
| SOC-009 | **Person repository** — `findByName` | `repositories/PersonRepository` | **MISSING** | |
| SOC-010 | **DTO layer** — `PersonDto`, `ContactDto`, `DeleteResult`, `ExecutionDto`, `RecurringTaskDto`, `Source` | `dtos/*.java` (6 classes) | **PLUMBING** | `RecurringTaskDto`/`ExecutionDto`/`Source` are copies of REC-014's types, duplicated because there was no shared module. |
| SOC-011 | **Mappers** — `PersonMapper`, `RecurringTaskDtoMapper` | `mappers/*.java` | **PLUMBING** | |
| SOC-012 | **Application bootstrap + Eureka discovery + `RestTemplate`** | `PortalSocialApplication`, `ModuleConfiguration`, `bootstrap*.properties` | **OBSOLETE** | Service discovery has no meaning in a modulith. |

---

## 4. `portal-front-end` — modules in scope

Angular. Rows are per feature; the files each covers are listed so coverage stays checkable. Each component row covers its `.ts` / `.html` / `.scss` / `.spec.ts` set.

`task-front-end` is a **fresh rewrite and currently a scaffold** — one `task-list` component, a `task.store`, Keycloak wiring, and three defects (**F1–F3**). So the honest status of almost every row below is MISSING; where `task-front-end` has *something*, the row says so.

### 4.1 Todo

| Id | Capability | Files | Status in `task-front-end` | Notes |
|---|---|---|---|---|
| FE-001 | **Offline-first task repository** — localStorage is the source of truth; replay patches since `dateOfLastUpdate` on start, tail SSE, optimistic local write then server write with rollback on failure | `todo/task.repository.ts` | **PARTIAL** | `task.store.ts` writes localStorage but never reads it back on init, applies no patches (**F2**), opens two streams (**F3**) and sends no token (**F1**). The *design* is the one thing the map says must survive; the implementation does not exist yet. |
| FE-002 | **Client-side patch construction** — diff updated vs original task into a patch with a client-minted GUID | `todo/task-patch.service.ts` | **MISSING** | Note this is where portal minted **client-side patch ids**. *(Corrected by [#14](https://github.com/stainii/task/issues/14): the original note said **D3** meant `task`'s API no longer permits this. Stale — [#12](https://github.com/stainii/task/issues/12) restored client-minted ids for both tasks and patches.)* |
| FE-003 | **Client-side task model with replay** — `patch()` sorts history and replays it; `rollback()` removes a patch and replays | `todo/task.model.ts` | **MISSING** | The client mirror of TODO-004. |
| FE-004 | **Urgency/importance scoring** — points from urgency (due date, expected duration), importance and overdue exceptions; ties broken by creation date | `todo/task.comparator.ts` | **MISSING** | Consumes `expectedDurationInHours`, which TODO-001 dropped. |
| FE-005 | **Task typing** — focus / goals / fit-in / back-burner buckets from importance + due-date proximity (< 7 days) | `todo/task.model.ts` (`hasType*`), `todo/todo-task-panel/` | **MISSING** | *(Corrected by [#14](https://github.com/stainii/task/issues/14): the original note said no view uses it. Wrong — `todo-task-panel.component.html:2-5` binds all four types as CSS classes, and the SCSS renders them as a **10px coloured stripe** down the panel's left edge: red = focus, green = goals, orange = fit-in, grey = back-burner. Not dead code, and not a bucketing view — it is the colour-coding on every task.)* |
| FE-006 | **Overview screen** — top 5 "most important", collapsible "also…", collapsible "starting in the future", context filter from the query string, "Relax! Nothing else to do." empty state | `todo/todo-overview/`, `todo/todo-task-panel/`, `todo/todo-app/`, `todo/todo-menu-bar-for-overview/`, `todo/todo-routing.module.ts`, `todo/todo.module.ts` | **MISSING** | `task-list.html` is CLI scaffold (`task-list works!`). **Defect found by [#14](https://github.com/stainii/task/issues/14)**: `todo-overview.component.ts:73` filters `status != "COMPLETED"` only, but `TaskStatus` has three values — so **every cancelled task stays on the overview forever**, ranked and coloured like live work. Moot in practice, because nothing in portal could set `CANCELLED` (see FE-008). |
| FE-007 | **Task create/edit dialog** and its result protocol (NO_ACTION / SAVE_TASK / USE_A_TASK_TEMPLATE / SAVE_TASK_TEMPLATE_ENTRY) | `todo/todo-task-details/`, `todo/dialog-result.model.ts` | **MISSING** | No create or edit UI at all today. |
| FE-008 | **Complete a task** — from the panel button, **or by swiping right**, via a patch | `todo/todo-task-panel/`, `todo/task.service.ts`, `main.ts` (HammerJS) | **MISSING** | *(Amended by [#14](https://github.com/stainii/task/issues/14): the original row named only the button. `todo-task-panel.component.html:6-8` also binds `(swiperight)="complete()"` with `(pan)`/`(panend)` fill-and-revert feedback, via **HammerJS** — `hammerjs` in `package.json`, `HammerModule` in `main.ts` and `todo.module.ts`. HammerJS appeared in no row at all. It is unmaintained and Angular has dropped support, so the gesture is a rebuild on Pointer Events, not a port.)* |
| FE-009 | **Undo** — delete a patch | `todo/task.service.ts` (`undo`) | **MISSING** | *(Corrected by [#14](https://github.com/stainii/task/issues/14): "blocked by **D3**" is stale. Client-minted ids came back in [#12](https://github.com/stainii/task/issues/12), and [ADR-0004](adr/0004-one-write-verb-two-clocks-offline-sync.md) put `id` on `TaskPatchDto` precisely so undo works cold. Undo itself becomes a **void patch**, not a delete.)* |
| FE-010 | **Template management UI** — list, create/edit template, edit task definitions | `todo/todo-templates/`, `todo/todo-task-templates/`, `todo/todo-task-template-details/`, `todo/todo-task-definition-details/`, `todo/todo-menu-bar-for-templates/`, `todo/task-template.service.ts`, `todo/task-template.model.ts`, `todo/task-definition.model.ts` | **MISSING** | Back-end surface exists (TODO-024) with no client. |
| FE-011 | **Instantiate a template** — fill variables and dates, create the tasks | `todo/todo-task-template-entry-details/`, `todo/task-template-entry.model.ts` | **MISSING** | |
| FE-012 | **Subscription management UI** — list, view, edit SpEL conditions and mappings | `todo/todo-subscription-list/`, `todo/todo-subscription-details/`, `todo/todo-subscription-editor/`, `todo/todo-menu-bar-for-subscriptions/`, `todo/todo-subscription.service.ts`, `todo/todo-subscription.model.ts`, `todo/subscription-mapping-to-task.model.ts` | **MISSING** | Dies with TODO-027…034 unless those are kept. |
| FE-013 | **Shared todo enums/models** — `importance.model.ts`, `task-status.model.ts`, `task-patch.model.ts`, `task-patch-result.model.ts` | as listed | **PARTIAL** | `task-front-end/src/app/model.ts` holds a minimal `Task`/`TaskPatch`. |
| FE-014 | **Default task context** from `environment.defaultTaskContext` | `todo/task.service.ts`, `todo/task-template.service.ts` | **MISSING** | A per-deployment default; `context` is mandatory on the back end. |

### 4.2 Recurring-tasks and its three skins

`recurring-tasks/` is a shared client keyed by `deploymentName`; housagotchi, setlist and health are three views over the same API (REC-011).

| Id | Capability | Files | Status | Notes |
|---|---|---|---|---|
| FE-015 | **Recurring-task API client** — CRUD against `/api/{deployment}/api/recurring-task/`, sorted by name | `recurring-tasks/recurring-task.service.ts`, `recurring-tasks/recurring-task.model.ts` | **MISSING** | The `{deployment}` path segment is exactly what REC-011 removes. |
| FE-016 | **Register an execution** (`date`, `source: USER`) | `recurring-tasks/execution.service.ts`, `recurring-tasks/execution.model.ts` | **MISSING** | |
| FE-017 | **Housagotchi creature** — mood HAPPY / ATTENTION / MAD from late (past min) and very-late (past max) tasks, creature art, speech balloon | `housagotchi/housagotchi-app/`, `-creature/`, `-balloon/`, `-menu-bar-for-creature/`, `housagotchi-report.service.ts`, `mood.model.ts`, `report.model.ts`, `housagotchi-constants.ts`, `housagotchi-routing.module.ts`, `housagotchi.module.ts`, `assets/housagotchi/` | **MISSING** | The gamification layer the map's fog patch is about. Note it is *computed client-side* from min/max — no server support beyond REC-001. |
| FE-018 | **Housagotchi execution + task management UI** | `housagotchi/housagotchi-add-execution/`, `-manage-recurring-tasks/`, `-recurring-task-details/`, `-menu-bar-for-manage-recurring-tasks/` | **MISSING** | |
| FE-019 | **Setlist** — songs sorted by rehearsal interval, `overdue` flag past min | `setlist/setlist.service.ts`, `setlist.model.ts`, `song.model.ts`, `setlist-app/`, `-list/`, `-song-details/`, `-manage/`, `-add-execution/`, `-menu-bar-for-list/`, `-menu-bar-for-manage/`, `setlist-constants.ts`, `setlist-routing.module.ts`, `setlist.module.ts` | **MISSING** | Same data, different vocabulary (song = recurring task, rehearsal = execution). |
| FE-020 | **Health / Sporty Spice** — status BODYBUILDER vs FAT from late tasks (min − 1 "rest days" rule), balloon, art | `health/health-report.service.ts`, `report.model.ts`, `state.model.ts`, `health-app/`, `-sporty-spice/`, `-balloon/`, `-add-execution/`, `-manage-recurring-tasks/`, `-recurring-task-details/`, `-menu-bar-for-sporty-spice/`, `-menu-bar-for-manage-recurring-tasks/`, `health-constants.ts`, `health-routing.module.ts`, `health.module.ts`, `assets/health/` | **MISSING** | Note the subtly different arithmetic from FE-017: health subtracts a day ("x rest days = x + 1 min days"). |

### 4.3 Social

| Id | Capability | Files | Status | Notes |
|---|---|---|---|---|
| FE-021 | **Social overview** — polaroid wall of people, `shouldContact` highlighting | `social/social-overview/`, `social-polaroid/`, `social-menu-bar-for-overview/`, `social-routing.module.ts`, `social.module.ts` | **MISSING** | Sepia vs colour thumbnail encodes "should contact" — dies with SOC-005. |
| FE-022 | **Register a contact** — date + "latest updates" note | `social/social-add-contact/`, `contact.model.ts` | **MISSING** | |
| FE-023 | **Manage people** — list, create/edit person, photo upload, min/max days between contacts | `social/social-manage-people/`, `social-manage-people-list/`, `social-person-settings/`, `social-person-settings-edit/`, `social-menu-bar-for-manage-people/`, `person.model.ts` | **MISSING** | |
| FE-024 | **Social API client** incl. image URL construction | `social/social.service.ts` | **MISSING** | |

### 4.4 Shell, offline and auth

| Id | Capability | Files | Status | Notes |
|---|---|---|---|---|
| FE-025 | **App shell** — Material sidenav, responsive handset breakpoint, toolbar, router outlet | `dashboard/dashboard.component.*`, `app.component.*` | **PARTIAL** | `task-front-end/src/app/app.html` is generated scaffold with `Link 1/2/3`. |
| FE-026 | **Module menu** — hardcoded list: todo, housagotchi, setlist, social, health, activity, notifications | `menu/menu.component.*` | **MISSING** | The list *is* the portal app inventory as the author saw it. |
| FE-027 | **Offline indicator** — window online/offline events | `offline/offline-indicator/` | **MISSING** | |
| FE-028 | **Retry interceptor** — 10 attempts, linear backoff from 10s, re-login on expired token, error notifications | `retry.interceptor.ts` | **MISSING** | Portal's actual offline tolerance for non-todo calls. |
| FE-029 | **Error notification surface** | `error/error.service.ts`, `error/error-notification/` | **MISSING** | |
| FE-030 | **Keycloak auth** — token access, login/logout, route guard | `user/user.service.ts`, `user/authentication-guard.service.ts`, `user/logout/` | **PARTIAL** | `task-front-end` has `provideKeycloak` and authenticates, but its bearer interceptor never matches (**F1**), and `onLoad: 'login-required'` is flagged in-code as hostile to offline use. |
| FE-031 | **Routing + lazy module loading**, `**` → todo | `app-routing.module.ts` | **PARTIAL** | `app.routes.ts` exists with the single scaffold route. |
| FE-032 | **Date helpers** — humanized difference | `util/date.service.ts`, `util/util.module.ts` | **MISSING** | |
| FE-033 | **localStorage wrapper** | `util/local-storage.service.ts` | **PARTIAL** | Inlined into `task.store.ts`, with a TODO to extract it and a format bug (two date formats, one key — see repo-health). |
| FE-034 | **Random adjective generator** — pimps placeholder text ("My crazy template name") | `funny-details/random-adjective.service.ts`, `funny-details/funny-details.module.ts` | **MISSING** | Small, and pure personality. Flagged rather than silently dropped. |

---

## 5. Database schemas

| Id | Schema | Shape | Notes |
|---|---|---|---|
| DB-001 | **`portal-todo`** (MongoDB, port 27018) | collections `task`, `taskPatch` (explicit `@Document(collection="taskPatch")`), `subscription`, `taskTemplate`. `Task.history` is a **`@DBRef` list to `taskPatch`**. Ids are String UUIDs. No migrations — schemaless, created by `docker-entrypoint-initdb.d/init.sh` (user creation only). | Document → relational is the biggest data-migration jump: patches become rows in `task_patch` with an `order_index`, and `changes` becomes JSONB. **Corrected by [#35](https://github.com/stainii/task/issues/35): the Mongo database is named `todo`, not `portal-todo`** (`portal-todo-db` is the container). Mongo **4.2.1**. Counts at 2026-08-04: `task` 11855, `taskPatch` 38211, `taskTemplate` 3, `subscription` 4. |
| DB-002 | **`portal-housagotchi`** (Postgres 5433, Liquibase) | `recurring_task(id bigint pk, name varchar(255) not null, min_number_of_days_between_executions int not null, max_number_of_days_between_executions int not null)`; `execution(id bigint pk, date timestamp not null, recurring_task_id bigint fk)`; sequences `recurring_task_id_sequence`, `execution_id_sequence` (increment 50). | Same changelog is applied to **four** databases, one per deployment (REC-011). Only the housagotchi URL is in the committed config; setlist/health/social-recurring-tasks databases are deployment-time overrides. |
| DB-003 | **`portal-setlist`**, **`portal-health`**, **`portal-social-recurring-tasks`** (Postgres) | identical to DB-002. | ~~Names inferred…verify against the live server~~ — **verified by [#35](https://github.com/stainii/task/issues/35): all three names correct, exactly as inferred.** Counts at 2026-08-04: setlist 4 templates / 686 executions (pg 12.1), health 6 / 2311 (pg 12.1), social-recurring-tasks 9 / 1443 (pg 12.2). |
| DB-004 | **`portal-social`** (Postgres 5438, Liquibase) — **9 person rows** at 2026-08-04 ([#35](https://github.com/stainii/task/issues/35)) | `person(id bigint pk, name varchar(255) not null, color_thumbnail varchar(255) not null (renamed from image_name), sepia_thumbnail varchar(255), recurring_task_id bigint not null, latest_updates text)`; sequence `person_id_sequence` (increment 50, initial 0). | `recurring_task_id` is a **cross-database foreign key with no constraint** — it points into DB-003's social deployment. Migration has to resolve it. |
| DB-005 | **`task` schema (target)** | `task`, `task_patch` (V1); `task_template`, `task_template_variable_name`, `task_definition` (V2); `recurring_task_template`, `execution` (V3). All ids UUID. | Verified against the entities with no drift (#18). No table yet for subscriptions (TODO-027), persons (SOC-001), or any notion of deployment/grouping (REC-011). |

---

## 6. Cross-service contracts

| Id | Contract | Shape | Consumers / producers | Status |
|---|---|---|---|---|
| CON-001 | **`eventTopic` on RabbitMQ** (Spring Cloud Stream `eventChannel-in-0` / `eventChannel-out-0`, JSON, `List<Event>`) | `Event{source, flowId, flowAction: START/UPDATE/END, publishDate, data: Map<String,String>}` (`portal-model`) | Produced by todo (schedule/cancellation), recurring-tasks (reminder/execution/cancellation) and every other portal service; consumed by todo (subscriptions) and recurring-tasks (executions). | **OBSOLETE as transport.** The `data` conventions (`type=schedule|cancellation|reminder|execution`, `urgent`, `task`, `dueDate`, `minDueDate`, `maxDueDate`) are the payload semantics any in-process replacement must still express. |
| CON-002 | **`flowId` correlation** — `"<source>-<id>"`, e.g. `Todo-<uuid>`, `housagotchi-42` | string convention, no type | todo matches events to tasks by flowId; recurring-tasks parses its own id back out of the flowId. | **MISSING in `task`** — `flowId` was dropped from `Task`/`TaskPatch` (TODO-001/006). Nothing links a created task back to the recurring template that spawned it, which is why REC-007 cannot be closed. **This is a design gap, not just a dropped field.** *(Extended by [#15](https://github.com/stainii/task/issues/15): the `<source>` half is the **`deployment-name` property**, which exists **only as a deployment-time env var** — `DEPLOYMENT_NAME`, passed in `portal-recurring-tasks/Dockerfile`, present in no committed config. The README's example is capitalised — `Housagotchi-1001` — while DB-002/003's database names are lowercase `portal-housagotchi`. **The prefix is not the database name and cannot be inferred from this repo**: ADR-0005's importer must derive the prefix set from the dumped data itself. See [#35](https://github.com/stainii/task/issues/35).)* |
| CON-003 | **social → recurring-tasks REST** — `GET/POST/PUT/DELETE /api/recurring-task/{id}`, `POST /{id}/execution/`, resolved via Eureka service id `social-recurring-tasks` | JSON `RecurringTaskDto` / `ExecutionDto` | `portal-social` | **OBSOLETE** — becomes an in-process call. |
| CON-004 | **social → image REST** — `POST /api/transform/` (multipart, transformation definitions), `DELETE /api/remove/{name}`, `GET /api/retrieve/{name}` (front-end), via Eureka service id `image` | multipart + `ImageDto{name,label}` | `portal-social`, front-end | **OUT OF SCOPE** — `portal-image` is not migrating. |
| CON-005 | **Front-end → gateway path scheme** — `/api/{service}/api/...`, e.g. `/api/todo/api/task/`, `/api/housagotchi/api/recurring-task/` | URL convention via `portal-proxy` | whole front-end | **OBSOLETE** — one deployable, so `task-front-end` calls `/api/tasks` directly. Every front-end row above carries this rewrite. |
| CON-006 | **Keycloak** — realm-based auth; `portal.auth.keycloak-uri` on the back ends, `keycloak-js` on the front end | OIDC bearer tokens | all | **PARTIAL** — `task` is an OAuth2 resource server against realm `portal-realm`. *(Answered by [#15](https://github.com/stainii/task/issues/15): the realm and its users **carry over as-is** — Keycloak is recent on both sides (`keycloak-js` 26 / `keycloak-angular` 19), so there is no accumulated config to clean. Only the **name** changes, because the Keycloak instance is **shared infrastructure**, not `task`'s: a neutral realm name with `task` as **one client in it**, so other apps can couple later.)* |

---

## 7. Tests in the in-scope services

Recorded because they document intended behaviour and because the map raises the bar on test quality.

| Id | What | Where | Notes |
|---|---|---|---|
| TST-001 | **portal-todo tests** (13 classes) — mappers (4), `TaskTest`, `TaskStatusTest`, services (4 incl. an SSE emitter test), utils (3), `AbstractSpringBootTest` | `portal-todo/src/test/` | Unit-heavy, Mockito. `TaskTest` is the closest thing to a spec for TODO-004/005. |
| TST-002 | **portal-recurring-tasks tests** (12 classes) — `RecurringTaskControllerIntTest` with DBUnit XML datasets (10 files), mappers (6), `EventListenerTest`, `RecurringTaskTest`, `PublishOvertimeRecurringTasksTest`, `RecurringTaskServiceTest` | `portal-recurring-tasks/src/test/` | The DBUnit datasets are a usable spec of DB-002's row shapes. |
| TST-003 | **portal-social tests** (4 classes) — `PersonServiceTest`, `RecurringTasksServiceTest`, `PersonMapperTest`, `RecurringTaskDtoMapperTest` | `portal-social/src/test/` | Mock-heavy; mostly exercises the rollback choreography (SOC-006). |
| TST-004 | **portal-front-end specs** — one `.spec.ts` per component, CLI-generated | `portal-front-end/src/app/**` | Whether any of them assert anything meaningful was not checked. |

---

## 8. Out of scope, restated

Already ruled out on the map; listed so a reader of this ledger does not go looking for rows: `portal-activity`, `portal-weather`, `portal-location`, `portal-image`, `portal-notifications`, `portal-proxy`, `portal-authentication`, `portal-model`, `portal-auth-starter`, `fridge/`. Their front-end counterparts (`activity/` — 24 files, `notification/` — 17 files) are likewise out of scope, though `notification/` is the UI half of the "does anything need reminders?" question the map still holds as fog.

---

## 9. Residual

Everything in or around the in-scope services that is not a feature of them. **#15 triages this section.** Nothing here has been silently dropped.

| Id | Item | Where | Note |
|---|---|---|---|
| RES-001 | **Dockerfiles** for todo, recurring-tasks, social | `portal-*/Dockerfile` | `task` has **no Dockerfile at all** — the unbuilt prerequisite for CD flagged in #21. |
| RES-002 | **Jenkinsfiles** | `portal-*/Jenkinsfile` | The CI that is not carried over (#21–#25). |
| RES-003 | **Maven wrappers, `pom.xml`, `lombok.config`, `.iml` files** | each service | Build config; `task` has its own. |
| RES-004 | **Liquibase changelogs** | `portal-recurring-tasks`, `portal-social` | The *source* schema definitions for DB-002/004; `task` uses Flyway. Needed by the data migration whether or not Liquibase itself is kept. |
| RES-005 | **`portal-todo/docker-entrypoint-initdb.d/init.sh`** | portal-todo | MongoDB user bootstrap. |
| RES-006 | **Swarm compose file, prometheus config, base images, server setup notes** | `docker-compose-for-swarm.yml`, `prometheus/`, `docker-base-images/`, `_for_myself_server_setup_instructions.txt` | The deployment reality the cutover replaces — the closest thing to documentation of the running production system, including which databases actually exist (DB-003). |
| RES-007 | **Actuator/Prometheus exposure and CORS wildcards** | each `application.yml` | Observability config; relates to the silent-failure alerting the map requires before cutover. |
| RES-008 | **`portal-model`** as a *contract* (as opposed to a library) | `portal-model/**` | The library is out of scope, but `Event`/`FlowAction` define CON-001's wire format, needed to read any archived message or to interpret CON-002. |
| RES-009 | **`portal-auth-starter` configuration keys** — `portal.auth.keycloak-uri` | each `application.yml` | The realm/client names in the running system; feeds the open auth question. |
| RES-010 | **`portal-email`** | `portal-email/` | Explicitly conditional on the map: in scope only if new functionality needs it. *(Settled by [#15](https://github.com/stainii/task/issues/15): **DROP** — the service dies. The requirement it served is [#34](https://github.com/stainii/task/issues/34), still open and unchanged; #13 already removed its loudest consumer.)* |
| RES-011 | **README files** | `portal-*/README.md` | ~~Not read for this ledger~~ — **read in full by [#15](https://github.com/stainii/task/issues/15)**. Eight facts recovered, none of them in any other row: **(a)** the `flowId` prefix constraint above (CON-002); **(b)** portal's own README documents the **sorted fold** — *"applied in order of their datetime… it doesn't matter if patch A gets sent before patch B"* — so **D2 is code diverging from its own documentation**, and ADR-0004 restores stated intent rather than inventing a rule; **(c)** the urgent escalation is documented as **cancel-then-re-remind**, corroborating REC-006's collapse; **(d)** releases run **from a local machine** via JGitflow, publishing to **Docker Hub *and* a personal Nexus** — two artefact stores to consciously abandon (#22/#24); **(e)** Jenkins is **self-hosted on the production server** (`server.stijnhooft.be/jenkins`), so decommissioning it frees resources on the box #22 is sizing; **(f)** every service ran **`-Xmx400m -Xms400m`** — the footprint the modulith replaces; **(g)** `PUBLISH_OVERTIME_RECURRING_TASKS_CRON = 0 0 4 * * *` confirms [#40](https://github.com/stainii/task/issues/40)'s 04:00 came from config, not code; **(h)** `portal-todo`'s README records the **Eisenhower four-quadrant** origin of importance/urgency — the previously undocumented "why" behind FE-004. `portal-front-end`'s README is Angular CLI boilerplate. |
| RES-012 | **Test resources / DBUnit datasets** | `portal-recurring-tasks/src/test/resources/datasets/*.xml` | See TST-002. |
| RES-013 | **Front-end build and serve config** — `angular.json`, `proxy.conf.json`, `nginx-custom.conf`, `ngsw-config.json` (service worker), `cert.pem`/`key.pem`, `tslint.json` | `portal-front-end/` | **`ngsw-config.json` matters**: portal had an Angular service worker, i.e. real PWA/offline installability. `task-front-end` has none. |
| RES-014 | **Front-end environments** — `environment.ts` incl. `defaultTaskContext` | `portal-front-end/src/environments/` | See FE-014. |
| RES-015 | **Front-end assets** — housagotchi creature art, sporty-spice art, todo "nothing to do" image, icons | `portal-front-end/src/assets/**` | Art is not code; if FE-017/FE-020 are kept, these files come with them. |

---

## 10. Coverage accounting

Every class, endpoint, event, scheduled job, table and front-end feature in the in-scope services has a row above.

| Source | Unit | Count | Rows |
|---|---|---|---|
| `portal-todo` | main Java classes | 48 | TODO-001 … TODO-048 (1 : 1) |
| `portal-recurring-tasks` | main Java classes | 20 | REC-001 … REC-018 (18 rows; REC-015 covers the 4 mapper classes, REC-016 covers 2 bootstrap classes) |
| `portal-social` | main Java classes | 18 | SOC-001 … SOC-012 (12 rows; SOC-010 covers 6 DTOs, SOC-011 covers 2 mappers, SOC-012 covers 2 bootstrap classes) |
| `portal-front-end` | in-scope `.ts` files (todo 34, recurring-tasks 4, housagotchi 14, setlist 13, health 14, social 14, offline 1, dashboard 1, menu 1, user 3, util 3, error 2, funny-details 2, root 3) | 109 | FE-001 … FE-034 (34 rows, files listed per row) |
| `portal-front-end` | out-of-scope `.ts` files (activity 25, notification 17) | 42 | §8 — no rows, by scope |
| Databases | schemas | 5 | DB-001 … DB-005 |
| Contracts | cross-service | 6 | CON-001 … CON-006 |
| Tests | test suites | 4 | TST-001 … TST-004 |
| Residual | items | 15 | RES-001 … RES-015 |

**Total addressable rows: 142** — TODO-001…048 (48) + REC-001…018 (18) + SOC-001…012 (12) + FE-001…034 (34) + DB-001…005 (5) + CON-001…006 (6) + TST-001…004 (4) + RES-001…015 (15). The id ranges are contiguous with no gaps, so #16 can check coverage against the ranges alone.

### Known limits of this ledger

State these to whoever consumes the ledger, rather than treating it as complete truth:

1. ~~**No database was queried.**~~ **Closed by [#35](https://github.com/stainii/task/issues/35)** — every database was enumerated and counted from the 2026-08-04 dump set (frozen at `~/portal-archive/2026-08-04/`). DB-001…004 now carry real counts, DB-003's names are confirmed, and the `flowId` prefix set is known from `subscription.origin`. The one thing counting revealed that the ledger could not: **49% of recurring-generated tasks reference a template that no longer exists** — see [#35](https://github.com/stainii/task/issues/35)'s resolution, it is a constraint on ADR-0005's importer.
2. **HTML/SCSS were read only where behaviour lived in them.** A screen's exact layout is not captured; FE rows describe function, not pixels.
3. ~~**READMEs were not read** (RES-011).~~ **Closed by [#15](https://github.com/stainii/task/issues/15)** — all four in-scope READMEs read; findings folded into RES-011 and CON-002. They extended the deployment facts here and contradicted none.
4. ~~**The four recurring-tasks deployments are inferred**~~ **Closed by [#35](https://github.com/stainii/task/issues/35).** All four exist, all four have data, and the authoritative prefix list is `subscription.origin` in Mongo: **`Housagotchi`, `Health`, `Setlist`, `social-recurring-tasks`** — three capitalised, one not, and none equal to its database name. CON-002's warning that the prefix cannot be inferred from this repo stands; it no longer has to be, because the data names them.
5. **`task` fidelity is read from source, not exercised.** Where behaviour was verified by running it, this ledger defers to [`docs/repo-health.md`](repo-health.md).
