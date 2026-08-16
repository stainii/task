import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

import { NOW } from '../../clock';
import { visibleWork } from '../../domain/bands';
import { today } from '../../domain/dates';
import { PanelAction, undoPatch } from '../../domain/patches';
import { Task, TaskPatch } from '../../domain/task';
import { LocalStore } from '../../store/local-store';
import { SyncService } from '../../sync/sync';
import { Toast, Toasts } from '../../ui/toasts';
import { TaskPanel } from './task-panel';

/**
 * A band that starts folded, and what is remembered about it for the session.
 *
 * `key` is separate from `title` on purpose: the key is what the session remembers, and deriving it
 * from the displayed words would make renaming a heading silently forget which bands were open —
 * a defect no test would catch, because both halves would still be internally consistent.
 *
 * Named `CollapsedBand` rather than `Fold` because `domain/fold.ts` is the most load-bearing word
 * in this app and it means something else entirely.
 */
interface CollapsedBand {
  readonly key: 'also' | 'future';
  readonly title: string;
  readonly tasks: readonly Task[];
  readonly open: boolean;
}

/**
 * ADR-0006's overview, at `/` and — once you enter a context — at `/in/:value`.
 *
 * Entering is a route, not in-page state (ADR-0014): a push notification, a shared link and a
 * restored session are then the same mechanism. The parameter is `value`, not `context`, because
 * ADR-0006 makes the grouping axis a property of the card row alone and a route naming the axis
 * would hard-code it into every stored URL.
 *
 * **It renders from the local store, never from a response.** The store is authoritative for
 * display at all times, so this screen is complete on a cold boot with the radio off; sync bumps a
 * revision and this re-reads, which is the whole of the coupling between them.
 *
 * The context cards above the bands, the words on a folded band and the rejected-changes band are
 * [#58](https://github.com/stainii/task/issues/58).
 */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskPanel],
  templateUrl: './overview.html',
  styleUrl: './overview.css',
})
export class Overview {
  private readonly store = inject(LocalStore);
  private readonly sync = inject(SyncService);
  private readonly now = inject(NOW);
  private readonly toasts = inject(Toasts);

  /** Bound from the route parameter; absent at `/`, which is every context at once. */
  readonly value = input<string>();

  private readonly held = signal<readonly Task[]>([]);

  /**
   * The date every band and every label on this screen is measured against.
   *
   * One date for the whole screenful, re-read whenever the tasks are: a clock read per task can put
   * two of them on opposite sides of midnight, and a tab left open overnight would otherwise band
   * yesterday's work for ever.
   */
  private readonly asOf = signal(today(this.now()));

  private readonly opened = signal<ReadonlySet<string>>(new Set());

  protected readonly today = this.asOf.asReadonly();

  private readonly scoped = computed(() => {
    const context = this.value();
    const held = this.held();
    return context === undefined ? held : held.filter((task) => task.context === context);
  });

  private readonly work = computed(() => visibleWork(this.scoped(), this.asOf()));

  protected readonly visible = computed(() => this.work().visible);

  /**
   * The band's title, which **says why** when more than five things are on screen.
   *
   * Without the retitle the list silently grows past the cap and the rule looks broken rather than
   * deliberate.
   *
   * **And it does not claim things are due when none are.** ADR-0006 specifies the retitle only for
   * the exceeded case, so this is a gap rather than a contradiction — but the band is topped up
   * with work that is *not* due, and a heading is a fact in words (ADR-0019): `Due today` over five
   * tasks due next month states something false. *Decided by recommendation; no ADR covers it.*
   */
  protected readonly dueTitle = computed(() => {
    const { capExceeded, dueCount } = this.work();
    if (capExceeded) {
      return `Due today — all ${dueCount}`;
    }
    return dueCount > 0 ? 'Due today' : 'Next up';
  });

  protected readonly capExceeded = computed(() => this.work().capExceeded);

  /**
   * The two folded bands.
   *
   * `Also…` and `Starting in the future…` start closed and are **not rendered** while closed —
   * folded is not the same as portal's greyed-out `disabled` rows, where the commonest act after
   * clearing the top five was a click that revealed what was already on screen. Opening is
   * remembered for the session, so clearing the day's work does not re-gate the same list.
   */
  protected readonly folds = computed<readonly CollapsedBand[]>(() => {
    const opened = this.opened();
    const bands: readonly CollapsedBand[] = [
      { key: 'also', title: 'Also…', tasks: this.work().also, open: opened.has('also') },
      {
        key: 'future',
        title: 'Starting in the future…',
        tasks: this.work().notStarted,
        open: opened.has('future'),
      },
    ];
    return bands.filter((band) => band.tasks.length > 0);
  });

  protected readonly empty = computed(
    () => this.visible().length === 0 && this.folds().length === 0,
  );

  constructor() {
    // Re-read whenever sync says the store changed — a stream patch, a snapshot, this screen's own
    // write. A revision rather than the tasks themselves: the store is the truth, and a second
    // in-memory copy of the task list is a second thing that can be wrong.
    effect(() => {
      this.sync.revision();
      void this.reload();
    });

    if (typeof document !== 'undefined') {
      // Portal's one genuinely good habit here: a phone comes back to this screen hours later, and
      // without this the bands are still measured against the day it was put down.
      const onVisible = () => {
        if (document.visibilityState === 'visible') {
          void this.reload();
        }
      };
      document.addEventListener('visibilitychange', onVisible);
      inject(DestroyRef).onDestroy(() => {
        document.removeEventListener('visibilitychange', onVisible);
      });
    }
  }

  protected unfold(key: CollapsedBand['key']): void {
    this.opened.update((opened) => new Set(opened).add(key));
  }

  /**
   * Complete, cancel and postpone all make a row leave a screen that does not show closed tasks, so
   * ADR-0015 puts an undo behind each of them.
   *
   * **The offer stands in the shell's one corner** (#67). This screen keeps the verb — undoing is
   * the overview's business, and it is the only place ADR-0004's void patch is reached in normal
   * use — and gives up the coordinate, because it used to pin itself into a corner the omnibox and
   * the templates list were pinning themselves into at the same time.
   */
  protected async acted(action: PanelAction): Promise<void> {
    await this.record(action.patch);
    // **It is not withdrawn when this screen goes.** Undo is a void patch through sync, which
    // works from wherever you are standing, and the corner is the app's — so tidying up on the way
    // out would have meant clearing whatever the corner happened to hold, including an offer the
    // omnibox had just raised from the appbar that outlives every screen.
    const toast: Toast = {
      kind: 'undoable',
      what: action.done,
      undo: () => void this.undo(action, toast),
    };
    this.toasts.show(toast);
  }

  private async undo(action: PanelAction, toast: Toast): Promise<void> {
    this.toasts.dismiss(toast);
    await this.record(undoPatch(action.patch, this.now()));
  }

  private async record(patch: TaskPatch): Promise<void> {
    // The local write is the acknowledgement; the network is weather. This resolves once the patch
    // is durably in the outbox, which is why the row may leave the screen straight afterwards.
    await this.sync.record(patch);
    await this.reload();
  }

  /**
   * Re-reads the store.
   *
   * **It catches, and that is not defensiveness.** Two of its three callers are fire-and-forget —
   * an `effect` and a DOM listener — so a rejection escaping here is an unhandled rejection and
   * nothing else: no screen shows it, no test fails on it, and vitest turns the whole suite red for
   * a reason that names the wrong file. That is exactly the shape
   * [#56](https://github.com/stainii/task/issues/56) shipped and had to fix in CI.
   *
   * There is nothing this screen can do about a store it cannot open — the app *is* its store —
   * and the fact already has an owner: `SyncStatus.storeUnavailable`, raised by whichever sync loop
   * meets it first, with [#63](https://github.com/stainii/task/issues/63) owning the screen for it.
   * So this keeps whatever it last held rather than blanking the list on a transient failure.
   */
  private async reload(): Promise<void> {
    try {
      const tasks = await this.store.tasks();
      this.asOf.set(today(this.now()));
      this.held.set(tasks);
    } catch (error) {
      console.error('The overview could not read the local store.', error);
    }
  }
}
