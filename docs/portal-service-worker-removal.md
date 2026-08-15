# Removing portal's service worker from every device

**Owner: [#17](https://github.com/stainii/task/issues/17), step 4 of
[ADR-0005](adr/0005-migration-by-replay-into-one-history.md)'s cutover sequence.** Written by
[#62](https://github.com/stainii/task/issues/62), the ticket that installs `task`'s own service
worker, because that is where the knowledge of how one behaves lives — but it is **not code in this
repo**, which is exactly why it needs writing down and an owner.

## Why this is a data-loss step and not tidying up

Portal is an installed PWA with a service worker (RES-013). A service worker outlives the site that
registered it: it keeps serving portal's cached shell from disk, so the app **keeps launching and
keeps accepting input** long after portal's server is gone. Portal is also offline-first, so those
writes do not fail loudly — they queue.

They queue into a void. Portal's back end no longer exists, and nothing in `task` will ever read
that queue. The device shows a working app, the writes look accepted, and they are lost. This is a
silent data-loss channel that **outlives the service**, and the only thing that closes it is
unregistering the worker on each device by hand.

Two properties make it worse than it sounds:

- **Uninstalling the app is not enough on every platform.** The registration is per origin, not per
  install, so a browser that still holds the origin can re-serve it.
- **It cannot be fixed remotely once portal is down.** The usual remedy — deploy a
  [self-unregistering worker](https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerRegistration/unregister)
  that calls `registration.unregister()` and clears its caches — needs portal's origin to still be
  serving. So if it is going to be used, it must be deployed **while portal is still up**, at step 2
  of the sequence, and every device must be opened once afterwards. Doing it by hand per device
  needs no deploy and is the recommended path for a handful of personal devices.

## Order

This runs at **step 4**, after the importer's diff report has been read and the decision to cut over
has been made, and **before the new app is installed** on that device. Not earlier: unregistering
portal's worker takes away the device's ability to work offline on portal, and if the diff report
says *abort*, portal is still the live system.

## Per device

Do this on **every** device that has ever opened portal — not only the ones with it installed. Work
from a written list; a forgotten tablet is exactly the case this exists for.

1. **Open portal once while its server is still up** (step 1 of the sequence) and confirm the outbox
   is empty. Nothing below recovers a queued write — it deletes it.
2. Uninstall the installed app, if it is installed.
3. Unregister the worker and clear its storage for portal's origin:

   - **Chrome / Edge (desktop)**: `chrome://serviceworker-internals` → find the origin →
     **Unregister**. Then DevTools → Application → Storage → **Clear site data**.
   - **Chrome (Android)**: Settings → Site settings → All sites → portal's origin → **Delete data**.
   - **Safari (macOS)**: Settings → Privacy → Manage Website Data → portal's origin → **Remove**.
   - **Safari (iOS/iPadOS)**: delete the home-screen app, then Settings → Safari → Advanced →
     Website Data → portal's origin → **Delete**. Deleting the home-screen app alone leaves the
     registration behind.
   - **Firefox**: `about:debugging#/runtime/this-firefox` → Service Workers → **Unregister**, then
     clear cookies and site data for the origin.

4. **Verify, do not assume.** With portal's server already stopped, open portal's URL on the device.
   A network error is the pass. **The app rendering is the failure** — the worker is still there and
   still serving, and the device is still a data-loss channel.

## Then install `task`

Installing `task` is ordinary: open its origin and accept the install prompt. Its own worker is
governed by `task-front-end/ngsw-config.json`, whose `dataGroups` is empty on purpose — see
`task-front-end/README.md`.

**Installing it does nothing to portal's worker.** [ADR-0010](adr/0010-a-tunnel-an-allowlist-and-a-role.md)
puts `task` on `task.stijnhooft.be` and portal is on `portal.stijnhooft.be`, so they are **different
origins**: a service worker's scope is its own origin, and `task`'s can neither see nor replace
portal's. The two apps sit side by side on the device until portal's is removed by hand. This
document is the whole of the removal — there is no mechanism doing any part of it.
