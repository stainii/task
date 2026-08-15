import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

import { NOW } from '../clock';
import { CAP } from '../domain/bands';
import { IsoDate, today } from '../domain/dates';
import { capturePatch, contextsOf, lastUsedContext, matchingTasks } from '../domain/omnibox';
import { completePatch, DUE_PRESETS, dueDatePatch, undoPatch } from '../domain/patches';
import { Task, TaskPatch } from '../domain/task';
import { TaskTemplate } from '../domain/template';
import { didItPatches } from '../domain/template-completion';
import { templateOffers, TemplateOffer } from '../domain/templates';
import { dueLabel, lastDoneLabel } from './wording';
import { LocalStore } from '../store/local-store';
import { SyncService } from '../sync/sync';
import { DateConfirm } from './date-confirm';
import { UndoToast } from './undo-toast';

/**
 * The one toast slot, and the two things it can be about.
 *
 * One slot rather than two, because the two are mutually exclusive in practice and a capture
 * stacked under a completion would put two undo-shaped offers on screen with different deadlines.
 */
export type Toast =
  /** A capture, still offering to give it the due date it deliberately did not get. */
  | {
      readonly kind: 'created';
      readonly taskId: string;
      readonly name: string;
      readonly context: string;
    }
  /**
   * A completion made by name, still offering to take it back.
   *
   * ADR-0015 puts a toast behind **any action that removes a row**, and this removes one from a
   * screen that does not show closed tasks. It is also the *only* correction path ADR-0011's
   * amendment and ADR-0018 left for `completedOn`: undo and recomplete inside the toast, or a wrong
   * date is permanent — the task is closed, this list is open-only, and the overview will never
   * show it again.
   */
  | { readonly kind: 'completed'; readonly patch: TaskPatch; readonly name: string };

/**
 * One row of the dropdown: something you can mark done, and which state it is in.
 *
 * **Two kinds in one list.** ADR-0014 collapsed the first draft's *Complete an open task* /
 * *I already did this* groups into a single list once every row was made to open the same confirm:
 * the split had become invisible and misleading, and it listed a due template **twice**, once in
 * each group. What tells them apart is now the sub-line — *7 days overdue* against *last done 10
 * days ago* — which is a fact about the row rather than a heading over it.
 */
export type Suggestion =
  | {
      readonly kind: 'task';
      readonly key: string;
      readonly name: string;
      /** *7 days overdue*, *no due date* — a fact, so it gets words (ADR-0019). */
      readonly state: string;
      readonly task: Task;
    }
  | {
      readonly kind: 'template';
      readonly key: string;
      readonly name: string;
      /** *never done*, *last 798 days ago · 7 Jun '24*. */
      readonly state: string;
      readonly offer: TemplateOffer;
    };

/**
 * The appbar's one input: **add, find, or say what you did**
 * ([ADR-0014](../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md)).
 *
 * Chosen over a FAB-and-sheet (3 taps) and a chores destination (2 taps) because it puts capture
 * **one keystroke from wherever you are** — and because it is what portal actually did:
 * `housagotchi-add-execution` was never a list you browsed and ticked, it was a `<mat-select>`, a
 * datepicker and *Done!*.
 *
 * **It is not a route.** Typing never changes the URL, so Escape returns you where you were.
 *
 * **There is no token syntax and no natural-language dates.** Both were rejected as a keyboard
 * layer in costume: a vocabulary to remember whose failure mode is silently eating a word out of a
 * task name, in two languages.
 */
@Component({
  selector: 'app-omnibox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DateConfirm, UndoToast],
  // Scoped to this component rather than `document:`, so it catches Escape from a chip or a
  // suggestion button — both are real, Tab-reachable buttons — without adding a third unconditional
  // document-level owner of the key beside `TaskPage` and `DateConfirm`. Bound on the input alone,
  // ADR-0014's *Escape returns you where you were* held only while the caret was in the box.
  host: { '(keydown.escape)': 'dismiss()' },
  templateUrl: './omnibox.html',
  styleUrl: './omnibox.css',
})
export class Omnibox {
  /**
   * The context a capture takes when this device knows of no other.
   *
   * Reachable only on a device holding **no tasks at all** and no remembered context — a fresh
   * install before the first sync. The alternative was refusing to capture until a context exists,
   * which would break *type and press Enter* on precisely the screen with nothing else on it.
   * *Decided by recommendation; no ADR names a default context.*
   */
  private static readonly FALLBACK_CONTEXT = 'general';

  /**
   * How long the create toast stays — **the undo toast's own horizon**, imported rather than
   * repeated, for the same reason `bands.CAP` is: a second literal is a second thing to keep in
   * step, and these two toasts share a slot.
   */
  private static readonly TOAST_MS = UndoToast.HORIZON_MS;

  private readonly router = inject(Router);
  private readonly store = inject(LocalStore);
  private readonly sync = inject(SyncService);
  private readonly now = inject(NOW);

  protected readonly query = signal('');

  /** The context last captured into, once the store has answered. */
  private readonly remembered = signal<string | null>(null);

  /**
   * Everything this device holds, re-read whenever sync says the store changed.
   *
   * From the store rather than an endpoint, which is what makes the whole dropdown work with the
   * radio off — the same coupling the overview has, and the whole of it.
   */
  private readonly held = signal<readonly Task[]>([]);

  /**
   * The templates this device holds, from the same store and for the same reason.
   *
   * Read rather than fetched: the whole dropdown has to work with the radio off, and a row that
   * waited on a response would be the one part of the capture path that did not.
   */
  private readonly heldTemplates = signal<readonly TaskTemplate[]>([]);

  /** The row whose confirm is open, or null. Nothing is written while this is set. */
  protected readonly confirming = signal<Suggestion | null>(null);

  /** What the toast is about, or null while there is nothing to say. */
  private readonly toast = signal<Toast | null>(null);

  /** The two views of that one slot, so the template branches on presence rather than on a kind. */
  protected readonly created = computed(() => {
    const toast = this.toast();
    return toast?.kind === 'created' ? toast : null;
  });

  protected readonly undoable = computed(() => {
    const toast = this.toast();
    return toast?.kind === 'completed' ? toast : null;
  });

  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /**
   * The context you are standing in, read off the URL.
   *
   * `/in/:value` is entering a context (ADR-0006, ADR-0014), so the URL already holds this answer
   * and a second copy of it in component state is a second thing that can be wrong.
   */
  private readonly standingIn = computed<string | null>(() => {
    const url = this.url();
    if (!url.startsWith('/in/')) {
      return null;
    }
    return decodeURIComponent(url.slice('/in/'.length).split(/[?#]/)[0]);
  });

  /**
   * A chip tapped for *this* capture, cleared once it is made.
   *
   * Deliberately not sticky: the next capture starts from where you are standing again. A chip that
   * outlived its capture would be an invisible mode — the box looks the same and the task lands
   * somewhere else.
   */
  private readonly picked = signal<string | null>(null);

  /**
   * Where a capture lands, in order: the chip you tapped, the context you are standing in, the one
   * you last captured into, the context of the newest task this device holds, and only then the
   * literal.
   *
   * **ADR-0018 states two steps** — standing in, else last used. The third and fourth are additions:
   * on a device that has never captured, *both* of the ADR's steps are silent while the answer is
   * sitting in the task list. Found by driving the real app, where a device holding four real
   * contexts marked `general`, a word nothing in the data had ever named. *Decided by
   * recommendation; ADR-0018 names only the first two steps.*
   */
  protected readonly context = computed(
    () =>
      this.picked() ??
      this.standingIn() ??
      this.remembered() ??
      lastUsedContext(this.held()) ??
      Omnibox.FALLBACK_CONTEXT,
  );

  /**
   * The chips: every context this device knows, plus the one a capture would land in.
   *
   * The second half matters when you have just cleared the last task in a context — the chip you
   * are standing on would otherwise vanish, leaving a row where none is marked and no way back to
   * the context you are in.
   */
  protected readonly chips = computed(() =>
    [...new Set([...contextsOf(this.held()), this.context()])].sort((a, b) => a.localeCompare(b)),
  );

  /**
   * The toast's due-date chips, worded as ADR-0018 words them: `due today · tomorrow · in 3 days`.
   *
   * The first carries the word that makes the row a sentence; the rest inherit it. Lower-cased
   * against the presets rather than written out again — the labels are the same vocabulary the
   * dialog and the panel use, and a second copy is a second thing to keep in step.
   */
  protected readonly dueChips = computed(() =>
    DUE_PRESETS.map((preset) => ({
      days: preset.days,
      label: preset.days === 0 ? 'due today' : preset.label.toLowerCase(),
    })),
  );

  constructor() {
    effect(() => {
      this.sync.revision();
      void this.reload();
    });

    inject(DestroyRef).onDestroy(() => this.clearToast());
  }

  /** One tap on the toast, giving the capture the due date it deliberately did not get. */
  protected async due(days: number): Promise<void> {
    const created = this.created();
    if (created === null) {
      return;
    }
    this.clearToast();
    await this.sync.record(dueDatePatch(created.taskId, days, this.now()));
  }

  /** *Add details*: an ordinary navigation to the task's own dialog (ADR-0018). */
  protected details(): void {
    const created = this.created();
    if (created === null) {
      return;
    }
    this.clearToast();
    void this.router.navigate(['/task', created.taskId]);
  }

  private clearToast(): void {
    if (this.toastTimer !== null) {
      clearTimeout(this.toastTimer);
      this.toastTimer = null;
    }
    this.toast.set(null);
  }

  /**
   * The date this screenful is measured against, **re-read whenever the tasks are**.
   *
   * A `signal`, not a `computed`: `NOW` is a plain function rather than a signal, so a computed
   * over it has no dependency to invalidate and memoises for the life of the tab — and this appbar
   * is never destroyed, in an installed PWA that stays open across midnight. The consequence is not
   * cosmetic here: `DateConfirm` seeds its field from this, so a frozen value writes **yesterday**
   * into `completedOn` — ADR-0011's domain clock, what a min/max anchor reads, and never editable
   * afterwards.
   *
   * `overview.ts` met this exact problem and answered it the same way, for the same stated reason.
   */
  private readonly asOf = signal<IsoDate>(today(this.now()));

  protected readonly today = this.asOf.asReadonly();

  /**
   * What typing offers: the open tasks that match, most urgent first.
   *
   * **One list, not two groups.** ADR-0014 collapsed *complete an open task* and *I already did
   * this* into a single list once both were made to open the same confirm — the split had become
   * invisible and misleading, and it listed a due template twice, once in each group. The template
   * rows join this list in [#61](https://github.com/stainii/task/issues/61), which is where the
   * client first holds templates at all.
   */
  protected readonly suggestions = computed<readonly Suggestion[]>(() => {
    const query = this.query();
    const today = this.today();

    // **Templates lead.** A task that matched is already on the overview, one tab away; a template
    // that is not yet due has no other route in the app except the templates list, and this box is
    // the one that claims to be *one keystroke from wherever you are*. It is also the half ADR-0014
    // ranks first for the ✓ on the list itself, so the two surfaces agree.
    //
    // *Decided by recommendation:* ADR-0014 collapses the two groups into one list and does not say
    // how the merged list orders itself.
    const templates = templateOffers(this.heldTemplates(), this.held(), query, today).map(
      (offer) =>
        ({
          kind: 'template',
          key: `${offer.row.template.id}#${offer.definitionIndex}`,
          name: offer.name,
          state: lastDoneLabel(offer.row.lastCompletedOn, today),
          offer,
        }) as const,
    );

    const tasks = matchingTasks(this.held(), query, today).map(
      (task) =>
        ({
          kind: 'task',
          key: task.id,
          name: task.name,
          state: dueLabel(task.dueDate, today),
          task,
        }) as const,
    );

    return [...templates, ...tasks].slice(0, CAP);
  });

  /** Creating is offered whenever anything has been typed, whether or not something matched. */
  protected readonly creatable = computed(() => this.query().trim() !== '');

  protected typed(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  /** One tap on a chip, changing where this capture lands. */
  protected pick(context: string): void {
    this.picked.set(context);
  }

  /**
   * Picking a row asks **when** rather than completing on the spot.
   *
   * *Chosen by name asks; acted on in place does not* (ADR-0014). Typing a name is recording
   * something that already happened, so this writes nothing until the confirm is answered.
   */
  protected choose(suggestion: Suggestion): void {
    this.confirming.set(suggestion);
  }

  /**
   * Writes ADR-0011's one button in whichever of its two shapes the row is.
   *
   * A task row completes that task; a template row mints one created and completed in the same
   * breath. **The two are indistinguishable from here on** — one confirm collected the date, one
   * toast offers to take it back — which is exactly what ADR-0014 means by the paths converging
   * before anything is written.
   */
  protected async completed(on: IsoDate): Promise<void> {
    const suggestion = this.confirming();
    if (suggestion === null) {
      return;
    }
    this.confirming.set(null);
    this.query.set('');

    const patches =
      suggestion.kind === 'task'
        ? [completePatch(suggestion.task, this.now(), on)]
        : didItPatches(suggestion.offer.row, suggestion.offer.definitionIndex, on, this.now());

    // In order, and awaited in order: the minting pair is a creation followed by a completion, and
    // the outbox drains in the order it was filled.
    for (const patch of patches) {
      await this.sync.record(patch);
    }

    // The **completing** patch is the one undo names. Voiding the creation of a task minted here
    // would complete it instead — the fold cannot un-create — which is the opposite of taking it
    // back.
    this.offerToast({
      kind: 'completed',
      patch: patches[patches.length - 1],
      name: suggestion.name,
    });
  }

  /** Takes the completion back, as ADR-0004's void patch. The fold recomputes; nothing is edited. */
  protected async undo(): Promise<void> {
    const toast = this.undoable();
    if (toast === null) {
      return;
    }
    this.clearToast();
    await this.sync.record(undoPatch(toast.patch, this.now()));
  }

  protected cancelConfirm(): void {
    this.confirming.set(null);
  }

  /**
   * Enter mints the task (ADR-0015), rather than opening a form.
   *
   * Routing a capture into a form gives back the keystroke that is the omnibox's whole argument —
   * and it writes its first patch only on Save, so anything typed and abandoned is lost, where this
   * path has a real task under offline sync from the first keystroke.
   */
  protected async capture(): Promise<void> {
    const name = this.query().trim();
    if (name === '') {
      return;
    }

    const context = this.context();
    const patch = capturePatch(name, context, this.now());
    this.query.set('');
    this.picked.set(null);
    await this.sync.record(patch);
    await this.store.setLastContext(context);
    this.remembered.set(context);
    this.offerToast({ kind: 'created', taskId: patch.taskId, name, context });
  }

  private offerToast(toast: Toast): void {
    this.clearToast();
    this.toast.set(toast);
    this.toastTimer = setTimeout(() => this.toast.set(null), Omnibox.TOAST_MS);
  }

  /** Escape puts the box down. It never navigates, because typing never navigated. */
  protected dismiss(): void {
    this.query.set('');
  }

  private async reload(): Promise<void> {
    try {
      const [tasks, templates] = await Promise.all([this.store.tasks(), this.store.templates()]);
      this.asOf.set(today(this.now()));
      this.held.set(tasks);
      this.heldTemplates.set(templates);
      this.remembered.set(await this.store.lastContext());
    } catch (error) {
      // Fire-and-forget from an effect: a rejection escaping here is an unhandled one and nothing
      // else, and `SyncStatus.storeUnavailable` already owns saying so (#63).
      console.error('The omnibox could not read the local store.', error);
    }
  }
}
