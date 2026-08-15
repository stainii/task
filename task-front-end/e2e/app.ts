import { Browser, expect, Locator, Page } from '@playwright/test';

import { STORAGE_STATE } from '../playwright.config';

/**
 * The app, driven the way a person drives it.
 *
 * Every helper here goes through the real surface — the omnibox, the create toast, the edit dialog
 * — and never through IndexedDB, a service or an injected token. That is the whole point of this
 * suite: the vitest suites already assert the outbox's and the fold's *rules* directly, so a
 * Playwright test that reached into the store would be paying browser prices for a unit test.
 *
 * **Nothing here reads the overview's list of panels.** The stack is shared and the database holds
 * years of real tasks, so whether a given row is on screen depends on the band cap and on which
 * folds happen to be open — which is #58's subject and none of this suite's business. Finding a
 * task by typing its name is the surface that answers *does this device know about this task?*
 * regardless, and it is one of the omnibox's three stated jobs.
 */

/**
 * A name nothing else in the database can be confused with.
 *
 * `docs/quality-bar.md` and #10's 2071 patch: the stack is shared and nothing is cleaned between
 * runs, so **assert only on data you created, by name or by id**. Counting rows, or asserting an
 * overview is empty, would pass alone and fail the moment a second test had ever run.
 */
export function uniqueName(what: string): string {
  return `e2e ${what} ${Math.random().toString(36).slice(2, 10)}`;
}

/** The appbar's one input (ADR-0014). */
export function omnibox(page: Page): Locator {
  return page.getByRole('textbox', { name: 'Add, find, or say what you did' });
}

/** Opens the overview and waits for the shell — never for the network. */
export async function openApp(page: Page): Promise<void> {
  await page.goto('/');
  await expect(omnibox(page)).toBeVisible();
}

/**
 * A second device, in its own context, signed in and cold.
 *
 * The point of it is that its storage is empty and its network is its own: what it shows came from
 * the server and nowhere else, which is what makes it a witness rather than an echo. `newPage()` on
 * the browser would not do — it opens a context with no session, and an unauthenticated witness
 * sees nothing and agrees with everything.
 */
export async function openWitness(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ storageState: STORAGE_STATE });
  const page = await context.newPage();
  await openApp(page);
  return page;
}

/**
 * How long a patch is given to cross the wire.
 *
 * **Derived, not picked.** `SyncService.MAX_BACKOFF_MS` is 60 seconds, so a stalled outbox can sit
 * a full minute between attempts. Coming back online is *supposed* to cut that short —
 * `SyncService` wakes the pump on the browser's `online` event — but that event is the one part of
 * this the test harness emulates rather than causes, and on a loaded CI runner it does not always
 * arrive. Then the queue drains on its own timer, and anything at or under 60 seconds fails a
 * client that is behaving exactly as ADR-0004 says it should. This was measured: 60s was green on a
 * laptop for a week and red on the runner.
 *
 * Twice the cap, so a wait that expires means the patch is not coming at all.
 */
export const SYNCED = { timeout: 120_000 };

/** Types a name into the omnibox — *find*, the second of its three jobs (ADR-0014). */
export async function typeToFind(page: Page, name: string): Promise<void> {
  await omnibox(page).fill(name);
}

/**
 * The dropdown rows for an open task of this name — a live answer to *does this device hold it?*
 *
 * The query is left in the box on purpose, and the dropdown recomputes from the store whenever sync
 * says it changed. That is what lets a caller wait on a patch *arriving* rather than polling with
 * reloads.
 */
export function rowsFound(page: Page, name: string): Locator {
  return page.locator('.offers .suggestion').filter({ hasText: name });
}

/**
 * Signs in through Keycloak's login form, wherever the app has just sent the browser.
 *
 * The credentials are the ones in `task-back-end/compose/keycloak/realm-export.json` — a
 * **deliberately worthless dev fixture**, never the production realm (#31). Nothing here is a
 * secret, which is what lets a pull request from a fork run this suite in full (`docs/ci.md` §5).
 */
export async function signIn(page: Page): Promise<void> {
  await page.getByLabel('Username or email').fill('stijnhooft@hotmail.com');
  await page.getByLabel('Password', { exact: true }).fill('test');
  await page.getByRole('button', { name: 'Sign In' }).click();
}

/** The bar a stalled sync raises, and the only way into a session (ADR-0004). */
export function signInPrompt(page: Page): Locator {
  return page.getByRole('button', { name: 'Sign in' });
}

/**
 * ADR-0009's first banner: **online, but the server is not answering.**
 *
 * The suite waits on it as the app's own report that a connection has actually been lost, which is
 * the only honest way to know that `setOffline(true)` has taken effect on a stream that was already
 * open. Asserting the *absence* of data instead would be a race that usually passes.
 *
 * It doubles as the one end-to-end exercise of that banner, whose entire job is to appear when
 * ADR-0004's sync stalls silently by design.
 */
export function notSyncing(page: Page): Locator {
  return page.locator('.banner.not-syncing');
}

/**
 * Waits until the browser in this page agrees that the radio is off.
 *
 * `setOffline(true)` is asynchronous emulation, and a write made before it lands would leave for
 * the server and prove nothing. The browser's own `navigator.onLine` is the right thing to ask
 * because it is the same answer `SyncStatus` keys off.
 *
 * There is deliberately **nothing on screen** to wait for instead. ADR-0009's not-syncing banner is
 * for *online and not syncing*; a train tunnel is `online === false` and says nothing, so an app
 * that showed something here would be crying wolf every time a phone went through one.
 */
export async function offline(page: Page): Promise<void> {
  await page.waitForFunction(() => !navigator.onLine);
}

/**
 * Turns a first visit into a **returning** one, with the service worker actually serving the page.
 *
 * Registration is `registerWhenStable:30000` (#62), and a controlling worker is not the same thing
 * as a worker in charge of navigations: for a while after it installs, `ngsw` still passes the
 * document straight to the network. Offline that is a 504 and an empty tab — measured, repeatedly,
 * and it is why this reloads rather than polls for a state.
 *
 * The reload is not a workaround, it is the situation. ADR-0004's promise is about "every cold boot
 * **after the first**"; a first visit is the one where the app is being installed, and no user's
 * cold start is the millisecond after that.
 *
 * (The state-polling version of this is a trap worth naming: `page.waitForFunction` tests its
 * result for truthiness **without awaiting it**, so an `async` predicate hands it a Promise, which
 * is always truthy, and the wait returns at once. Every run reported the worker ready in 40ms and
 * then failed.)
 */
export async function serviceWorkerReady(page: Page): Promise<void> {
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null, undefined, {
    timeout: 60_000,
  });

  const served = await page.reload();
  expect(served?.fromServiceWorker()).toBe(true);
  await expect(omnibox(page)).toBeVisible();
}

/** Captures a task by typing. Leaves the create toast up — it is the way to the details. */
export async function capture(page: Page, name: string): Promise<void> {
  await omnibox(page).fill(name);
  await omnibox(page).press('Enter');
  await expect(page.locator('.created')).toContainText(name);
}

/**
 * Renames the task the create toast is about, through the edit dialog it offers.
 *
 * *Add details* is the intended path off a capture (ADR-0018): the dialog stays shut for the
 * ordinary case, and this is the door for when it does not. It is also the only edit surface that
 * does not require finding the task's row on the overview first.
 */
export async function renameFromToast(page: Page, to: string): Promise<void> {
  await page.locator('.created .details').click();

  const dialog = page.locator('section.dialog');
  await expect(dialog).toBeVisible();
  await dialog.locator('[data-field="name"]').fill(to);
  await dialog.getByRole('button', { name: 'Save' }).click();

  await expect(dialog).toBeHidden();
}

/**
 * Completes a task by name — ADR-0014's *say what you did*.
 *
 * Both capture paths meet at one confirm before anything is written, so the date is answered here
 * rather than assumed. Accepting the date it offers is accepting *today*, which is what a task
 * completed in front of the app means.
 */
export async function completeByName(page: Page, name: string): Promise<void> {
  await typeToFind(page, name);
  await rowsFound(page, name).click();

  const confirm = page.locator('.confirm-dialog');
  await expect(confirm).toContainText(name);
  await confirm.getByRole('button', { name: 'Done' }).click();

  await expect(confirm).toBeHidden();
}
