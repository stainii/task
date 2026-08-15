import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SwPush, SwUpdate } from '@angular/service-worker';
import { BehaviorSubject } from 'rxjs';

import { routes } from './app.routes';
import { Overview } from './pages/overview/overview';
import { Status } from './pages/status/status';
import { TaskPage } from './pages/task/task-page';
import { TemplateAuthoring } from './pages/template-authoring/template-authoring';
import { Templates } from './pages/templates/templates';

describe('routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes, withComponentInputBinding()),
        // Supplied by `provideServiceWorker` in the real app and by nothing here. Stubbed as
        // *present but doing nothing*, which is what a browser with no registered worker looks like.
        {
          provide: SwUpdate,
          useValue: { isEnabled: false, checkForUpdate: () => Promise.resolve(false) },
        },
        {
          provide: SwPush,
          useValue: { isEnabled: false, subscription: new BehaviorSubject(null) },
        },
      ],
    });
  });

  it.each([
    ['/', Overview],
    ['/in/house', Overview],
    ['/templates', Templates],
    ['/templates/1e0f', TemplateAuthoring],
    ['/task/1e0f', TaskPage],
    ['/status', Status],
  ])('resolves %s', async (url, component) => {
    const harness = await RouterTestingHarness.create();

    expect(await harness.navigateByUrl(url)).toBeInstanceOf(component);
  });

  it('binds the entered context from the URL, so it can be stored and deep-linked', async () => {
    const harness = await RouterTestingHarness.create();

    const overview = await harness.navigateByUrl('/in/house', Overview);

    expect(overview.value()).toBe('house');
  });

  it('leaves the axis out of the URL: it is /in/:value, never /c/:context', () => {
    const paths = routes.map((route) => route.path);

    expect(paths).toContain('in/:value');
    expect(paths.some((path) => path?.includes('context'))).toBe(false);
  });

  it('loads every destination eagerly — lazy loading stays dropped (FE-031)', () => {
    expect(routes.every((route) => !route.loadComponent && !route.loadChildren)).toBe(true);
  });
});
