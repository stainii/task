import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { SwPush, SwUpdate } from '@angular/service-worker';
import { BehaviorSubject } from 'rxjs';

import { App } from './app';
import { routes } from './app.routes';
import { aTask } from './domain/task.mother';
import { BuildSkew } from './pwa/build-skew';
import { LocalStore } from './store/local-store';
import { SyncStatus } from './sync/sync-status';
import { Notices } from './ui/notices';
import { Confirms } from './ui/confirms';
import { Overlays } from './ui/overlays';
import { Toasts } from './ui/toasts';

/**
 * The service-worker surfaces, which `provideServiceWorker` supplies in the real app and nothing
 * supplies here. Both are stubbed as *present but doing nothing*, which is what a browser without a
 * registered worker actually looks like.
 */
const SERVICE_WORKER_STUBS = [
  {
    provide: SwUpdate,
    useValue: { isEnabled: false, checkForUpdate: () => Promise.resolve(false) },
  },
  {
    provide: SwPush,
    useValue: { isEnabled: false, subscription: new BehaviorSubject(null) },
  },
];

/**
 * The shell's two claims: every route in ADR-0014 resolves, and entering a context does not look
 * like leaving Tasks.
 */
/** A verb nothing in these tests presses: they are about the slot, not about what the offer does. */
function noop(): void {
  // Deliberately empty.
}

describe('App', () => {
  beforeEach(() => {
    // The shell starts sync, and sync opens the store. A fresh factory per test, so nothing shares
    // a database with anything else.
    globalThis.indexedDB = new IDBFactory();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes, withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
        ...SERVICE_WORKER_STUBS,
      ],
    });
  });

  async function navigate(url: string): Promise<HTMLElement> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('offers exactly the two destinations, and the omnibox', async () => {
    const shell = await navigate('/');

    const tabs = [...shell.querySelectorAll('nav.tabs a')].map((a) => a.textContent?.trim());
    expect(tabs).toEqual(['Tasks', 'Templates']);
    // The omnibox is really on the appbar, not a slot waiting for one (#60).
    expect(shell.querySelector('app-omnibox input.query')).toBeTruthy();
    expect(shell.querySelector('button.more')?.getAttribute('aria-label')).toBe('More');
  });

  it('carries FE-027’s indicator on the appbar, where nothing is wrong', async () => {
    // Its states are `queued-indicator.spec.ts`'s business. What only the shell can say is *where*
    // it is: inside `header.appbar`, beside the omnibox, and not in a band — a queued change is
    // healthy, and a band above the work is for something that is not.
    const shell = await navigate('/');

    expect(shell.querySelector('header.appbar app-queued-indicator')).toBeTruthy();
  });

  it.each([
    ['/', 'tasks'],
    ['/in/house', 'tasks'],
    ['/templates', 'templates'],
    ['/templates/abc', 'templates'],
    ['/status', 'elsewhere'],
  ])('marks %s as the %s destination', async (url, expected) => {
    const shell = await navigate(url);

    const active = shell.querySelector('nav.tabs a.active')?.textContent?.trim().toLowerCase();
    expect(active ?? 'elsewhere').toBe(expected);
  });

  it('keeps Tasks lit while a task dialog is open', async () => {
    // `/task/:id` is a dialog *over* the overview (ADR-0018), so it is the same destination for the
    // same reason `/in/:value` is: opening a task is not leaving Tasks. The task is really in the
    // store, because a dialog that redirects would land on `/` and pass this whatever the rule said.
    const task = aTask({ id: 'abc' });
    await TestBed.inject(LocalStore).receivePatches(task.history);

    const shell = await navigate('/task/abc');

    expect(shell.querySelector('nav.tabs a.active')?.textContent?.trim()).toBe('Tasks');
  });

  it('shows a notice from a screen that has already gone', async () => {
    // The redirect's whole point: `/task/:id` for a closed task sends you to the overview, and the
    // component that knows *why* is unmounted by the very navigation it is explaining.
    const shell = await navigate('/');
    TestBed.inject(Notices).say('Beddengoed wassen is already completed.');
    await TestBed.inject(ApplicationRef).whenStable();

    expect(shell.querySelector('.notice')?.textContent).toContain(
      'Beddengoed wassen is already completed.',
    );
  });

  it('sends an unknown URL back to the overview', async () => {
    await navigate('/nowhere');

    expect(TestBed.inject(Router).url).toBe('/');
  });

  /**
   * The overlay layer (#67): everything painted *over* the page, painted by the shell.
   *
   * Before this the omnibox painted its own toasts and its own confirm from inside `.appbar`, which
   * is `position: sticky; z-index: 5` and therefore a **stacking context**: every z-index those
   * overlays declared was clamped to 5 against the root, so `DateConfirm`'s scrim could not cover
   * the shell's notice while `aria-modal="true"` promised a screen reader that it did.
   */
  describe('the overlay layer', () => {
    it('paints the one toast slot, whichever screen raised it', async () => {
      const shell = await navigate('/');
      TestBed.inject(Toasts).show({
        kind: 'undoable',
        what: 'Completed — Beddengoed wassen',
        undo: noop,
      });
      await TestBed.inject(ApplicationRef).whenStable();

      const toast = shell.querySelector('.corner app-undo-toast');
      expect(toast?.textContent).toContain('Completed — Beddengoed wassen');
    });

    it('stands the notice and a toast in the corner side by side rather than on top of each other', async () => {
      const shell = await navigate('/');
      TestBed.inject(Notices).say('Beddengoed wassen is already completed.');
      TestBed.inject(Toasts).show({ kind: 'undoable', what: 'Completed — Iets', undo: noop });
      await TestBed.inject(ApplicationRef).whenStable();

      // One corner, laid out once. `app.css` claimed *"only one is ever up"* and nothing enforced
      // it; what enforces it now is that they share a stack rather than a coordinate.
      const corner = shell.querySelector('.corner');
      expect(corner?.querySelector('.notice')).toBeTruthy();
      expect(corner?.querySelector('app-undo-toast')).toBeTruthy();
    });

    it('paints the one confirm, and paints it outside the appbar', async () => {
      const shell = await navigate('/');
      void TestBed.inject(Confirms).ask('Beddengoed wassen', '2026-08-16');
      await TestBed.inject(ApplicationRef).whenStable();

      const confirms = shell.querySelectorAll('app-date-confirm');
      expect(confirms.length).toBe(1);
      // The defect in one assertion: inside `header.appbar` the scrim can never cover the notice,
      // whatever it declares.
      expect(shell.querySelector('header.appbar app-date-confirm')).toBeNull();
      expect(confirms[0].textContent).toContain('When did you do it?');
    });

    it('withdraws a standing confirm when the screen under it moves', async () => {
      // The confirm is the shell's now, so it is no longer destroyed with the component that asked.
      // Hardware back, ADR-0012's 07:30 push and any deep link can move the screen out from under
      // it — and what that would otherwise leave is an `aria-modal` dialog over a screen that never
      // asked, with a caller awaiting an answer that can no longer come.
      const shell = await navigate('/');
      const confirms = TestBed.inject(Confirms);
      const answered = confirms.ask('Beddengoed wassen', '2026-08-16');
      await TestBed.inject(ApplicationRef).whenStable();
      expect(shell.querySelector('app-date-confirm')).toBeTruthy();

      await TestBed.inject(Router).navigateByUrl('/templates');
      await TestBed.inject(ApplicationRef).whenStable();

      await expect(answered).resolves.toBeNull();
      expect(shell.querySelector('app-date-confirm')).toBeNull();
    });

    it('owns the only document-level Escape there is, and gives it to the topmost overlay', async () => {
      await navigate('/');
      const overlays = TestBed.inject(Overlays);
      const dismissed: string[] = [];
      overlays.open(() => dismissed.push('underneath'));
      overlays.open(() => dismissed.push('topmost'));

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

      expect(dismissed).toEqual(['topmost']);
    });
  });

  /**
   * **ADR-0009's whole detection strategy**, and it lives here because a banner has to *come to
   * you*. There is no Prometheus, no Grafana, no uptime pinger and no dead-man's switch; the app
   * reports on itself, on the surface with guaranteed attention.
   *
   * Neither banner has a threshold, deliberately: *warn if not synced for three days* cries wolf on
   * a holiday and stays silent through a week of bad signal. Both conditions are states, not
   * numbers.
   */
  describe('the two banners', () => {
    it('says nothing at all on a healthy day', async () => {
      const shell = await navigate('/');

      expect(shell.querySelector('.not-syncing')).toBeNull();
      expect(shell.querySelector('.build-skew')).toBeNull();
    });

    it('reports a working radio and a server that will not answer', async () => {
      // The first banner, and the one case ADR-0004 is built to conceal: the outbox stalls on `5xx`
      // by design and the PWA renders from IndexedDB regardless, so a back end dead for four days
      // and four days of poor signal are otherwise the same experience.
      const shell = await navigate('/');
      const status = TestBed.inject(SyncStatus);
      status.online.set(true);
      status.unreachable();
      await TestBed.inject(ApplicationRef).whenStable();

      expect(shell.querySelector('.not-syncing')?.textContent).toContain('not answering');
    });

    it('stays quiet in a tunnel, where there is nothing wrong to report', async () => {
      // Offline is the ordinary state of this app, not a fault. This is the half that makes the
      // first banner need no threshold at all.
      const shell = await navigate('/');
      const status = TestBed.inject(SyncStatus);
      status.online.set(false);
      status.unreachable();
      await TestBed.inject(ApplicationRef).whenStable();

      expect(shell.querySelector('.not-syncing')).toBeNull();
    });

    it('reports a build-date mismatch the service worker has already failed to fix', async () => {
      // Half a deploy: one container recreated and the other not. ADR-0007 tags both images with one
      // commit SHA precisely because the fold lives in two languages, but nothing verified that at
      // runtime until this line.
      const shell = await navigate('/');
      TestBed.inject(BuildSkew).persistentMismatch.set(true);
      await TestBed.inject(ApplicationRef).whenStable();

      expect(shell.querySelector('.build-skew')?.textContent).toContain('different versions');
    });
  });
});
