import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { MatTooltip } from '@angular/material/tooltip';
import { Router } from '@angular/router';

import { NOW } from '../../clock';
import { bucketOf } from '../../domain/buckets';
import { addDays, IsoDate } from '../../domain/dates';
import {
  cancelPatch,
  completePatch,
  COMPLETED_ON_PRESETS,
  PanelAction,
  POSTPONE_PRESETS,
  DatePreset,
  postponePatch,
} from '../../domain/patches';
import { Task, TaskPatch } from '../../domain/task';
import { Confirms } from '../../ui/confirms';
import { GlyphButton } from '../../ui/glyph-button';
import { linkify } from '../../ui/linkify';
import { dueLabel, dueTone } from '../../ui/wording';

/**
 * One task, as an expandable row (FE-005, FE-006).
 *
 * **Expand-to-reveal, and swipe everywhere.** Always-visible row buttons were rejected — originally
 * on a measurement of three *text* buttons costing ~110px and truncating task names, which
 * [ADR-0019](../../../../../docs/adr/0019-verbs-are-glyphs-facts-are-words.md) retired when it made
 * the verbs glyphs. The rejection stands on a new reason: **swipe already covers complete and
 * cancel**, so a permanent row would put four affordances on every card to serve the two verbs
 * swipe does not reach.
 *
 * **Swipe was never touch-only.** Portal's `(swiperight)`/`(pan)` ran on HammerJS, which binds
 * mouse drag too — the desktop has had this gesture for years. HammerJS is unmaintained since 2016
 * and Angular has dropped `HammerModule`, so this is Pointer Events: mouse, touch and pen in one
 * code path, and no dependency.
 *
 * **`CANCELLED` is a real verb here, for the first time in the app's history.** Portal's panel had
 * *Details* and *Complete*, and nothing in the system could set `CANCELLED` — so the only way to
 * clear an abandoned task was to press Complete, writing a false completion into the very history
 * ADR-0011 reads as a template's clock.
 */
@Component({
  selector: 'app-task-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [GlyphButton, MatMenu, MatMenuItem, MatMenuTrigger, MatTooltip],
  templateUrl: './task-panel.html',
  styleUrl: './task-panel.css',
})
export class TaskPanel {
  /**
   * How far a gesture has to travel before it means anything, in CSS pixels.
   *
   * Portal's HammerJS default was 10px, which on a scrolling list of rows is an accident waiting to
   * happen for a verb that closes a task. This is the prototype's number, driven with a mouse and a
   * thumb.
   */
  static readonly SWIPE_THRESHOLD = 110;

  /** Past this, a pointer has dragged rather than clicked, and the row must not toggle. */
  private static readonly DRAG_SLOP = 6;

  private readonly router = inject(Router);
  private readonly now = inject(NOW);
  private readonly confirms = inject(Confirms);

  readonly task = input.required<Task>();

  /** Passed in rather than read, so a whole screenful is banded and worded against one date. */
  readonly today = input.required<IsoDate>();

  /**
   * Whether to show the context chip.
   *
   * Off inside a context (`/in/:value`), where every row on screen carries the same chip: about
   * 60px of constant that the name can have instead. The panel is told rather than reading the
   * route, so it stays the axis-agnostic thing ADR-0006 asks for — it does not know that the
   * grouping axis is context, only that its caller has already said this one.
   */
  readonly showContext = input(true);

  /** A patch this panel made, with the words the undo toast needs. */
  readonly acted = output<PanelAction>();

  protected readonly presets = POSTPONE_PRESETS;

  /** The split Complete button's *done when?* offsets (issue #83): today, yesterday, 2 days ago. */
  protected readonly doneWhenPresets = COMPLETED_ON_PRESETS;

  protected readonly open = signal(false);

  /** How far the current gesture has travelled; 0 whenever no gesture is in flight. */
  protected readonly travel = signal(0);

  protected readonly bucket = computed(() => bucketOf(this.task(), this.today()));
  protected readonly due = computed(() => dueLabel(this.task().dueDate, this.today()));
  protected readonly tone = computed(() => dueTone(this.task().dueDate, this.today()));

  /**
   * The description, split into plain runs and links (ADR-0015: *URLs, and nothing else*).
   *
   * Said out loud when there is none, rather than left blank: an empty body reads as *this panel is
   * broken*, where the fact is *there is nothing more to say about this task*.
   */
  protected readonly description = computed(() => linkify(this.written() ?? 'No description.'));

  /**
   * The description, or `null` where there is nothing written.
   *
   * **One predicate, read by both the row and the body**, because they are two views of the same
   * fact and a description of `'   '` is exactly where they would otherwise disagree: the row would
   * fall silent while the body rendered a blank paragraph instead of saying *No description.* —
   * two answers to *is there anything here*, one screen apart.
   */
  private readonly written = computed(() => {
    const description = this.task().description?.trim();
    return description === undefined || description === '' ? null : description;
  });

  /**
   * Whether there is anything behind the caret.
   *
   * A fact, so a word rather than a glyph (ADR-0019). The collapsed row cannot say this today:
   * {@link description} falls back to *No description.*, so every panel looks equally worth
   * opening and the only way to find the ones with anything in them is to open all of them.
   */
  protected readonly hasDescription = computed(() => this.written() !== null);

  protected readonly rightFill = computed(() => Math.max(0, this.travel()));
  protected readonly leftFill = computed(() => Math.max(0, -this.travel()));

  private start: number | null = null;
  private dragged = false;

  protected toggle(): void {
    // A drag that ends over the row is not a click on it. Without this a gesture that falls short
    // both fails to act and opens the panel, which reads as it having done something else.
    if (this.dragged) {
      return;
    }
    this.open.update((open) => !open);
  }

  protected edit(): void {
    void this.router.navigate(['/task', this.task().id]);
  }

  /**
   * The plain tap on the split button's left half, and where a swipe-right lands: *now*, silently.
   *
   * ADR-0014's line — *acted on in place does not ask*. The ▾ beside it is the only deliberate
   * surface; this path stays a single tap that means today.
   */
  protected complete(): void {
    this.completeAt(this.today());
  }

  /** A preset day from the ▾ menu — today, yesterday or two days ago (issue #83). */
  protected completeOn(preset: DatePreset): void {
    this.completeAt(addDays(this.today(), preset.days));
  }

  /**
   * *In the past…* — anything older than the presets goes through the shell's one confirm, the same
   * *When did you do it?* the omnibox and the templates list use. Nothing is written if it is
   * dismissed.
   */
  protected async completeInThePast(): Promise<void> {
    const on = await this.confirms.ask(this.task().name, this.today());
    if (on !== null) {
      this.completeAt(on);
    }
  }

  /** One completion, whichever surface picked the date. The date rides on the action so the toast
   *  can say which day it filed without reading it back off the patch. */
  private completeAt(on: IsoDate): void {
    const task = this.task();
    this.act(completePatch(task, this.now(), on), 'Completed', { task, on });
  }

  protected cancel(): void {
    this.act(cancelPatch(this.task(), this.now()), 'Cancelled');
  }

  protected postpone(preset: DatePreset): void {
    // Just the participle, like the other two. The preset's own label does not compose into a
    // sentence — *Postponed until in 3 days* — and it says nothing the tap that produced it did
    // not already say.
    this.act(postponePatch(this.task(), preset.days, this.now()), 'Postponed');
  }

  /**
   * The toast names the task as well as the verb, because the row it is about has just left the
   * screen — *Completed* alone would be an offer to undo something you can no longer see.
   */
  private act(patch: TaskPatch, verb: string, completed?: { task: Task; on: IsoDate }): void {
    this.acted.emit({ patch, done: `${verb} — ${this.task().name}`, completed });
  }

  protected began(event: PointerEvent): void {
    this.start = event.clientX;
    this.dragged = false;
    this.travel.set(0);
    // Guarded: the capture is what keeps a fast gesture attached to this row once the pointer
    // leaves it, and jsdom has no implementation of it.
    (event.target as Element).setPointerCapture?.(event.pointerId);
  }

  protected moved(event: PointerEvent): void {
    if (this.start === null) {
      return;
    }
    const travel = event.clientX - this.start;
    if (Math.abs(travel) > TaskPanel.DRAG_SLOP) {
      this.dragged = true;
    }
    this.travel.set(travel);
  }

  protected ended(): void {
    if (this.start === null) {
      return;
    }
    const travel = this.travel();
    this.start = null;
    this.travel.set(0);

    if (travel > TaskPanel.SWIPE_THRESHOLD) {
      this.complete();
    } else if (travel < -TaskPanel.SWIPE_THRESHOLD) {
      this.cancel();
    }
    // `dragged` is deliberately *not* cleared here. The browser dispatches the click that follows a
    // gesture after this handler, so clearing it on a timer is a race with the very event it exists
    // to suppress — the prototype's `setTimeout(0)` only worked by accident of ordering. The next
    // gesture's `began` is the reset, which needs no timing assumption at all.
  }
}
