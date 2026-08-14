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
import { IsoDate, today } from '../domain/dates';
import { capturePatch, contextsOf, lastUsedContext, matchingTasks } from '../domain/omnibox';
import { completePatch, DUE_PRESETS, dueDatePatch } from '../domain/patches';
import { Task } from '../domain/task';
import { LocalStore } from '../store/local-store';
import { SyncService } from '../sync/sync';
import { DateConfirm } from './date-confirm';
import { dueLabel } from './wording';

/** A capture, while its toast is still offering to finish the job. */
export interface Captured {
  readonly taskId: string;
  readonly name: string;
  readonly context: string;
}

/** One row of the dropdown: something you can mark done, and which state it is in. */
export interface Suggestion {
  readonly task: Task;
  /** *7 days overdue*, *no due date* — a fact, so it gets words (ADR-0019). */
  readonly state: string;
}

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
  imports: [DateConfirm],
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

  /** How long the create toast stays — the undo toast's horizon, for the same reason. */
  private static readonly TOAST_MS = 8_000;

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

  /** The task whose confirm is open, or null. Nothing is written while this is set. */
  protected readonly confirming = signal<Task | null>(null);

  /** The capture just made, while its toast is still up. */
  protected readonly created = signal<Captured | null>(null);

  private createdTimer: ReturnType<typeof setTimeout> | null = null;

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
   * The fourth step was added after driving the real app: a device holding four real contexts and
   * no capture history marked `general` — a word nothing in the data had ever named.
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
    const captured = this.created();
    if (captured === null) {
      return;
    }
    this.clearToast();
    await this.sync.record(dueDatePatch(captured.taskId, days, this.now()));
  }

  /** *Add details*: an ordinary navigation to the task's own dialog (ADR-0018). */
  protected details(): void {
    const captured = this.created();
    if (captured === null) {
      return;
    }
    this.clearToast();
    void this.router.navigate(['/task', captured.taskId]);
  }

  private clearToast(): void {
    if (this.createdTimer !== null) {
      clearTimeout(this.createdTimer);
      this.createdTimer = null;
    }
    this.created.set(null);
  }

  protected readonly today = computed<IsoDate>(() => today(this.now()));

  /**
   * What typing offers: the open tasks that match, most urgent first.
   *
   * **One list, not two groups.** ADR-0014 collapsed *complete an open task* and *I already did
   * this* into a single list once both were made to open the same confirm — the split had become
   * invisible and misleading, and it listed a due template twice, once in each group. The template
   * rows join this list in [#61](https://github.com/stainii/task/issues/61), which is where the
   * client first holds templates at all.
   */
  protected readonly suggestions = computed<readonly Suggestion[]>(() =>
    matchingTasks(this.held(), this.query(), this.today()).map((task) => ({
      task,
      state: dueLabel(task.dueDate, this.today()),
    })),
  );

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
  protected choose(task: Task): void {
    this.confirming.set(task);
  }

  protected async completed(on: IsoDate): Promise<void> {
    const task = this.confirming();
    if (task === null) {
      return;
    }
    this.confirming.set(null);
    this.query.set('');
    await this.sync.record(completePatch(task, this.now(), on));
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
    this.offerToast({ taskId: patch.taskId, name, context });
  }

  private offerToast(captured: Captured): void {
    this.clearToast();
    this.created.set(captured);
    this.createdTimer = setTimeout(() => this.created.set(null), Omnibox.TOAST_MS);
  }

  /** Escape puts the box down. It never navigates, because typing never navigated. */
  protected dismiss(): void {
    this.query.set('');
  }

  private async reload(): Promise<void> {
    try {
      this.held.set(await this.store.tasks());
      this.remembered.set(await this.store.lastContext());
    } catch (error) {
      // Fire-and-forget from an effect: a rejection escaping here is an unhandled one and nothing
      // else, and `SyncStatus.storeUnavailable` already owns saying so (#63).
      console.error('The omnibox could not read the local store.', error);
    }
  }
}
