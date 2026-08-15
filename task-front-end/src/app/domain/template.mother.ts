import { IsoDate } from './dates';
import { Importance } from './task';
import { StoredTrigger, TaskDefinition, TaskTemplate } from './template';

/**
 * A template builder for tests. **Imported only by `*.spec.ts`** — nothing in `src/main` reaches it.
 *
 * It builds the wire shape, because that is what the client holds: templates arrive from
 * `GET /api/task-templates` and are stored as they arrived, with no fold and no local model to
 * diverge from them.
 */

let minted = 0;

export interface TemplateOverrides {
  readonly id?: string;
  readonly name?: string;
  readonly context?: string;
  readonly active?: boolean;
  readonly activeSince?: IsoDate;
  readonly trigger?: StoredTrigger;
  readonly taskDefinitions?: readonly TaskDefinition[];
}

export function aTemplate(overrides: TemplateOverrides = {}): TaskTemplate {
  const id = overrides.id ?? `template-${++minted}`;
  return {
    id,
    name: overrides.name ?? `Template ${id}`,
    context: overrides.context ?? 'house',
    active: overrides.active ?? true,
    activeSince: overrides.activeSince ?? '2026-01-01',
    // Min/max by default: 44 of the 47 real templates are exactly that shape, so a test that says
    // nothing about the trigger gets the one the author actually has.
    trigger: overrides.trigger ?? minMax(10, 3),
    taskDefinitions: overrides.taskDefinitions ?? [aDefinition({ name: overrides.name })],
  };
}

export function aDefinition(overrides: Partial<TaskDefinition> = {}): TaskDefinition {
  return {
    name: overrides.name ?? 'Do the thing',
    startDateOffsetDays: overrides.startDateOffsetDays ?? null,
    dueDateOffsetDays: overrides.dueDateOffsetDays ?? null,
    importance: (overrides.importance ?? null) as Importance | null,
    description: overrides.description ?? null,
  };
}

/** *Comes round every `interval` days, and I have `window` days to do it* — the form's vocabulary. */
export function minMax(interval: number, window: number): StoredTrigger {
  return { type: 'MIN_MAX', minDays: interval, maxDays: interval + window };
}

export function manual(anchorLabel: string | null = null): StoredTrigger {
  return { type: 'MANUAL', anchorLabel };
}
