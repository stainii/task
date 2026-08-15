import { expect, test } from '@playwright/test';

import {
  capture,
  offline,
  openApp,
  rowsFound,
  serviceWorkerReady,
  signInPrompt,
  typeToFind,
  uniqueName,
} from './app';

/**
 * **A cold start with no radio and no token still shows you your tasks.**
 *
 * This is ADR-0004's headline promise — *authenticate to sync, not to see* — and it is the reason
 * `app.config.ts` has no Keycloak provider, `AuthService` never redirects on its own, and
 * `serviceWorkerOptions` refuses to let the worker race the first paint. Four separate pieces of
 * design, each of which looks arbitrary on its own, and **only this test asks whether they add up.**
 *
 * A unit test cannot: the thing being proved is that the *document* is served by the service worker
 * while the network is off and the store answers before anything authenticates. There is no seam
 * inside the app where that is visible.
 */
test('the app cold-starts from IndexedDB with no network and no session', async ({
  page,
  context,
}) => {
  const task = uniqueName('cold');

  await openApp(page);
  await serviceWorkerReady(page);
  await capture(page, task);

  // Signing out is not enough on its own — the point is a device that has *nothing*: no session
  // cookie to check, and no server to check it against.
  await context.clearCookies();
  await context.setOffline(true);
  await offline(page);

  const reload = await page.reload();

  // Said out loud, because "the page rendered" would also be true of a test that was never offline:
  // the document itself was answered by the worker, not by a server.
  expect(reload?.fromServiceWorker()).toBe(true);

  // The shell came from the worker and the task came from IndexedDB. Neither asked anything.
  await typeToFind(page, task);
  await expect(rowsFound(page, task)).toBeVisible();

  // And it did not turn a dead radio into a login screen. Offline there is nothing to prompt for,
  // so the bar the sync stall raises must stay down.
  await expect(signInPrompt(page)).toBeHidden();
});
