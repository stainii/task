import { Importance, IMPORTANCES, Task, TaskPatch, TaskStatus, TASK_STATUSES } from './task';

/**
 * The fold, in TypeScript.
 *
 * Sort the history, resolve voids, blank the task, replay from the creation patch. **For each field
 * the winner is the newest patch that touches it**, whatever order the patches arrived in — which
 * is the whole point, because a patch written on a train on Monday can reach this device on
 * Wednesday and must still lose to a Tuesday edit of the same field.
 *
 * **This algorithm exists twice**: here, and in `Task.foldOf` on the back end. The client folds so
 * that offline use works and optimistic edits are instant; the server folds because it is the
 * authority. If the two drift by one rule, the device shows one thing and the server believes
 * another **with no error anywhere** — so every rule is pinned by a shared fixture in
 * `/fold-fixtures/`, run by both suites. **No fold rule without a fixture**, and this file is a
 * deliberate line-by-line mirror of the Java one rather than an idiomatic rewrite of it.
 *
 * See [ADR-0004](../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md).
 */

/** A history that cannot produce a whole task — a required field no patch ever set. */
export class IncompleteTaskHistoryError extends Error {
  constructor(
    readonly taskId: string,
    readonly field: string,
  ) {
    super(`Task ${taskId} has no ${field}: its history cannot be folded into a whole task.`);
    this.name = 'IncompleteTaskHistoryError';
  }
}

/**
 * Fold order: the client's clock, ties broken by patch id.
 *
 * The tie-break is not decoration. Two devices can mint patches in the same millisecond, and
 * `sequence` cannot break the tie because a client folds patches it has not yet sent and so has no
 * sequence for them. The id is the only ordering both sides can compute — **compared as a string**,
 * because Java's `UUID.compareTo` compares signed longs and would order differently.
 */
export function compareFoldOrder(a: TaskPatch, b: TaskPatch): number {
  const difference = instantOf(a) - instantOf(b);
  if (difference !== 0) {
    return difference;
  }
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

/**
 * Milliseconds since the epoch, throwing rather than returning `NaN`.
 *
 * A `NaN` here would not fail — it would make the comparator return `NaN` for every pair, leaving
 * the history in whatever order it happened to be in and the fold silently dependent on arrival
 * order, the one thing it may never depend on. The Java side gets this for free: an unparseable
 * date-time never becomes an `Instant` in the first place.
 */
function instantOf(patch: TaskPatch): number {
  const millis = Date.parse(patch.dateTime);
  if (Number.isNaN(millis)) {
    throw new Error(`Patch ${patch.id} has an unparseable date-time: '${patch.dateTime}'.`);
  }
  return millis;
}

/** The blank task the fold replays onto. Mutable and local, so the result never is. */
interface FoldState {
  name: string | null;
  creationDateTime: string | null;
  startDate: string | null;
  dueDate: string | null;
  context: string | null;
  importance: Importance | null;
  description: string | null;
  status: TaskStatus | null;
  completedOn: string | null;
  cancelledOn: string | null;
  taskTemplateId: string | null;
  occurrenceId: string | null;
}

/**
 * Every field a patch can change, with the code that applies it. **One table**, mirroring the Java
 * `FIELDS` map key for key: a field in one and not the other is a divergence no fixture can catch,
 * because a fixture can only test the keys somebody thought to write down.
 *
 * The values stay strings. Only the two enums are parsed, because only they have a value the fold
 * itself must be able to reason about — a malformed date is the server door's problem (it rejects
 * unknown and unparseable changes with a `400`), and inventing a client-side parse rule with no
 * fixture behind it is precisely the drift the fixtures exist to prevent.
 */
const FIELDS: Record<string, (state: FoldState, value: string | null) => void> = {
  name: (state, value) => (state.name = value),
  creationDateTime: (state, value) => (state.creationDateTime = value),
  startDate: (state, value) => (state.startDate = value),
  dueDate: (state, value) => (state.dueDate = value),
  context: (state, value) => (state.context = value),
  importance: (state, value) => (state.importance = parseImportance(value)),
  description: (state, value) => (state.description = value),
  status: (state, value) => (state.status = parseStatus(value)),
  completedOn: (state, value) => (state.completedOn = value),
  cancelledOn: (state, value) => (state.cancelledOn = value),
  taskTemplateId: (state, value) => (state.taskTemplateId = value),
  occurrenceId: (state, value) => (state.occurrenceId = value),
};

/** Case-sensitive, like Java's `Importance.valueOf`. */
function parseImportance(value: string | null): Importance | null {
  if (value === null) {
    return null;
  }
  if (!(IMPORTANCES as readonly string[]).includes(value)) {
    throw new Error(`'${value}' is not a valid importance.`);
  }
  return value as Importance;
}

/**
 * Case-**in**sensitive, like Java's `TaskStatus.parse`. The asymmetry with importance is mirrored
 * rather than chosen: the two implementations agreeing matters more than either being tidy, and
 * years of migrated history run through here.
 */
function parseStatus(value: string | null): TaskStatus | null {
  if (value === null) {
    return null;
  }
  const match = TASK_STATUSES.find((status) => status.toLowerCase() === value.toLowerCase());
  if (match === undefined) {
    throw new Error(`Task status with name ${value} does not exist.`);
  }
  return match;
}

/**
 * The fold. Every rule below is pinned by a file in `/fold-fixtures/`.
 */
export function foldOf(taskId: string, history: readonly TaskPatch[]): Task {
  if (history.length === 0) {
    throw new Error(`A task cannot be folded from an empty history: ${taskId}`);
  }

  // Sorted, then deduplicated by id: a client sees its own patches echo back on the stream and a
  // retry may have stored one twice, so the same patch turning up twice must fold to the same task
  // — and to the same history — as it turning up once.
  const seen = new Set<string>();
  const ordered = [...history].sort(compareFoldOrder).filter((patch) => {
    if (seen.has(patch.id)) {
      return false;
    }
    seen.add(patch.id);
    return true;
  });

  const creationPatch = ordered[0];
  const { voided, creationVoided } = resolveVoids(ordered, creationPatch);

  const state: FoldState = {
    name: null,
    creationDateTime: null,
    startDate: null,
    dueDate: null,
    context: null,
    importance: null,
    description: null,
    status: null,
    completedOn: null,
    cancelledOn: null,
    taskTemplateId: null,
    occurrenceId: null,
  };

  for (const patch of ordered) {
    if (!voided.has(patch.id)) {
      apply(state, patch);
    }
  }

  // Voiding the creation patch cannot un-create a task, so it completes it instead. The creation
  // patch itself still applies — skipping it would erase every field the fold has to produce.
  if (creationVoided) {
    state.status = 'COMPLETED';
  }

  return {
    id: taskId,
    name: required(state.name, taskId, 'name'),
    creationDateTime: required(state.creationDateTime, taskId, 'creationDateTime'),
    startDate: required(state.startDate, taskId, 'startDate'),
    dueDate: state.dueDate,
    context: required(state.context, taskId, 'context'),
    importance: state.importance ?? 'IMPORTANT',
    description: state.description,
    status: state.status ?? 'OPEN',
    completedOn: state.completedOn,
    cancelledOn: state.cancelledOn,
    taskTemplateId: state.taskTemplateId,
    occurrenceId: state.occurrenceId,
    history: ordered,
  };
}

interface ResolvedVoids {
  readonly voided: ReadonlySet<string>;
  readonly creationVoided: boolean;
}

/**
 * Resolves which patches are voided, **walking the history backwards**.
 *
 * Backwards, because a void can itself be voided — undo-of-undo is voiding the void — and a void
 * only ever removes a patch that precedes it in fold order. Walking from the end means a void that
 * has itself been voided is already known to be inactive by the time it would have removed
 * anything, and it makes a cycle of two patches voiding each other impossible to express rather
 * than something the algorithm has to survive.
 */
function resolveVoids(ordered: readonly TaskPatch[], creationPatch: TaskPatch): ResolvedVoids {
  const voided = new Set<string>();
  let creationVoided = false;
  for (let i = ordered.length - 1; i >= 0; i--) {
    const patch = ordered[i];
    if (voided.has(patch.id) || patch.voids === null) {
      continue;
    }
    if (patch.voids === creationPatch.id) {
      creationVoided = true;
    } else {
      voided.add(patch.voids);
    }
  }
  return { voided, creationVoided };
}

function apply(state: FoldState, patch: TaskPatch): void {
  for (const [field, value] of Object.entries(patch.changes)) {
    const set = FIELDS[field];

    // A key the fold does not know names no field, so it changes nothing. The API rejects unknown
    // keys at the door rather than here, because the fold also replays years of migrated history
    // that the API never saw.
    if (set !== undefined) {
      set(state, value);
    }
  }
}

function required<T>(value: T | null, taskId: string, field: string): T {
  if (value === null) {
    throw new IncompleteTaskHistoryError(taskId, field);
  }
  return value;
}

/**
 * When this task stopped being open, on the client's own clock, or null while it is still open.
 *
 * The date-time of the newest applied patch that closed it — which is the moment the user closed
 * it, not the moment this device heard about it. Used for the one-day horizon on closed tasks.
 *
 * A task closed by **voiding its creation patch** is dated from the void. Its creation patch still
 * applies, `status: OPEN` and all, so reading the newest status change would date the closure to
 * the moment the task was *made* — and a task undone the day it was created would be swept away
 * within the hour, taking the undo of the undo with it.
 */
export function closedAt(task: Task): string | null {
  if (task.status === 'OPEN') {
    return null;
  }
  const creationPatch = task.history[0];
  const { voided, creationVoided } = resolveVoids(task.history, creationPatch);

  for (let i = task.history.length - 1; i >= 0; i--) {
    const patch = task.history[i];
    if (voided.has(patch.id)) {
      continue;
    }
    if (creationVoided ? patch.voids === creationPatch.id : 'status' in patch.changes) {
      return patch.dateTime;
    }
  }
  return task.history[task.history.length - 1].dateTime;
}
