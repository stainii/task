import { expect, test as setup } from '@playwright/test';

import { STORAGE_STATE } from '../playwright.config';

import { openApp, signIn, signInPrompt } from './app';

/**
 * Signs in once, and leaves the session where the `chromium` project can pick it up.
 *
 * **The app's own bar is the only way in**, and that is a fact about the design rather than a
 * choice made here: ADR-0004 forbids a login at boot — *authenticate to sync, not to see* — so
 * `AuthService` never redirects on its own, and the prompt appears only once a sync has actually
 * needed a token. Opening the app online with no session is enough to produce that: the stream asks
 * first, the server answers `401`, and the bar goes up.
 *
 * Keycloak's own account console would have been the more independent route, but it is a
 * single-page app that loads from `/resources/**`, which this origin does not route — and neither
 * does `src/proxy.conf.json`. The login *form* needs nothing but `/realms`, which is why it is the
 * one Keycloak surface this suite touches.
 */
setup('the sign-in bar leads to a session', async ({ page }) => {
  await openApp(page);

  // Not a boot-time redirect: this appears because a sync asked for a token and was refused.
  await signInPrompt(page).click();
  await signIn(page);

  // Back in the app, with the prompt withdrawn — the sync that raised it now has its token.
  await expect(signInPrompt(page)).toBeHidden();

  await page.context().storageState({ path: STORAGE_STATE });
});
