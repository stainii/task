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
