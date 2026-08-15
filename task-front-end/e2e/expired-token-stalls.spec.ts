import { expect, test } from '@playwright/test';

import { capture, openApp, openWitness, search, uniqueName } from './app';

/**
 * **A refused token stalls the queue and asks; it does not throw the work away.**
 *
 * This is the exception carved out of ADR-0004's *`4xx` means the patch is permanently wrong*.
 * `401` and `403` say nothing about the patch, so under the unamended rule, coming back from a week
 * away with a dead refresh token would discard the entire week into the failed-to-sync pile — one
 * patch at a time, without ever naming authentication as the cause.
 *
 * A vitest can prove `Outbox` returns `'unauthenticated'`. It cannot prove that the patch is still
 * on the device after a **full-page redirect to Keycloak and back**, which is where a queue held in
 * memory rather than in IndexedDB would quietly evaporate. That is what this runs.
 */

// No session at all, which is what an expired one degrades to: `AuthService.token()` answers `null`
// either way, and the server refuses the request either way.
test.use({ storageState: { cookies: [], origins: [] } });

test('a patch made without a session survives the login it triggers', async ({ browser, page }) => {
  const task = uniqueName('stalled');

  const witness = await openWitness(browser);

  await openApp(page);
  await capture(page, task);

  // The stall raised the prompt — and only because the device is online. It is a bar over a working
  // app, not a login screen: the capture is on screen behind it.
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await expect(await search(witness, task)).toHaveCount(0);

  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByLabel('Username or email').fill('stijnhooft@hotmail.com');
  await page.getByLabel('Password', { exact: true }).fill('test');
  await page.getByRole('button', { name: 'Sign In' }).click();

  // Nothing was lost across the redirect: the queue drains behind the login it asked for.
  await expect(await search(witness, task)).toBeVisible({ timeout: 60_000 });
});
