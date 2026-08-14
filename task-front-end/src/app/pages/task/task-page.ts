import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { NOW } from '../../clock';
import { addDays, IsoDate, today } from '../../domain/dates';
import { ASK_FROM_PRESETS, PostponePreset } from '../../domain/patches';
import { Importance, IMPORTANCES, Task } from '../../domain/task';
import {
  asksAfterItIsDue,
  changesOf,
  draftOf,
  savePatch,
  TaskDraft,
} from '../../domain/task-draft';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { Notices } from '../../ui/notices';
import { dateLabel, dueLabel, importanceLabel } from '../../ui/wording';

/** What one read of the store yields: the task, and the contexts already in use. */
interface Loaded {
  readonly task: Task | null;
  readonly contexts: readonly string[];
}

/** The sentence the form states instead of validating, pre-worded. */
interface AskFromConflict {
  readonly from: string;
  readonly due: string;
  readonly overdue: string;
}

/**
 * The task dialog, on a route (ADR-0018): a flat dialog at `/task/:id`.
 *
 * **A dialog, and routed anyway.** The author chose the dialog *look* over a full-page route and
 * over editing in the expanded panel — the last of those rejected on a cost the prototype found
 * rather than the predicted one: the form is perfectly legible in a ~410px grid cell, but the CSS
 * grid row stretches, so every card beside it grows into a tall empty box. The addressability is a
 * separate question and ADR-0014's answer to it still holds: an installed PWA's hardware back must
 * leave the *thing* and not the app, and ADR-0012's 07:30 push has to land somewhere.
 *
 * **Six fields, flat.** A deliberate inversion of ADR-0013, whose drawer hides what is rarely
 * *set*: the same rule here would hide description and *ask me from*, the 3rd and 4th most-edited
 * fields across six years. The stepper's sin was making six fields into six screens, not having
 * them.
 *
 * **It reads the local store, never the network.** The client is authoritative for display at all
 * times, so a task this device has not got is simply not here — there is no server to ask, and a
 * spinner would wait for ever on the offline cold boot this app is built around.
 *
 * **There is no create mode** (ADR-0018). Enter in the omnibox mints the task; this screen only
 * ever edits one that exists, because a form that writes its first patch on Save loses anything
 * typed and abandoned, where the capture path has a real task under offline sync from the first
 * keystroke.
 */
@Component({
  selector: 'app-task-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './task-page.html',
  styleUrl: './task-page.css',
  host: { '(document:keydown.escape)': 'dismiss()' },
})
export class TaskPage {
  private readonly store = inject(LocalStore);
  private readonly sync = inject(SyncService);
  private readonly router = inject(Router);
  private readonly notices = inject(Notices);
  private readonly now = inject(NOW);

  readonly id = input.required<string>();

  protected readonly importances = IMPORTANCES;
  protected readonly presets = ASK_FROM_PRESETS;

  protected readonly confirming = signal(false);

  /**
   * One read of the local store, keyed on the id in the URL.
   *
   * A `resource` rather than an `effect` that assigns: an effect writing signals is state
   * propagation, which Angular's own guidance rules out, and it cost this screen a real defect —
   * `lastSuccessfulNavigation` is itself a signal, so reading it inside the effect made *closing*
   * the dialog retrigger the load that reopens it. That is an infinite loop, and what it produced
   * was `Worker exited unexpectedly`, naming no file at all.
   *
   * It reads the store and never the network: the client is authoritative for display at all times,
   * so *not on this device* is the only answer there is, and a spinner would wait for ever offline.
   */
  private readonly loaded = resource<Loaded, string>({
    params: () => this.id(),
    loader: async ({ params: id }): Promise<Loaded> => {
      const [task, all] = await Promise.all([this.store.task(id), this.store.tasks()]);
      // The contexts already in use, so a free label has its known values one keystroke away
      // (`CONTEXT.md`: a context is a label, not an enum).
      return { task, contexts: [...new Set(all.map((held) => held.context))].sort() };
    },
  });

  /** The task, and only while it is still editable — a closed one redirects rather than renders. */
  protected readonly task = computed<Task | null>(() => {
    const task = this.loaded.value()?.task ?? null;
    return task !== null && task.status === 'OPEN' ? task : null;
  });

  protected readonly contexts = computed<readonly string[]>(
    () => this.loaded.value()?.contexts ?? [],
  );

  /**
   * The form's own copy, which **resets when the task underneath it changes** rather than being
   * assigned by hand. That is what `linkedSignal` is for, and it is why nothing here has to
   * remember to clear a half-typed draft when the URL moves to another task.
   */
  protected readonly draft = linkedSignal<Task | null, TaskDraft>({
    source: () => this.task(),
    computation: (task) => (task === null ? BLANK : draftOf(task)),
  });

  /**
   * Where closing goes back to, captured **once, on arrival**, so entering a context survives an
   * edit: closing a dialog opened from `/in/housagotchi` must not drop you back at every context at
   * once. `untracked` because `lastSuccessfulNavigation` is a signal, and this may not become a
   * dependency of anything.
   */
  private readonly returnTo = untracked(
    () => this.router.lastSuccessfulNavigation()?.previousNavigation?.finalUrl?.toString() ?? '/',
  );

  protected readonly changeCount = computed(() => {
    const task = this.task();
    return task === null ? 0 : Object.keys(changesOf(task, this.draft())).length;
  });

  /**
   * *Asking from 17 Aug, still due 30 Jun — 48 days overdue.*
   *
   * The overdue tail goes through the same ladder the overview uses, rather than a subtraction
   * written a second time here: the two saying different things about one date is exactly what
   * `dueIn` exists to prevent.
   */
  protected readonly conflict = computed<AskFromConflict | null>(() => {
    const draft = this.draft();
    if (!asksAfterItIsDue(draft) || draft.dueDate === null) {
      return null;
    }
    const asOf = today(this.now());
    return {
      from: dateLabel(draft.startDate, asOf),
      due: dateLabel(draft.dueDate, asOf),
      overdue: dueLabel(draft.dueDate, asOf),
    };
  });

  constructor() {
    // Navigation *is* an imperative side effect, which is the one thing an effect is for. This one
    // reads the resource and writes no signal of its own.
    effect(() => {
      const failure = this.loaded.error();
      if (failure !== undefined) {
        console.error('The task dialog could not read the local store.', failure);
        return;
      }
      if (!this.loaded.hasValue()) {
        return;
      }
      const task = this.loaded.value().task;
      if (task === null) {
        this.leave('That task is not on this device.');
      } else if (task.status !== 'OPEN') {
        const said = task.status === 'COMPLETED' ? 'completed' : 'cancelled';
        this.leave(`${task.name} is already ${said}.`);
      }
    });
  }

  protected label(importance: Importance): string {
    return importanceLabel(importance);
  }

  /** The value of whichever control raised the event. Typed once here rather than in six bindings. */
  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement).value;
  }

  protected edit(field: 'name' | 'context' | 'importance', value: string): void {
    this.draft.update((draft) => ({ ...draft, [field]: value }));
  }

  /** An emptied text field is `null` on the wire, not `''` — the fold's *clear it* value. */
  protected editText(field: 'description', value: string): void {
    this.draft.update((draft) => ({ ...draft, [field]: value === '' ? null : value }));
  }

  protected editDate(field: 'dueDate', value: string): void {
    this.draft.update((draft) => ({ ...draft, [field]: value === '' ? null : value }));
  }

  /**
   * *Ask me from* is the one date that cannot be cleared: a task with no start date is a task the
   * bands cannot place, where a task with no due date is 8% of the archive and perfectly ordinary.
   */
  protected editStart(value: string): void {
    if (value !== '') {
      this.draft.update((draft) => ({ ...draft, startDate: value }));
    }
  }

  protected askFrom(preset: PostponePreset): void {
    this.draft.update((draft) => ({ ...draft, startDate: this.presetDate(preset) }));
  }

  protected isOn(preset: PostponePreset): boolean {
    return this.draft().startDate === this.presetDate(preset);
  }

  /**
   * One Save writes **one patch carrying every changed field**, and an untouched form writes
   * nothing at all.
   */
  protected async save(): Promise<void> {
    const task = this.task();
    if (task === null) {
      return;
    }
    const patch = savePatch(task, this.draft(), this.now());
    if (patch !== null) {
      // The local write is the acknowledgement; the network is weather.
      await this.sync.record(patch);
    }
    this.close();
  }

  /**
   * A **deliberate** Cancel discards and says what went. It does not ask: it is already an answer.
   */
  protected cancel(): void {
    const count = this.changeCount();
    if (count > 0) {
      this.notices.say(`Discarded ${count} unsaved change(s)`);
    }
    this.close();
  }

  /**
   * An **accidental** dismissal — scrim, Escape, hardware back — asks first.
   *
   * Splitting by gesture is what lets both of the author's answers hold without putting a confirm
   * in front of every close (ADR-0018). With nothing typed there is nothing to lose, so it goes.
   */
  protected dismiss(): void {
    if (this.changeCount() === 0) {
      this.close();
      return;
    }
    this.confirming.set(true);
  }

  protected keepEditing(): void {
    this.confirming.set(false);
  }

  private presetDate(preset: PostponePreset): IsoDate {
    return addDays(today(this.now()), preset.days);
  }

  /**
   * Sends you back with a sentence.
   *
   * **A closed task's URL redirects** rather than rendering. Routing makes `/task/:id` deep-linkable
   * including to a task that has since been completed, which ADR-0012's 07:30 push makes ordinary
   * rather than exotic — and opening it editable would let a change be saved into a task ADR-0006's
   * overview will never show again. A read-only rendering is a second copy of the whole form for a
   * case with nothing to do in it. The author accepted this as *"a consequence of having everything
   * routable"*.
   */
  private leave(why: string): void {
    this.notices.say(why);
    this.close();
  }

  private close(): void {
    void this.router.navigateByUrl(this.returnTo);
  }
}

/** Never rendered: the template holds nothing until a task has loaded. */
const BLANK: TaskDraft = {
  name: '',
  context: '',
  importance: 'IMPORTANT',
  dueDate: null,
  startDate: '',
  description: null,
};
