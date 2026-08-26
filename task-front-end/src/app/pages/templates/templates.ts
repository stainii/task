import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  PendingTasks,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { NOW } from '../../clock';
import { IsoDate, today } from '../../domain/dates';
import { undoPatch } from '../../domain/patches';
import { Task, TaskPatch } from '../../domain/task';
import { TaskTemplate } from '../../domain/template';
import { didItPatches } from '../../domain/template-completion';
import { templateRowMatches, templateRows, TemplateRow } from '../../domain/templates';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { TemplateService } from '../../sync/templates';
import { GlyphButton } from '../../ui/glyph-button';
import { Confirms } from '../../ui/confirms';
import { Overlays } from '../../ui/overlays';
import { Toast, Toasts } from '../../ui/toasts';
import { dueLabel, lastDoneLabel } from '../../ui/wording';
import { ContextGroupingPrototypeSwitcher } from './context-grouping-prototype-switcher';

/**
 * PROTOTYPE (issue #80) — one context's slice of the list, sorted alphabetically within.
 * Stands in for whatever `domain/templates.ts` grows once a variant is picked; the settled
 * ordering decision (context outer, alphabetical inner, due/quiet dropped) is #76's, not #80's.
 */
interface ContextGroup {
  readonly context: string;
  readonly rows: readonly TemplateRow[];
}

/** PROTOTYPE (issue #80) — groups `rows` by `template.context`, each group alphabetical by name. */
function groupByContext(rows: readonly TemplateRow[]): readonly ContextGroup[] {
  const byContext = new Map<string, TemplateRow[]>();
  for (const row of rows) {
    const group = byContext.get(row.template.context);
    if (group) {
      group.push(row);
    } else {
      byContext.set(row.template.context, [row]);
    }
  }
  return [...byContext.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([context, groupRows]) => ({
      context,
      rows: [...groupRows].sort((left, right) => left.template.name.localeCompare(right.template.name)),
    }));
}

/** Which definition a ✓ is about, once one has been chosen. */
interface Chosen {
  readonly row: TemplateRow;
  readonly definitionIndex: number;
  /** What is being marked done, in words — the confirm shows it, and so does the toast after. */
  readonly what: string;
}

/**
 * **The templates list — the reminding surface**
 * ([ADR-0014](../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md)).
 *
 * The omnibox assumes you know what you did. This screen is for the other mood: every row carries
 * **when it was last done, as an elapsed count *and* a date**, and a **✓**. The two halves of that
 * date are load-bearing — *798 days ago* is arithmetic and *7 Jun '24* is a memory — and it is what
 * killed the pruning policy ADR-0019 records.
 *
 * **Not-yet-due first.** A template with an open task is already asking, on the overview; the ✓
 * exists for the ones showing nothing. On the author's real data that is 28 rows against 16.
 *
 * **It is also where templates stop being setup furniture.** ADR-0013's named risk was "a screen you
 * open when you set something up and then do not touch for months"; the ✓ gives it a weekly job.
 */
@Component({
  selector: 'app-templates',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, GlyphButton, ContextGroupingPrototypeSwitcher],
  templateUrl: './templates.html',
  styleUrl: './templates.css',
})
export class Templates {
  private readonly store = inject(LocalStore);
  private readonly sync = inject(SyncService);
  private readonly templates = inject(TemplateService);
  private readonly now = inject(NOW);
  private readonly overlays = inject(Overlays);
  private readonly confirms = inject(Confirms);
  private readonly toasts = inject(Toasts);
  private readonly route = inject(ActivatedRoute);

  /** PROTOTYPE (issue #80) — which context-grouping variant `?variant=A|B|C` selects. */
  protected readonly variant = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('variant') ?? 'A')),
    { initialValue: this.route.snapshot.queryParamMap.get('variant') ?? 'A' },
  );

  /** PROTOTYPE (issue #80), variant B only — which context groups the user has collapsed. */
  protected readonly collapsed = signal<ReadonlySet<string>>(new Set());

  /**
   * Angular's own register of work in flight.
   *
   * The reload below is started from an effect and not awaited by anyone, so without this it is
   * invisible to `whenStable` — which means a test asserting on the rendered rows is really
   * asserting on how many microtasks a helper happened to flush. Registering the read is the
   * documented way to say *this screen is not settled yet*, and it costs nothing at runtime.
   */
  private readonly pending = inject(PendingTasks);

  /** ADR-0004: editing is **visibly** unavailable offline, rather than a save that goes nowhere. */
  protected readonly writable = this.templates.writable;

  private readonly held = signal<readonly Task[]>([]);
  private readonly heldTemplates = signal<readonly TaskTemplate[]>([]);

  /**
   * The date this screenful is measured against, re-read whenever the rows are.
   *
   * A `signal` rather than a `computed` over `NOW`, which is a plain function and so has no
   * dependency to invalidate: #60 shipped that bug and it wrote **yesterday** into `completedOn` on
   * a tab left open across midnight. Every date on this screen — the elapsed counts, the overdue
   * words, and the confirm's default — comes from here.
   */
  private readonly asOf = signal<IsoDate>(today(this.now()));

  protected readonly today = this.asOf.asReadonly();

  /** ADR-0013's escape hatch. Deactivated templates are hidden, never deleted. */
  protected readonly showInactive = signal(false);

  /** The search bar's live text (#78/#79). */
  protected readonly query = signal('');

  protected readonly rows = computed(() => {
    const rows = templateRows(this.heldTemplates(), this.held(), this.today(), {
      includeInactive: this.showInactive(),
    });
    return rows.filter((row) => templateRowMatches(row, this.query()));
  });

  /** PROTOTYPE (issue #80) — `rows()` grouped by context, for all three variants. */
  protected readonly groups = computed(() => groupByContext(this.rows()));

  /**
   * PROTOTYPE (issue #80), variant B only — a group is expanded unless the user collapsed it, or
   * the search has live text: a collapsed group would otherwise hide matches inside it, which is
   * the same "never a silent widening/narrowing" rule #78/#79 already settled for the checkbox.
   */
  protected expanded(context: string): boolean {
    if (this.query().trim() !== '') {
      return true;
    }
    return !this.collapsed().has(context);
  }

  /** PROTOTYPE (issue #80), variant B only. */
  protected toggleGroup(context: string): void {
    const next = new Set(this.collapsed());
    if (next.has(context)) {
      next.delete(context);
    } else {
      next.add(context);
    }
    this.collapsed.set(next);
  }

  /** PROTOTYPE (issue #80), variant C only — jump to a context's section without a page reload. */
  protected jumpTo(context: string): void {
    document
      .getElementById(`context-${this.slug(context)}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /** PROTOTYPE (issue #80), variant C only — a stable id for the jump target / anchor. */
  protected slug(context: string): string {
    return context.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  }

  /**
   * A nudge, never a silent widening: how many deactivated templates the search matches while
   * "show deactivated" is off and hiding them (#78's Variant A). Zero once the checkbox is on —
   * matches are visible directly then, and the nudge would be pointing at what is already shown.
   */
  protected readonly hiddenMatches = computed(() => {
    const needle = this.query().trim();
    if (needle === '' || this.showInactive()) {
      return 0;
    }
    const allRows = templateRows(this.heldTemplates(), this.held(), this.today(), {
      includeInactive: true,
    });
    return allRows.filter((row) => !row.template.active && templateRowMatches(row, this.query()))
      .length;
  });

  /**
   * The template whose ✓ was pressed and whose definition is not yet settled, or null.
   *
   * A step of its own, because ADR-0011 makes the affordance **pick a task, not a template**:
   * conjuring every definition as completed would complete tasks the user never named. A
   * single-definition template — which is 44 of the 47 real ones — never sees it.
   */
  protected readonly choosing = signal<TemplateRow | null>(null);

  constructor() {
    effect(() => {
      this.sync.revision();
      void this.pending.run(() => this.reload());
    });

    // Asked for once on arrival rather than polled: this is the screen where a template list being
    // stale is most visible, and the fetch leaves what is held alone if it fails.
    void this.templates.refresh();

    // *Which one did you do?* is an `aria-modal` dialog, so Escape has to close it — and until #67
    // nothing did, because the two components that owned the key both bound `document:` and neither
    // was this. It says it is open instead, and the shell hands it the key while it is topmost.
    effect((onCleanup) => {
      if (this.choosing() !== null) {
        onCleanup(this.overlays.open(() => this.choosing.set(null)));
      }
    });
  }

  /** What the template is asking for right now — only a row that has fired has one. */
  protected due(task: Task): string {
    return dueLabel(task.dueDate, this.today());
  }

  /** How long it has been wanting it. Every row has one, including the ones with nothing open. */
  protected lastDone(row: TemplateRow): string {
    return lastDoneLabel(row.lastCompletedOn, this.today());
  }

  /**
   * The ✓, which **never fires silently** (ADR-0014, amended by the author after the first draft let
   * it). The whole reason the action exists is that you did the thing away from the app.
   */
  protected didIt(row: TemplateRow): void {
    // An open task *is* the choice: it names one definition already, so asking which would be asking
    // a question whose answer is on screen.
    if (row.openTask !== null) {
      void this.confirm({ row, definitionIndex: 0, what: row.openTask.name });
      return;
    }
    if (row.template.taskDefinitions.length > 1) {
      this.choosing.set(row);
      return;
    }
    void this.confirm({
      row,
      definitionIndex: 0,
      what: row.template.taskDefinitions[0].name,
    });
  }

  protected chose(row: TemplateRow, definitionIndex: number): void {
    this.choosing.set(null);
    void this.confirm({
      row,
      definitionIndex,
      what: row.template.taskDefinitions[definitionIndex].name,
    });
  }

  protected cancelConfirm(): void {
    this.choosing.set(null);
  }

  /**
   * Asks when, then writes ADR-0011's one button in whichever of its two shapes the data chose.
   *
   * The confirm is **the shell's one confirm** (#67), awaited rather than rendered here: ADR-0014
   * affords two capture paths precisely because they converge before anything is written, and until
   * this there were two instances of the one component that convergence was named after.
   *
   * Through `sync.recordAll`, which the omnibox's template rows use too: the two paths mint the same
   * patches and must record them the same way, and it is what names the patch undo takes back.
   */
  private async confirm(chosen: Chosen): Promise<void> {
    const on = await this.confirms.ask(chosen.what, this.today());
    if (on === null) {
      return;
    }

    const undoable = await this.sync.recordAll(
      didItPatches(chosen.row, chosen.definitionIndex, on, this.now()),
    );

    const toast: Toast = {
      kind: 'undoable',
      what: `Completed — ${chosen.what}`,
      undo: () => void this.undo(undoable, toast),
    };
    this.toasts.show(toast);
  }

  private async undo(patch: TaskPatch, toast: Toast): Promise<void> {
    this.toasts.dismiss(toast);
    await this.sync.record(undoPatch(patch, this.now()));
  }

  private async reload(): Promise<void> {
    try {
      const [tasks, templates] = await Promise.all([this.store.tasks(), this.store.templates()]);
      this.asOf.set(today(this.now()));
      this.held.set(tasks);
      this.heldTemplates.set(templates);
    } catch (error) {
      // Fire-and-forget from an effect: a rejection escaping here is an unhandled one and nothing
      // else, and `SyncStatus.storeUnavailable` already owns saying so.
      console.error('The templates list could not read the local store.', error);
    }
  }
}
