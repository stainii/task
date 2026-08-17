import { expect, test } from '@playwright/test';

import {
  capture,
  completeByName,
  drained,
  offline,
  openApp,
  openWitness,
  renameFromToast,
  rowsFound,
  SYNCED,
  typeToFind,
  uniqueName,
} from './app';

/**
 * **Go offline, edit, complete, come back — and the outbox drains in order.**
 *
 * ADR-0004's outbox drains strictly in order, and that ordering is what makes *the first patch for
 * a task id creates it* safe. Reorder it and the rename arrives before the create, the server
 * answers `404`, and the outbox does exactly what it is told to do with a `404`: **drop it and
 * carry on**. The device shows the edit it made, the server never heard of it, and nothing anywhere
 * reports a fault — no error, no banner, no retry. That is why this needs an end-to-end witness
 * rather than a unit test.
 *
 * The witness is a **second browser**, watching the same server. Asserting on the device that made
 * the edits would prove nothing at all: local state is authoritative for display, so it shows the
 * same thing whether the patches landed or were dropped on the floor.
 *
 * **What it does not control, and now says so.** `openApp` waits for the shell and never for the
 * network, so `setOffline(true)` lands at whatever point the app happens to have reached — sometimes
 * with a Keycloak session established, sometimes with the silent `check-sso` still in flight. That is
 * deliberately not pinned: it is a real device's situation, both orders must work, and pinning it
 * would have hidden [#71](https://github.com/stainii/task/issues/71) rather than fixed it. The cost
 * is that *which* of the two this run exercised is not recorded anywhere, so a regression that only
 * breaks the interrupted-boot order is a **1-in-N red here** and a certainty in `auth.spec.ts` — the
 * suite is the witness that the browser really queues and replays, and never the place a shape is
 * pinned down.
 */
test('an offline create, edit and completion reach the server in the order they were made', async ({
  browser,
  page,
  context,
}) => {
  const original = uniqueName('ordered');
  const edited = `${original} renamed`;

  // Cold-booted and streaming before anything happens, so it sees the patches arrive rather than
  // being asked afterwards whether they did.
  const witness = await openWitness(browser);
  await typeToFind(witness, original);
  await expect(rowsFound(witness, original)).toHaveCount(0);

  await openApp(page);
  await context.setOffline(true);
  // Cut off for real, before anything is written: emulation is asynchronous, and a capture made a
  // moment too early would leave for the server and prove nothing.
  await offline(page);

  await capture(page, original);
  await renameFromToast(page, edited);

  // Still nothing on the server, which is the state the whole contract is about.
  await typeToFind(witness, edited);
  await expect(rowsFound(witness, edited)).toHaveCount(0);

  await context.setOffline(false);

  // The device's own report that it has nothing left to send, asserted before the witness so that a
  // client which stopped is blamed on the client rather than on the stream (#71).
  await drained(page);

  // The rename can only be found if the create landed *first*: a patch naming a task the server has
  // never heard of is a `404`, and a dropped one at that.
  await typeToFind(witness, edited);
  await expect(rowsFound(witness, edited)).toBeVisible(SYNCED);

  await context.setOffline(true);
  await offline(page);
  await completeByName(page, edited);
  await context.setOffline(false);
  await drained(page);

  // And the completion landed on that same task rather than on nothing: the row leaves the witness's
  // list, which holds open tasks only.
  await typeToFind(witness, edited);
  await expect(rowsFound(witness, edited)).toHaveCount(0, SYNCED);
});
