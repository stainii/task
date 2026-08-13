import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { App } from './app';
import { routes } from './app.routes';

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
    ['/task/abc', 'elsewhere'],
    ['/status', 'elsewhere'],
  ])('marks %s as the %s destination', async (url, expected) => {
    const shell = await navigate(url);

    const active = shell.querySelector('nav.tabs a.active')?.textContent?.trim().toLowerCase();
    expect(active ?? 'elsewhere').toBe(expected);
  });

  it('sends an unknown URL back to the overview', async () => {
    await navigate('/nowhere');

    expect(TestBed.inject(Router).url).toBe('/');
  });
});
