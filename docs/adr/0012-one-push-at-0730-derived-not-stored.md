# 12. One push at 07:30, derived not stored

Date: 2026-08-07

## Status

Accepted. Resolves [#34](https://github.com/stainii/task/issues/34).

Amends [ADR-0003](0003-two-modules-with-package-visibility-as-the-boundary.md) with a fifth module,
`notification`. See that ADR's *Amendments* section.

Drops `portal-email` for good, closing the last claim it had on this migration
([#15](https://github.com/stainii/task/issues/15) already dropped it as a service; this removes the
requirement that kept it alive).

Discharges the open pointer in `docs/portal-inventory.md` §8 — portal's `notification/` front-end
(17 files) stays out of scope permanently, and is no longer fog.

## Context

The ticket asked whether anything still needs to reach the author when the app is closed. Two
decisions had already narrowed it: [#13](https://github.com/stainii/task/issues/13) collapsed the
reminder→urgent escalation into one task created at `min` and due at `max`, so the final warning is
just a task going overdue and needs no channel; and ADR-0001 settled a model with no kind-specific
behaviour, so nothing in the shape of a task demands one either.

**Portal's channel was alive to the last day.** `portal-notifications` holds **8,201 notifications**,
running 50–70/month through July 2026, every one `published = true`, the last on 2026-08-04 — the day
the archive was taken. `portal-email` turned each into a mail to a personal Gmail address. This was
never a dead feature being tidied away.

**The author had already run the experiment on the busiest part of the system.** Of the five
subscriptions, `Todo`'s `activation_condition` is the literal string `false`. At some point todo
notifications were switched off deliberately rather than tuned.

**The in-app half died years before the email did.** Across the entire history, **336 of 8,201
notifications were ever marked read (4%)**; in 2026, **5 of 412**. The inbox is not a feature to
lose. It is one that was abandoned and kept being paid for in storage.

Asked directly what the 04:00 mail is worth, the author's answer was that it is not useful — **but
that a notification for tasks due today would be**. So the ticket's "no" was half right: the channel
portal built dies, and a narrower requirement survives it that portal never served.

**Portal never had push at all.** No `SwPush`, no VAPID, no `pushManager` anywhere in
`portal-front-end`; `ngsw-config.json` is cache-only. Every out-of-app notification portal ever sent
was email. This is new functionality, not a port.

## Decision

### One notification, 07:30, due today only

At **07:30 Europe/Brussels**, one Web Push notification listing the tasks due that day. If nothing is
due, nothing is sent.

**A task announces itself on its due day and never again.** An overdue task is silent. This is the
whole difference from the mail being dropped: portal re-sent every overdue item every morning until
it was done, which is how a daily signal becomes wallpaper — the reason ADR-0009 made *persistent*
load-bearing on its build-date banner. Overdue work is not lost; ADR-0006 shows **everything overdue
or due today, always, however many**, at the top of the overview.

Because a task is due-today on exactly one day, "due today only" *is* "announce once, ever", but
reached by a date comparison rather than by remembering what has already been sent. No per-device
sent-state, no sync problem. The same move ADR-0006 made on finding the due check was a state
comparison and not a calendar event.

**Content**: one notification, naming what fits — *"Due today: Vacuum the house, Call Jan, +1 more"*.
The naming is the one thing worth carrying over from the mail, which let the author judge from the
notification shade whether it mattered. A bare count forces the app open to learn anything, which is
a nag rather than information. It stays **one** notification: per-task notifications reintroduce
exactly the volume that made the mail wallpaper, and Android would stack them into a unit swiped away
as a unit.

**Tapping it opens the overview**, not a task detail. ADR-0006's always-visible band *is* the list
the notification is about, so a deep link would be a second route to the same rows — and routes
belong to [#37](https://github.com/stainii/task/issues/37).

### The timezone is pinned, not inherited

Both the schedule and the definition of "today" are evaluated in an **explicitly configured
`Europe/Brussels`**.

Nothing in `task` sets a timezone today: no `TZ` in compose, no `Clock` bean (TODO-043 is open),
`ZoneId.systemDefault()` throughout `DateTimeUtils`, `Task`, `RecurringTaskTemplate` and
`CreateDueTasks`, with two files carrying `@SuppressWarnings("JavaTimeDefaultTimeZone")` to
acknowledge it. The container default is UTC, so `0 0 4 * * *` actually fires at **06:00 local in
summer, 05:00 in winter**.

Tolerable for a batch job nobody watches; not tolerable for something that buzzes a phone. An
"07:30" push would land at 09:30 half the year, and the day boundary would roll at 02:00 local.

Relying on the base image happening to be UTC is *a guarantee that lives in code, broken by something
that lives in configuration* — this map's signature defect, now found in `ngsw-config.json`
([#15](https://github.com/stainii/task/issues/15)), nginx's SSE buffering
([#22](https://github.com/stainii/task/issues/22)), backup retention
([#35](https://github.com/stainii/task/issues/35)) and Docker's logging default (ADR-0009).

This gives **TODO-043's `Clock` bean a second, user-visible reason to exist**, alongside the
recurring scheduler's rebuild it was deferred into.

### Web Push, self-hosted, no service in between

VAPID keypair, `nl.martijndwars:web-push` (5.1.2, actively maintained, pulls in BouncyCastle as a JCE
provider) on the back end, Angular's `SwPush` on the front end. No push vendor, no third-party SDK.

**The expensive part was already paid for.** RES-013 made the PWA a **cutover blocker**
([#15](https://github.com/stainii/task/issues/15)) and ADR-0004 already requires a service worker and
IndexedDB. iOS's home-screen-install requirement is what normally sinks web push for a personal
project; here it is a constraint already accepted for offline reasons — and moot in practice, since
the author is on **Android/Chrome**, where a plain tab is granted `PushManager`.

A claim encountered while researching this — that Apple's DMA compliance left EU PWAs without push —
is **false**. Apple announced that in the iOS 17.4 beta (February 2024) and **reversed it in early
March 2024**; Home Screen web apps and their push support continue to work in the EU. Recorded
because it would have killed this option on wrong facts.

**A calendar feed (ICS) was the serious alternative and is rejected on security, not convenience.**
An ICS subscription cannot send an `Authorization` header, so it requires an unauthenticated URL
carrying a secret token. [ADR-0010](0010-a-tunnel-an-allowlist-and-a-role.md) settled that every
`/api` request requires the realm role `task-user`, deliberately and narrowly. A calendar feed would
punch a permanent unauthenticated hole through that posture to save one table and one library. Its
second flaw is that refresh cadence belongs to Google or Apple, not to us — external ICS feeds can
lag by up to 24h, so the one thing asked for, *today's* tasks, is the thing it can least guarantee.

### Nothing about a notification is stored

**No notification entity, no inbox, no history, no read state, no publish strategies.** The only
persisted state in this entire feature is one `PushSubscription` row per device.

A notification here is **a projection of the task list at 07:30**, not an object. Portal needed
8,201 rows with `read`, `published`, `cancelled_at`, `scheduled_at` and four publish strategies
because a notification was a *message in flight* between two services over RabbitMQ. Inside one
deployable reading its own tables there is nothing in flight and nothing to remember — the same
deletion ADR-0001 performed on `Execution`.

Storing what was sent was considered and refused: it is recoverable from the tasks themselves, whose
due dates and append-only patch history are kept anyway. A notification log would be a second, weaker
copy of a history we already have.

The in-app inbox does not return, on the author's own data: 4% read over eight years, 1.2% in 2026.

### The channel repairs itself; the server only prunes

A Web Push subscription is not permanent — Chrome expires them, clearing browser data kills them, and
the push service answers a dead one with `410 Gone`. Untreated, the failure is that notifications
simply stop and nothing says so, and the author concludes nothing was due for three weeks. That is
this map's recurring shape: ADR-0008's `backup-server.sh` printing "Backup completed" over a `zip`
that skipped all 311 files.

Two mechanisms, and they are the same fact observed from each end:

1. **The client re-reads its subscription on every app open** and, if it is missing or has changed,
   silently re-subscribes. This is where repair happens, and it covers the common case — Chrome
   quietly rotating an endpoint — without the author ever seeing anything. A banner appears **only if
   re-subscription fails** (permission actually revoked).
2. **The server deletes a subscription on `410 Gone`**, because a dead endpoint is garbage.

**The server does not raise a banner, and the reason is worth recording**: it would be unreachable.
A server-side "notifications are off on this device" message could only ever be seen when the app is
open, which is precisely the moment mechanism 1 has already re-subscribed. Reporting stays where the
repair is.

**A recurring *check your notifications* task was refused**, even though ADR-0008 chose exactly that
pattern for backups — and the difference is the point. A backup's health is **invisible to the app**:
nothing in `task` can observe whether the archive on the box is valid, so a human check is the only
instrument available. A push subscription's health is **directly observable from both ends**. Asking
a human to check something the machine already knows is `echo "Backup completed"` from the other
direction.

### No action buttons

The notification carries no *Complete* button, though Web Push supports them.

A completion is a patch, and ADR-0004 requires every write to enter the ordered outbox and be
acknowledged **only once durably stored** — an amendment ADR-0009 made precisely because *a failed
IndexedDB write looks exactly like a successful tick*. A service worker writing to that outbox with
no UI present is the hardest place in the system to honour that guarantee, and its failure would be
invisible. Deferred to [#38](https://github.com/stainii/task/issues/38) as a deliberate later pass
with a stated reason, not smuggled into the first version.

### A fifth module, `notification`

`PushSubscription` is a real aggregate with its own lifecycle — created on permission grant, deleted
on `410 Gone` — and unrelated to `Task`. That is the test ADR-0003 used.

Practically, the alternative would hand `task` a scheduler, a public registration endpoint, an
outbound internet call and **BouncyCastle**. ADR-0003 bought one property deliberately — **`task` has
no outbound module dependencies** — and that is how a core module rots.

The dependency runs `notification → task`, through a small purpose-built query port in the shape of
ADR-0003's `TaskOccurrences`: something like `tasksDueOn(LocalDate)`. `task` exposes, never reaches.

**No second application event is minted.** ADR-0002's tripwire was that the first event flowing
`task → template` forces a shared kernel; nothing here flows that way. ADR-0003's mechanical rule —
*an event cannot return a value, so queries go direct and facts go by event* — puts this squarely on
the query side. The modulith keeps exactly one application event, `TaskTemplateFired`.

### Permission is a toggle, never a prompt on arrival

Notification permission is requested from an **explicit toggle**, gesture-initiated, per device. It
is never asked for automatically.

Chrome treats a dismissed prompt harshly — repeated dismissal drops the origin into quieter
permissions, and recovering a denial means digging through site settings. The prompt is close to
one-shot, so it is spent on purpose.

Bundling it into first run would also rebuild a gate that was deliberately removed:
[#14](https://github.com/stainii/task/issues/14) amended ADR-0004 to **authenticate to sync, not to
see**, deleting `onLoad: 'login-required'` so a cold boot renders from IndexedDB without a token. A
soft in-app pre-prompt was refused as ceremony: that pattern exists to persuade users, and there is
one user, who asked for this feature.

**Every subscribed device is sent to.** No primary device, no dedup — one row per device, all of them
pushed. With one notification per day there is nothing to reconcile.

**Registration is a `/api` write** and therefore requires the realm role `task-user`, like everything
else under ADR-0010.

## Consequences

- **`portal-email` is dead with no residual claim.** [#15](https://github.com/stainii/task/issues/15)
  dropped it as a service while noting the requirement remained here; the requirement is now answered
  by something else entirely.
- **`docs/portal-inventory.md` §8's open pointer is discharged.** All 17 out-of-scope `notification/`
  front-end files stay out. [#14](https://github.com/stainii/task/issues/14) is unaffected — it never
  passed over notification UI, because the ledger had parked it here.
- **[#11](https://github.com/stainii/task/issues/11) inherits the build**: the `notification` module,
  `PushSubscription` and its repository, the VAPID configuration, the 07:30 scheduler, the
  `tasksDueOn` port on `task`, the `SwPush` wiring and the subscription toggle — plus a second driver
  for TODO-043's `Clock` bean.
- **[#37](https://github.com/stainii/task/issues/37) inherits a requirement**: there must be somewhere
  to put a notifications toggle. No settings surface has been designed.
- **[#38](https://github.com/stainii/task/issues/38) inherits "complete from the notification
  shade"** as an explicitly deferred candidate, with the durable-ack reason attached.
- **The VAPID private key is a secret whose loss invalidates every existing subscription.** It lives
  in the gitignored `.env` per [#31](https://github.com/stainii/task/issues/31), so
  [#26](https://github.com/stainii/task/issues/26)'s *restore config, not just data* requirement
  already covers it — and this is the first concrete artifact justifying that requirement. If it were
  lost anyway, the re-subscribe-on-open rule heals it silently.
- **An outbound internet dependency enters the deploy**, on Google's push service. It is not
  infrastructure we run, and ADR-0009's *no infrastructure at all* survives, but the delivery path now
  leaves the box.
- **Something ignored on its due day is never pushed again.** Accepted knowingly: the overview is the
  backstop, and it always shows every overdue item.
- **Nothing detects that pushes stopped being *generated*** — only that a subscription died. A
  scheduler that runs and computes wrongly is invisible here exactly as ADR-0009 stated for the due
  check, and the defence is the same: tests, on [#10](https://github.com/stainii/task/issues/10).

## Alternatives considered

- **Keep the daily email, on a nicer transport.** Rejected by the author directly: it is not useful.
  It is also the wallpaper failure ADR-0009 named.
- **A summary covering due-today *and* overdue.** Rejected: that is the mail, re-sent daily until
  done, which is what stopped being read.
- **Per-task notifications.** Rejected: reintroduces the volume, and Android stacks them anyway.
- **A calendar (ICS) feed.** Rejected on ADR-0010's posture and on refresh cadence belonging to the
  calendar vendor. See above.
- **The client holding a push until a chosen hour.** Rejected as broken rather than merely worse: a
  service worker cannot reliably wake itself on a timer — the Notification Triggers API never shipped
  — so it would work on desktop and fail silently on the phone, which is the device that matters.
- **Only sending what is newly due since the last notification.** Rejected: identical behaviour to
  due-today-only, at the cost of per-device sent-state and its own sync problem.
- **Putting the feature in `task`.** Rejected: spends ADR-0003's one deliberately bought property to
  save a package.
- **Storing sent notifications.** Rejected: a weaker second copy of the patch history.
- **A recurring "check your notifications" task.** Rejected: the machine already knows.

Note on ADR-0003: its *three or more modules* alternative was rejected, but what it rejected was
**splitting existing concerns** — firing out of `template`, patch/SSE out of `task`. This adds a
module for a new aggregate that did not exist when that was written. The reasoning is extended, not
contradicted.
