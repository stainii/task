import { expect, test } from '@playwright/test';

import {
  capture,
  completeByName,
  offline,
  openApp,
  openWitness,
  renameFromToast,
  search,
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
  await expect(await search(witness, original)).toHaveCount(0);

  await openApp(page);
  await context.setOffline(true);
  // Cut off for real, before anything is written: emulation is asynchronous, and a capture made a
  // moment too early would leave for the server and prove nothing.
  await offline(page);

  await capture(page, original);
  await renameFromToast(page, edited);

  // Still nothing on the server, which is the state the whole contract is about.
  await expect(await search(witness, edited)).toHaveCount(0);

  await context.setOffline(false);

  // The rename can only be found if the create landed *first*: a patch naming a task the server has
  // never heard of is a `404`, and a dropped one at that.
  await expect(await search(witness, edited)).toBeVisible({ timeout: 60_000 });

  await context.setOffline(true);
  await offline(page);
  await completeByName(page, edited);
  await context.setOffline(false);

  // And the completion landed on that same task rather than on nothing: the row leaves the witness's
  // list, which holds open tasks only.
  await expect(await search(witness, edited)).toHaveCount(0, { timeout: 60_000 });
});
