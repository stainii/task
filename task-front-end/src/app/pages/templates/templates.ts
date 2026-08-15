import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  PendingTasks,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { NOW } from '../../clock';
import { IsoDate, today } from '../../domain/dates';
import { undoPatch } from '../../domain/patches';
import { Task, TaskPatch } from '../../domain/task';
import { TaskTemplate } from '../../domain/template';
import { didItPatches } from '../../domain/template-completion';
import { templateRows, TemplateRow } from '../../domain/templates';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { TemplateService } from '../../sync/templates';
import { DateConfirm } from '../../ui/date-confirm';
import { GlyphButton } from '../../ui/glyph-button';
import { UndoToast } from '../../ui/undo-toast';
import { dueLabel, lastDoneLabel } from '../../ui/wording';

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
  imports: [RouterLink, DateConfirm, GlyphButton, UndoToast],
  templateUrl: './templates.html',
  styleUrl: './templates.css',
})
export class Templates {
  private readonly store = inject(LocalStore);
  private readonly sync = inject(SyncService);
  private readonly templates = inject(TemplateService);
  private readonly now = inject(NOW);

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

  protected readonly rows = computed(() =>
    templateRows(this.heldTemplates(), this.held(), this.today(), {
      includeInactive: this.showInactive(),
    }),
  );

  /**
   * The template whose ✓ was pressed and whose definition is not yet settled, or null.
   *
   * A step of its own, because ADR-0011 makes the affordance **pick a task, not a template**:
   * conjuring every definition as completed would complete tasks the user never named. A
   * single-definition template — which is 44 of the 47 real ones — never sees it.
   */
  protected readonly choosing = signal<TemplateRow | null>(null);

  /** What the confirm is about. Nothing is written while this is set. */
  protected readonly confirming = signal<Chosen | null>(null);

  /** The completion still inside its undo window, or null. */
  protected readonly undoable = signal<{ patch: TaskPatch; what: string } | null>(null);

  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      this.sync.revision();
      void this.pending.run(() => this.reload());
    });

    // Asked for once on arrival rather than polled: this is the screen where a template list being
    // stale is most visible, and the fetch leaves what is held alone if it fails.
    void this.templates.refresh();

    inject(DestroyRef).onDestroy(() => this.clearToast());
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
      this.confirming.set({ row, definitionIndex: 0, what: row.openTask.name });
      return;
    }
    if (row.template.taskDefinitions.length > 1) {
      this.choosing.set(row);
      return;
    }
    this.confirming.set({ row, definitionIndex: 0, what: row.template.taskDefinitions[0].name });
  }

  protected chose(row: TemplateRow, definitionIndex: number): void {
    this.choosing.set(null);
    this.confirming.set({
      row,
      definitionIndex,
      what: row.template.taskDefinitions[definitionIndex].name,
    });
  }

  protected cancelConfirm(): void {
    this.choosing.set(null);
    this.confirming.set(null);
  }

  /**
   * Writes ADR-0011's one button in whichever of its two shapes the data chose.
   *
   * **In order, and awaited in order**: the pair that mints a task is a creation followed by a
   * completion, and the outbox drains in the order it was filled.
   */
  protected async completed(on: IsoDate): Promise<void> {
    const chosen = this.confirming();
    if (chosen === null) {
      return;
    }
    this.confirming.set(null);

    const patches = didItPatches(chosen.row, chosen.definitionIndex, on, this.now());
    for (const patch of patches) {
      await this.sync.record(patch);
    }

    // The *completing* patch is the one undo names — voiding the creation of a task minted here
    // would complete it instead (the fold cannot un-create), which is the opposite of taking it
    // back. Voiding the completion leaves an open task, which is a row you can then cancel.
    this.offerUndo(patches[patches.length - 1], chosen.what);
  }

  protected async undo(): Promise<void> {
    const toast = this.undoable();
    if (toast === null) {
      return;
    }
    this.clearToast();
    await this.sync.record(undoPatch(toast.patch, this.now()));
  }

  private offerUndo(patch: TaskPatch, what: string): void {
    this.clearToast();
    this.undoable.set({ patch, what });
    this.toastTimer = setTimeout(() => this.undoable.set(null), UndoToast.HORIZON_MS);
  }

  private clearToast(): void {
    if (this.toastTimer !== null) {
      clearTimeout(this.toastTimer);
      this.toastTimer = null;
    }
    this.undoable.set(null);
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
