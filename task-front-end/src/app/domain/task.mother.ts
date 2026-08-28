import { IsoDate } from './dates';
import { Importance, Task, TaskPatch, TaskStatus } from './task';

/**
 * A task builder for tests. **Imported only by `*.spec.ts`** — nothing in `src/main` reaches it.
 *
 * It builds the *folded* shape rather than a history, because everything on the overview reads a
 * folded task: the ranking, the bands and the panel all take `Task` and none of them has ever seen
 * a patch. Where a test needs a real history — undo, closure dates — it builds the patches itself,
 * as `local-store.spec.ts` does.
 */

let minted = 0;

export interface TaskOverrides {
  readonly id?: string;
  readonly name?: string;
  readonly creationDateTime?: string;
  readonly startDate?: IsoDate;
  readonly dueDate?: IsoDate | null;
  readonly context?: string;
  readonly importance?: Importance;
  readonly description?: string | null;
  readonly status?: TaskStatus;
  readonly completedOn?: IsoDate | null;
  readonly cancelledOn?: IsoDate | null;
  readonly taskTemplateId?: string | null;
  readonly occurrenceId?: string | null;
  readonly history?: readonly TaskPatch[];
}

export function aTask(overrides: TaskOverrides = {}): Task {
  const id = overrides.id ?? `task-${++minted}`;
  const creationDateTime = overrides.creationDateTime ?? '2026-01-01T09:00:00Z';
  const task = {
    id,
    name: overrides.name ?? `Task ${id}`,
    creationDateTime,
    // Started by default: a test about the bands says so explicitly, and every other test wants a
    // task that is simply on screen.
    startDate: overrides.startDate ?? '2020-01-01',
    dueDate: overrides.dueDate ?? null,
    context: overrides.context ?? 'house',
    importance: overrides.importance ?? 'IMPORTANT',
    description: overrides.description ?? null,
    status: overrides.status ?? 'OPEN',
    completedOn: overrides.completedOn ?? null,
    cancelledOn: overrides.cancelledOn ?? null,
    taskTemplateId: overrides.taskTemplateId ?? null,
    occurrenceId: overrides.occurrenceId ?? null,
    history: [],
  } satisfies Task;

  return {
    ...task,
    // **A creation patch carries every field the fold requires**, because the fold replays from it
    // and a history missing one produces no task at all. Building it from the task above rather
    // than beside it is what keeps the two from disagreeing — a test that re-folds this history
    // must get the same task back.
    history: overrides.history ?? [
      {
        id: `${id}-creation`,
        taskId: id,
        dateTime: creationDateTime,
        sequence: null,
        voids: null,
        changes: {
          name: task.name,
          creationDateTime: task.creationDateTime,
          startDate: task.startDate,
          dueDate: task.dueDate,
          context: task.context,
          importance: task.importance,
          description: task.description,
          status: task.status,
          completedOn: task.completedOn,
          taskTemplateId: task.taskTemplateId,
          occurrenceId: task.occurrenceId,
        },
      },
    ],
  };
}
