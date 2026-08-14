import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { App } from './app';
import { routes } from './app.routes';
import { aTask } from './domain/task.mother';
import { LocalStore } from './store/local-store';
import { Notices } from './ui/notices';

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
    expect(shell.querySelector('.omnibox-slot')).toBeTruthy();
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
});
