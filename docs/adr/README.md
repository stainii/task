# Decision log

Every architectural decision on this project, in the order it was taken. This index exists so the
*why* is findable without opening twenty files — read the line, then open the one ADR that answers
your question.

Each ADR records the decision **and what it cost to find**: the options that were put and rejected,
and the facts that were established rather than assumed. When a later decision changes an earlier
one, it amends it in place rather than superseding it silently, so an ADR you are reading is the
current answer.

**Adding one?** Number it next, name the file after the decision rather than the topic, and add its
line below. `DecisionLogIndexTest` in the back-end suite fails if you forget — an index nobody can
trust is worse than none.

| # | Decision | In one line | Resolves |
|---|---|---|---|
| 1 | [One task aggregate, with templates that fire on a trigger](0001-one-task-aggregate-with-triggered-templates.md) | One `Task`, no subtypes; one `TaskTemplate` absorbs `RecurringTaskTemplate`, and `Execution` is derived rather than stored. | [#2](https://github.com/stainii/task/issues/2) |
| 2 | [One application event, published as a fact](0002-one-application-event-published-as-a-fact.md) | Modules talk by application event, never by direct call; events are past-tense facts named in the publisher's domain. | [#5](https://github.com/stainii/task/issues/5) |
| 3 | [Two modules, with package visibility as the boundary](0003-two-modules-with-package-visibility-as-the-boundary.md) | Four modules — `task`, `template`, `config`, `goal` — with the boundary enforced by package visibility and `ApplicationModules.verify()`. | [#6](https://github.com/stainii/task/issues/6) |
| 4 | [One write verb, two clocks: the offline sync contract](0004-one-write-verb-two-clocks-offline-sync.md) | `POST /api/task-patches` is the only write; the client's clock orders patches, the server's `sequence` drives resync. The whole offline model. | [#7](https://github.com/stainii/task/issues/7) |
| 5 | [Migration by replay into one history](0005-migration-by-replay-into-one-history.md) | Portal's data arrives as replayed patches, not rows, and the importer proves itself with a stored-vs-folded diff report. | [#8](https://github.com/stainii/task/issues/8) |
| 6 | [One overview, grouped by a swappable axis](0006-one-overview-grouped-by-a-swappable-axis.md) | One screen for everything you might do, ranked, capped to visible work, grouped by an axis you can swap. Phone and desktop are both primary. | [#9](https://github.com/stainii/task/issues/9) |
| 7 | [The box pulls, nightly, behind a dump](0007-the-box-pulls-nightly-behind-a-dump.md) | Deploy target and pipeline: the local server pulls, behind the Cloudflare Tunnel, with a pre-deploy dump as part of the deploy unit. | [#22](https://github.com/stainii/task/issues/22) |
| 8 | [Every backup restores itself before it is kept](0008-every-backup-restores-itself-before-it-is-kept.md) | Three copies, and a backup only counts once it has restored itself. Includes rebuilding the box from nothing. | [#26](https://github.com/stainii/task/issues/26) |
| 9 | [The app is its own monitor](0009-the-app-is-its-own-monitor.md) | No observability stack at all: two self-reported facts and two threshold-free banners inside the app. | [#27](https://github.com/stainii/task/issues/27) |
| 10 | [A tunnel, an allowlist, and a role](0010-a-tunnel-an-allowlist-and-a-role.md) | The security posture: no inbound port, nginx default-deny as the whole exposure surface, and the `task-user` realm role on every `/api` request. | [#28](https://github.com/stainii/task/issues/28) |
| 11 | [Completion is a task fact, which the template reads](0011-completion-is-a-task-fact-the-template-reads.md) | Two anchors rather than one, so a recurring template cannot compose into a loop with its own firing rule. | [#33](https://github.com/stainii/task/issues/33) |
| 12 | [One push at 07:30, derived not stored](0012-one-push-at-0730-derived-not-stored.md) | One Web Push a day listing what is due today; a task announces itself on its due day and never again. | [#34](https://github.com/stainii/task/issues/34) |
| 13 | [One anchor, and a trigger that shapes the form](0013-one-anchor-and-a-trigger-that-shapes-the-form.md) | Template authoring is one screen whose trigger picker — by hand / every so often / on the calendar — swaps the fields beneath it. | [#36](https://github.com/stainii/task/issues/36) |
| 14 | [Two destinations, and you capture by typing](0014-two-destinations-and-you-capture-by-typing.md) | The navigation model: Tasks and Templates, status behind `⋯`, and the omnibox as the way in. | [#37](https://github.com/stainii/task/issues/37) |
| 15 | [Postpone pushes the start date, and the fold speaks](0015-postpone-pushes-the-start-date-and-the-fold-speaks.md) | *Not today* moves the start date and never the due date, so postponing frees a visible-work slot without lying about the deadline. | [#38](https://github.com/stainii/task/issues/38) |
| 16 | [The due check ticks hourly, and starting the app is one of the ticks](0016-the-due-check-ticks-hourly-and-starts-with-the-app.md) | The scheduler is one `@Scheduled` annotation, and a restart catches up rather than waiting an hour. | [#40](https://github.com/stainii/task/issues/40) |
| 17 | [A calendar template fires for its latest date you have not already closed](0017-a-calendar-template-fires-for-its-latest-unclosed-date.md) | However long the outage, a missed calendar template returns exactly once, anchored on the date it should have fired. | [#41](https://github.com/stainii/task/issues/41) |
| 18 | [A flat dialog on a route, and *today* is the un-postpone](0018-a-flat-dialog-on-a-route-and-today-is-the-un-postpone.md) | Task edit is a dialog, routed at `/task/:id` so it is addressable and the back button works. | [#42](https://github.com/stainii/task/issues/42) |
| 19 | [Verbs are glyphs, facts are words](0019-verbs-are-glyphs-facts-are-words.md) | The visual rule for every screen: actions are drawn, information is written, and no glyph stands in for a fact. | [#43](https://github.com/stainii/task/issues/43) |
| 20 | [One overlay layer, and one owner of Escape](0020-one-overlay-layer-and-one-owner-of-escape.md) | Every overlay is painted by the shell, and the app has exactly one `document:keydown.escape`. | [#67](https://github.com/stainii/task/issues/67) |
