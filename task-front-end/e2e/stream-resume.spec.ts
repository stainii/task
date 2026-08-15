import { expect, test } from '@playwright/test';

import {
  capture,
  notSyncing,
  openApp,
  openWitness,
  renameFromToast,
  rowsFound,
  SYNCED,
  typeToFind,
  uniqueName,
} from './app';

/**
 * **Drop the stream mid-session, and resume with no gap and no duplicate.**
 *
 * ADR-0004 calls reconnect-and-resume "the most load-bearing and least observable mechanism in this
 * contract" — both of #12's SSE defects lived there — and it is the reason the server bounds every
 * connection to 15–30 minutes: the resume path has to run several times an hour on every device, or
 * it rots undetected. `stack.mjs` shrinks that bound to ten seconds, which is the knob
 * `application.yml` says exists for exactly this.
 *
 * Both halves of the promise fail silently, in opposite directions. A **gap** is a patch the device
 * never hears about, so it shows yesterday's task for ever and looks perfectly healthy. A
 * **duplicate** is a patch folded twice, which is invisible for most fields and wrong for the ones
 * that are not. Neither raises anything anywhere.
 *
 * The device under test is deliberately the *reader*: this is the read side, and asserting on the
 * writer would only prove the writer's own local state.
 */
test('a device that loses the stream catches up on exactly what it missed', async ({
  browser,
  page,
  context,
}) => {
  const mine = uniqueName('own');
  const theirs = uniqueName('missed');
  const renamed = `${theirs} renamed`;

  await openApp(page);

  // Its own patch, which the server echoes back on the stream. That echo is the acknowledgement
  // (ADR-0004), and it is also the standing chance to fold the same patch twice.
  await capture(page, mine);
  await typeToFind(page, mine);
  await expect(rowsFound(page, mine)).toHaveCount(1);

  // Only the stream is broken, and only its `GET`: `context.setOffline(true)` would not do, because
  // it leaves an *established* connection alone — the reader would go on receiving everything and
  // the test would prove nothing. Refusing the reconnect is what actually strands this device,
  // and the server's short lifetime is what makes the reconnect happen within seconds.
  await context.route('**/api/task-patches*', (route) =>
    route.request().method() === 'GET' ? route.abort() : route.continue(),
  );
  await expect(notSyncing(page)).toBeVisible(SYNCED);

  // The other device does its work while this one is not listening.
  const other = await openWitness(browser);
  await capture(other, theirs);
  await renameFromToast(other, renamed);
  await typeToFind(page, renamed);
  await expect(rowsFound(page, renamed)).toHaveCount(0);

  await context.unroute('**/api/task-patches*');
  await expect(notSyncing(page)).toBeHidden(SYNCED);

  // Exactly one row, carrying the name from the *second* patch: resuming from the persisted cursor
  // delivered both patches it missed, in order, and folded them once.
  await typeToFind(page, renamed);
  await expect(rowsFound(page, renamed)).toHaveCount(1, SYNCED);

  // And the resume did not re-deliver what this device already had as a second copy.
  await typeToFind(page, mine);
  await expect(rowsFound(page, mine)).toHaveCount(1);
});
