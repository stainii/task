import { IsoDate } from './dates';
import { completePatch, patchOn } from './patches';
import { renderTemplate } from './render';
import { TaskPatch } from './task';
import { TemplateRow } from './templates';

/**
 * *"I already did this"* — **one button with two shapes**
 * ([ADR-0011](../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)).
 *
 * Both capture paths land here: the templates list's ✓ and the omnibox's template rows. Which shape
 * you get is chosen by the **data**, never by where you clicked, which is what keeps typing a
 * chore's name meaning the same thing whether or not a task happens to exist for it.
 *
 * **It is built client-side and written through the patch outbox, not by a server endpoint.** That
 * is the requirement rather than a preference: this is housagotchi's entire interaction, performed
 * while doing chores around the house, and a server call cannot happen when there is no server. A
 * firing endpoint would have kept rendering in one place, and was rejected on exactly this.
 */

/**
 * The gap between the creating patch and the completing one, and it is **load-bearing**.
 *
 * The fold breaks a tie on the patch id compared as a string, so two patches sharing an instant
 * order by a minted UUID — and a completion sorting before its own creation folds to a task that is
 * `OPEN`, because the creation's `status` is then the last one applied. ADR-0005's importer puts
 * exactly one second between its synthesised pair for this reason, and this is the live path it
 * says it produces identical rows to.
 */
const COMPLETION_GAP_MS = 1_000;

/**
 * The patches that record *this was done, on this day*.
 *
 * - **A task for this template is open** → complete it, with the chosen date. One patch.
 * - **Nothing is open** → mint a task **created and completed in the same breath**, both patches
 *   carrying the chosen date as the task's own dates. Two patches, and ADR-0005's migrated-execution
 *   shape exactly — so a migrated execution and a live out-of-band completion are the same rows and
 *   *"when did I last do this"* stays one query over one history.
 *
 * **The write clock is never backdated.** The task is dated by the day it was done; the patches are
 * dated `now`, because `dateTime` orders the fold and a backdated one loses to any later edit from
 * another device.
 *
 * **A stated limit: variables are not asked for here.** A manual template carrying `${…}` renders
 * with none supplied, so the placeholder is left standing and the task is named for it — fixture
 * 02's rule, deliberately, since substituting it away would produce a plausible name nobody typed.
 * The ✓ is a chore affordance and the 44 real chore templates have no variables; a workshop template
 * is run, not ticked.
 *
 * @param definitionIndex which definition was done. The affordance **picks a task, not a template**
 *                        (ADR-0011): conjuring every definition as completed would complete tasks
 *                        the user never named, which is template-level completion in a different hat.
 */
export function didItPatches(
  row: TemplateRow,
  definitionIndex: number,
  completedOn: IsoDate,
  now: Date,
): TaskPatch[] {
  if (row.openTask !== null) {
    return [completePatch(row.openTask, now, completedOn)];
  }

  // Rendered whole rather than field by field: rendering is the one place a template becomes tasks,
  // and reading half of it here would be a rendering rule with no fixture behind it. The day it was
  // done is both the firing date and the anchor — there was no other date this happened on.
  const firing = renderTemplate(row.template, {
    firingDate: completedOn,
    anchor: completedOn,
    variables: {},
  });
  const definition = firing.definitions[definitionIndex];

  const taskId = crypto.randomUUID();

  const creation = patchOn(taskId, now, {
    name: definition.name,
    // The task's own clock, not the patch's: this is the day the work happened. An instant, because
    // that is the field's shape — **UTC midnight of that day**, for `dates.ts`'s reason: it is the
    // one point on that date with no zone in it to shift. The server reads it back as a `LocalDate`
    // in `task.time-zone` to date the firing, and midnight UTC lands on the intended day there
    // however far from home the device is. Local midnight would too, from Brussels; this one also
    // does from a laptop in New York.
    creationDateTime: new Date(`${completedOn}T00:00:00.000Z`).toISOString(),
    startDate: definition.startDate,
    dueDate: definition.dueDate,
    context: firing.context,
    importance: definition.importance,
    description: definition.description,
    status: 'OPEN',
    completedOn: null,
    // Provenance, on the creating patch — the same discriminator the API uses, so it lands exactly
    // once per task. Without it the min/max anchor cannot see this completion at all, and the whole
    // point of recording it is that the template's clock moves.
    taskTemplateId: row.template.id,
  });

  const completion = patchOn(taskId, new Date(now.getTime() + COMPLETION_GAP_MS), {
    status: 'COMPLETED',
    completedOn,
  });

  return [creation, completion];
}
