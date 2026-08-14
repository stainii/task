import { Routes } from '@angular/router';

import { Overview } from './pages/overview/overview';
import { Status } from './pages/status/status';
import { TaskPage } from './pages/task/task-page';
import { TemplateAuthoring } from './pages/template-authoring/template-authoring';
import { Templates } from './pages/templates/templates';

/**
 * Everything is a route (ADR-0014), because ADR-0012's 07:30 push must land somewhere, FE-014's
 * last-used-context restore becomes a stored URL rather than bespoke state, and an installed PWA's
 * hardware back must leave the *context*, not the app.
 *
 * `/in/:value`, not `/c/:context`: ADR-0006 makes the grouping axis a property of the card row
 * alone, and the route is the one place that promise would leak into every stored URL and deep
 * link. Recorded as decided-by-recommendation; reverting is a one-line change.
 *
 * **Lazy loading stays dropped** (FE-031). It existed because there were seven apps behind a menu,
 * and there are now two destinations — so every component here is imported eagerly.
 */
export const routes: Routes = [
  { path: '', component: Overview, title: 'Tasks' },
  { path: 'in/:value', component: Overview, title: 'Tasks' },
  { path: 'templates', component: Templates, title: 'Templates' },
  { path: 'templates/:id', component: TemplateAuthoring, title: 'Template' },
  {
    // The guard is how **hardware back** gets asked about. A component sees the scrim and Escape;
    // it cannot see a `popstate`, and ADR-0018 lists all three together as accidental dismissals.
    path: 'task/:id',
    component: TaskPage,
    title: 'Task',
    canDeactivate: [(dialog: TaskPage) => dialog.canLeave()],
  },
  { path: 'status', component: Status, title: 'Status' },
  { path: '**', redirectTo: '' },
];
