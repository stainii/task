import { expect, test } from '@playwright/test';

import {
  capture,
  openApp,
  openWitness,
  rowsFound,
  signIn,
  signInPrompt,
  SYNCED,
  typeToFind,
  uniqueName,
} from './app';

/**
 * **A refused token stalls the queue and asks; it does not throw the work away.**
 *
 * This is the exception carved out of ADR-0004's *`4xx` means the patch is permanently wrong*.
 * `401` and `403` say nothing about the patch, so under the unamended rule, coming back from a week
 * away with a dead refresh token would discard the entire week into the failed-to-sync pile — one
 * patch at a time, without ever naming authentication as the cause.
 *
 * So it queues **more than one** patch. A single one would pass against an outbox that dropped
 * everything but the head of the queue, which is exactly the shape of the loss being guarded
 * against: the rule is about a *week* of work, not about one write.
 *
 * A vitest can prove `Outbox` returns `'unauthenticated'`. It cannot prove that the queue is still
 * on the device after a **full-page redirect to Keycloak and back**, which is where a queue held in
 * memory rather than in IndexedDB would quietly evaporate. That is what this runs.
 */

// No session at all, which is what an expired one degrades to: `AuthService.token()` answers `null`
// either way, the server refuses either way, and the outbox sees the same `401`. A genuinely
// expired token cannot be produced here — the realm's lifespan is minutes and no test may sleep
// through it — and the client cannot tell the two apart.
test.use({ storageState: { cookies: [], origins: [] } });

test('a queue built without a session survives the login it triggers', async ({
  browser,
  page,
}) => {
  const first = uniqueName('stalled-first');
  const second = uniqueName('stalled-second');

  const witness = await openWitness(browser);

  await openApp(page);
  await capture(page, first);
  await page.keyboard.press('Escape');
  await capture(page, second);

  // The stall raised the prompt — and only because the device is online. It is a bar over a working
  // app, not a login screen: both captures are on screen behind it.
  await expect(signInPrompt(page)).toBeVisible();
  await typeToFind(witness, first);
  await expect(rowsFound(witness, first)).toHaveCount(0);

  await signInPrompt(page).click();
  await signIn(page);

  // Neither was lost across the redirect: the whole queue drains behind the login it asked for.
  await typeToFind(witness, first);
  await expect(rowsFound(witness, first)).toBeVisible(SYNCED);
  await typeToFind(witness, second);
  await expect(rowsFound(witness, second)).toBeVisible(SYNCED);
});
