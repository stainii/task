import {
  ChangeDetectionStrategy,
  Component,
  computed,
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
import { completePatch, dueDatePatch, undoPatch } from '../domain/patches';
import { Task, TaskPatch } from '../domain/task';
import { TaskTemplate } from '../domain/template';
import { didItPatches } from '../domain/template-completion';
import { templateOffers, TemplateOffer } from '../domain/templates';
import { dueLabel, lastDoneLabel } from './wording';
import { LocalStore } from '../store/local-store';
import { SyncService } from '../sync/sync';
import { Confirms } from './confirms';
import { Overlays } from './overlays';
import { Toast, Toasts } from './toasts';

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

  private readonly router = inject(Router);
  private readonly store = inject(LocalStore);
  private readonly sync = inject(SyncService);
  private readonly now = inject(NOW);
  private readonly overlays = inject(Overlays);
  private readonly confirms = inject(Confirms);
  private readonly toasts = inject(Toasts);

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

  constructor() {
    effect(() => {
      this.sync.revision();
      void this.reload();
    });

    // **The dropdown says it is open, and the shell owns the key** (#67). #60 bound Escape on this
    // component's host rather than on `document:` so it would catch a chip or a suggestion without
    // adding a third unconditional owner of the key; this keeps that promise and drops the listener
    // with it — one press dismisses the topmost overlay, which is this only while nothing is over
    // it. The cleanup is what stands the box down again, so nothing has to remember to.
    effect((onCleanup) => {
      if (this.creatable()) {
        onCleanup(this.overlays.open(() => this.dismiss()));
      }
    });
  }

  /** One tap on the toast, giving the capture the due date it deliberately did not get. */
  private async due(taskId: string, days: number, toast: Toast): Promise<void> {
    this.toasts.dismiss(toast);
    await this.sync.record(dueDatePatch(taskId, days, this.now()));
  }

  /** *Add details*: an ordinary navigation to the task's own dialog (ADR-0018). */
  private details(taskId: string, toast: Toast): void {
    this.toasts.dismiss(toast);
    void this.router.navigate(['/task', taskId]);
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
    const asOf = this.today();

    // **Tasks first, then templates** — ADR-0014 states the order outright: *"Typing offers, in
    // order — complete a matching open task, I already did this for a matching template, and create
    // a task with what you typed."* An earlier draft of this method put templates first and claimed
    // the ADR was silent on the merged list; it is not, and the reason it is not is the same reason
    // the two groups were collapsed: an open task is the thing the app already decided you should be
    // doing, and offering a chore above it re-creates the split by ranking.
    const tasks = matchingTasks(this.held(), query, asOf).map(
      (task) =>
        ({
          kind: 'task',
          key: task.id,
          name: task.name,
          state: dueLabel(task.dueDate, asOf),
          task,
        }) as const,
    );

    // **Whatever room the tasks left**, which is why this asks for a limit rather than taking the
    // cap itself. Both halves capping at five independently made the merge slice a ten-row list back
    // to five, so five matching chores pushed *every* open task off a list ADR-0014 puts them at the
    // top of.
    const room = Math.max(0, CAP - tasks.length);
    const templates = templateOffers(this.heldTemplates(), this.held(), query, asOf, room).map(
      (offer) =>
        ({
          kind: 'template',
          key: `${offer.row.template.id}#${offer.definitionIndex}`,
          name: offer.name,
          state: lastDoneLabel(offer.row.lastCompletedOn, asOf),
          offer,
        }) as const,
    );

    return [...tasks, ...templates];
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
   * Picking a row asks **when** rather than completing on the spot, and then writes ADR-0011's one
   * button in whichever of its two shapes the row is.
   *
   * *Chosen by name asks; acted on in place does not* (ADR-0014). Typing a name is recording
   * something that already happened, so nothing is written until the confirm is answered — and the
   * confirm is the shell's one confirm, awaited rather than rendered here (#67).
   *
   * A task row completes that task; a template row mints one created and completed in the same
   * breath. **The two are indistinguishable from here on** — one confirm collected the date, one
   * toast offers to take it back — which is exactly what ADR-0014 means by the paths converging
   * before anything is written. `recordAll` is the third thing they share, and it is what names the
   * patch undo takes back.
   */
  protected async choose(suggestion: Suggestion): Promise<void> {
    const on = await this.confirms.ask(suggestion.name, this.today());
    if (on === null) {
      return;
    }
    this.query.set('');

    const undoable = await this.sync.recordAll(
      suggestion.kind === 'task'
        ? [completePatch(suggestion.task, this.now(), on)]
        : didItPatches(suggestion.offer.row, suggestion.offer.definitionIndex, on, this.now()),
    );

    const toast: Toast = {
      kind: 'undoable',
      what: `Completed — ${suggestion.name}`,
      undo: () => void this.undo(undoable, toast),
    };
    this.toasts.show(toast);
  }

  /** Takes the completion back, as ADR-0004's void patch. The fold recomputes; nothing is edited. */
  private async undo(patch: TaskPatch, toast: Toast): Promise<void> {
    this.toasts.dismiss(toast);
    await this.sync.record(undoPatch(patch, this.now()));
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
    const toast: Toast = {
      kind: 'created',
      name,
      context,
      due: (days) => void this.due(patch.taskId, days, toast),
      details: () => this.details(patch.taskId, toast),
    };
    this.toasts.show(toast);
  }

  /** Escape puts the box down. It never navigates, because typing never navigated. */
  private dismiss(): void {
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
