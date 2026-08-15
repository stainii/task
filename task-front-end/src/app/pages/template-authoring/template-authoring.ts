import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  PendingTasks,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import { NOW } from '../../clock';
import { IsoDate, today } from '../../domain/dates';
import { renderTemplate, TemplateRenderError } from '../../domain/render';
import { IMPORTANCES, Importance, Task } from '../../domain/task';
import { RenderedDefinition, TaskTemplate, Weekday, WEEKDAYS } from '../../domain/template';
import {
  CalendarUnit,
  DefinitionDraft,
  draftOf,
  emptyDraft,
  MonthlyOn,
  problemsOf,
  templateOf,
  TemplateDraft,
  TriggerKind,
  variablesOfDraft,
} from '../../domain/template-draft';
import { Ordinal } from '../../domain/template';
import { LocalStore } from '../../store/local-store';
import { TemplateService } from '../../sync/templates';
import { importanceLabel } from '../../ui/wording';

/**
 * **One authoring screen, and the trigger is the first field on it**
 * ([ADR-0013](../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)).
 *
 * Three separate forms behind a chooser were considered and rejected: they match the three user
 * intents exactly, and they would have re-forbidden the one combination this screen exists to
 * unlock — **any trigger may carry any number of tasks**. That is not theoretical. Both halves of
 * it are already in production as workarounds: `Vissen eten geven (5 dagen)` is five identical
 * definitions at offsets 0–4 because a manual template could not repeat, and `Beddengoed wassen
 * **en** bed stofzuigen` is two chores hand-joined in a name string because a recurring one could
 * only ever be one task.
 *
 * **Hand-rolled, like the task dialog.** `docs/agents/angular-skills.md` names this screen as the
 * natural first use of Signal Forms; it was put to the author and declined, on the grounds that what
 * this form mostly is — a discriminated union whose branch swaps the fields under it — is not the
 * flat field set Signal Forms is aimed at, and that one form idiom in the app beats two.
 *
 * The named risk ADR-0013 accepted is that one form carrying trigger, offsets, anchors, variables
 * and context becomes a control panel. **Progressive disclosure is the answer**: a task collapses to
 * its name and its importance, and the offsets and description are behind the `▾` — which is what
 * keeps the 44 three-field recurring templates from paying for the 3 workshop-shaped ones.
 */
@Component({
  selector: 'app-template-authoring',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './template-authoring.html',
  styleUrl: './template-authoring.css',
})
export class TemplateAuthoring {
  /** The template's id, or the literal `new`. */
  readonly id = input.required<string>();

  private readonly store = inject(LocalStore);
  private readonly templates = inject(TemplateService);
  private readonly router = inject(Router);
  private readonly now = inject(NOW);
  private readonly pending = inject(PendingTasks);

  protected readonly IMPORTANCES = IMPORTANCES;
  protected readonly WEEKDAYS = WEEKDAYS;
  protected readonly ORDINALS: readonly Ordinal[] = ['FIRST', 'SECOND', 'THIRD', 'FOURTH', 'LAST'];
  protected readonly importanceLabel = importanceLabel;

  /** ADR-0004: template editing is **visibly** unavailable offline, not silently failing. */
  protected readonly writable = this.templates.writable;

  protected readonly creating = computed(() => this.id() === 'new');

  private readonly stored = signal<TaskTemplate | null>(null);

  /** Whether this template has ever produced a task. A **count, not a judgement** (ADR-0013). */
  private readonly hasTasks = signal(false);

  /**
   * The form's state.
   *
   * A `linkedSignal` over the loaded template rather than an effect that assigns: reopening the
   * screen for a different template must show that template, and an effect writing into a signal is
   * the propagation Angular's own guidance rules out — the same call #59 made for the task dialog's
   * draft, and this screen keeps the app to one idiom.
   */
  protected readonly draft = linkedSignal<TaskTemplate | null, TemplateDraft>({
    source: this.stored,
    computation: (stored) => (stored === null ? emptyDraft() : draftOf(stored)),
  });

  /** Shown only after Save has been pressed: a form that scolds you as you type is a form that lies. */
  protected readonly problems = signal<readonly string[]>([]);

  /** Inferred from the text as it is typed. There is no declared list (ADR-0013). */
  protected readonly variables = computed(() => variablesOfDraft(this.draft()));

  protected readonly active = computed(() => this.stored()?.active ?? true);

  protected readonly deletable = computed(() => !this.creating() && !this.hasTasks());

  /** Only a manual template is run by hand; the other two come round on their own. */
  protected readonly runnable = computed(() => this.draft().kind === 'MANUAL' && !this.creating());

  /** The run panel's own state: the anchor date and the answers to the variables. */
  protected readonly running = signal(false);
  protected readonly anchorDate = signal<IsoDate | ''>('');
  protected readonly answers = signal<Record<string, string>>({});

  protected readonly anchorQuestion = computed(() => {
    const label = this.draft().anchorLabel.trim();
    // The author's own wording, and a plain fallback where they wrote none — a template run without
    // an anchor is a real case (`/render-fixtures/09`), so the field cannot simply disappear.
    return label === '' ? 'What date does this hang off?' : label;
  });

  /**
   * **The preview: dates when dates exist, and shape when they do not** (ADR-0013).
   *
   * Authoring a manual template shows the *shape*, because there is no anchor date yet and inventing
   * one would display concrete dates that are wrong for every actual run. Running one shows real
   * dates, and that is the check that replaces an edit-before-create step.
   *
   * It renders through the **same renderer the server uses**, pinned by `/render-fixtures/` — a
   * preview computed some other way would be a preview that can disagree with what gets created.
   */
  protected readonly preview = computed<readonly RenderedDefinition[]>(() => {
    const anchor = this.anchorDate();
    if (anchor === '') {
      return [];
    }
    try {
      return renderTemplate(templateOf(this.draft(), this.id()), {
        firingDate: today(this.now()),
        anchor,
        variables: this.answers(),
      }).definitions;
    } catch (error) {
      // A template that cannot render one of its tasks produces **none** (TODO-022). The preview
      // says so rather than showing the ones that happened to work, because that is exactly what
      // running it would do.
      this.renderProblem.set(error instanceof TemplateRenderError ? error.message : String(error));
      return [];
    }
  });

  protected readonly renderProblem = signal<string | null>(null);

  /**
   * The shape a manual template describes, without dates: *2 weeks before → 1 week before the
   * workshop*, using the anchor's own name.
   */
  protected readonly shape = computed(() =>
    this.draft().definitions.map((definition) => ({
      name: definition.name,
      when: shapeOf(definition, this.anchorQuestion()),
    })),
  );

  /** ADR-0013: the timeline earns its place only when there is something to compare. */
  protected readonly showTimeline = computed(() => this.draft().definitions.length > 1);

  constructor() {
    effect(() => {
      const id = this.id();
      void this.pending.run(() => this.load(id));
    });
  }

  private async load(id: string): Promise<void> {
    if (id === 'new') {
      this.stored.set(null);
      this.hasTasks.set(false);
      return;
    }
    try {
      const [templates, tasks] = await Promise.all([this.store.templates(), this.store.tasks()]);
      this.stored.set(templates.find((template) => template.id === id) ?? null);
      this.hasTasks.set(tasks.some((task: Task) => task.taskTemplateId === id));
    } catch (error) {
      console.error('The authoring screen could not read the local store.', error);
    }
  }

  protected edit<K extends keyof TemplateDraft>(key: K, value: TemplateDraft[K]): void {
    this.draft.update((draft) => ({ ...draft, [key]: value }));
  }

  protected editText(key: keyof TemplateDraft, event: Event): void {
    this.edit(key, (event.target as HTMLInputElement).value as never);
  }

  protected editNumber(key: keyof TemplateDraft, event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.edit(key, (Number.isFinite(value) ? value : 0) as never);
  }

  protected editKind(event: Event): void {
    this.edit('kind', (event.target as HTMLSelectElement).value as TriggerKind);
  }

  protected editUnit(event: Event): void {
    this.edit('calendarUnit', (event.target as HTMLSelectElement).value as CalendarUnit);
  }

  protected editMonthlyOn(event: Event): void {
    this.edit('monthlyOn', (event.target as HTMLSelectElement).value as MonthlyOn);
  }

  protected editOrdinal(event: Event): void {
    this.edit('ordinal', (event.target as HTMLSelectElement).value as Ordinal);
  }

  protected editWeekday(key: 'nthWeekday', event: Event): void {
    this.edit(key, (event.target as HTMLSelectElement).value as Weekday);
  }

  /** Several weekdays in one rule, so *"Tuesday and Thursday"* is one template rather than two. */
  protected toggleWeekday(weekday: Weekday): void {
    this.draft.update((draft) => {
      const on = draft.weekdays.includes(weekday);
      // Never down to none: a weekly rule with no weekday names no date, and the server refuses it.
      const weekdays = on
        ? draft.weekdays.filter((other) => other !== weekday)
        : [...draft.weekdays, weekday];
      return { ...draft, weekdays: weekdays.length === 0 ? draft.weekdays : weekdays };
    });
  }

  protected editDefinition<K extends keyof DefinitionDraft>(
    index: number,
    key: K,
    value: DefinitionDraft[K],
  ): void {
    this.draft.update((draft) => ({
      ...draft,
      definitions: draft.definitions.map((definition, at) =>
        at === index ? { ...definition, [key]: value } : definition,
      ),
    }));
  }

  protected editDefinitionText(index: number, key: 'name' | 'description', event: Event): void {
    this.editDefinition(index, key, (event.target as HTMLInputElement).value);
  }

  protected editDefinitionImportance(index: number, event: Event): void {
    this.editDefinition(
      index,
      'importance',
      (event.target as HTMLSelectElement).value as Importance,
    );
  }

  /** An emptied offset field is **no offset**, not zero: zero is *on the anchor*, which is a date. */
  protected editOffset(
    index: number,
    key: 'startDateOffsetDays' | 'dueDateOffsetDays',
    event: Event,
  ): void {
    const raw = (event.target as HTMLInputElement).value.trim();
    this.editDefinition(index, key, raw === '' ? null : Number(raw));
  }

  protected addDefinition(): void {
    this.draft.update((draft) => ({
      ...draft,
      definitions: [
        ...draft.definitions,
        {
          id: null,
          name: '',
          description: '',
          importance: 'IMPORTANT',
          startDateOffsetDays: null,
          dueDateOffsetDays: null,
        },
      ],
    }));
  }

  protected removeDefinition(index: number): void {
    this.draft.update((draft) => ({
      ...draft,
      definitions: draft.definitions.filter((_definition, at) => at !== index),
    }));
  }

  /** Which task's drawer is open. One at a time: the drawer is for reading, not for comparing. */
  protected readonly opened = signal<number | null>(null);

  protected toggleDrawer(index: number): void {
    this.opened.update((open) => (open === index ? null : index));
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const problems = problemsOf(draft);
    this.problems.set(problems);
    if (problems.length > 0) {
      return;
    }

    // The id is minted here for a new template, exactly as it is for a task: the client owns it, so
    // there is nothing to wait for and nothing to reconcile afterwards.
    const id = this.creating() ? crypto.randomUUID() : this.id();
    await this.templates.save(templateOf(draft, id), { existing: !this.creating() });
    await this.router.navigate(['/templates']);
  }

  protected async deactivate(): Promise<void> {
    await this.templates.deactivate(this.id());
    await this.router.navigate(['/templates']);
  }

  protected async reactivate(): Promise<void> {
    await this.templates.reactivate(this.id());
  }

  protected async remove(): Promise<void> {
    await this.templates.remove(this.id());
    await this.router.navigate(['/templates']);
  }

  protected startRun(): void {
    this.running.set(true);
    this.anchorDate.set('');
    this.answers.set({});
  }

  protected cancelRun(): void {
    this.running.set(false);
  }

  protected answer(name: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.answers.update((answers) => ({ ...answers, [name]: value }));
  }

  /**
   * **Running creates the tasks immediately** (ADR-0013). No edit-before-create step: the tasks are
   * ordinary tasks the moment they exist, and the overview already gives every tool needed to edit,
   * complete or cancel them one screen later. The preview above is the check.
   */
  protected async run(): Promise<void> {
    const anchor = this.anchorDate();
    await this.templates.run(this.id(), {
      variables: this.answers(),
      anchorDate: anchor === '' ? null : anchor,
    });
    this.running.set(false);
    await this.router.navigate(['/']);
  }
}

/** *2 weeks before → 1 week before the workshop*, said without a date (ADR-0013's preview rule). */
function shapeOf(definition: DefinitionDraft, anchor: string): string {
  const start = offsetPhrase(definition.startDateOffsetDays);
  const due = offsetPhrase(definition.dueDateOffsetDays);
  if (start === null && due === null) {
    return `on ${anchor}`;
  }
  return `${start ?? 'from the day it fires'} → ${due ?? 'no due date'}, around ${anchor}`;
}

function offsetPhrase(days: number | null): string | null {
  if (days === null) {
    return null;
  }
  if (days === 0) {
    return 'on the day';
  }
  const count = Math.abs(days);
  const unit = count === 1 ? 'day' : 'days';
  return days < 0 ? `${count} ${unit} before` : `${count} ${unit} after`;
}
