-- #75 / ADR-0022: a min/max round starts when you closed it, not when it fired.
--
-- `MinMax` counted its interval from the firing date of the last closed task, so a chore you were
-- later than the interval with came back the same hour you ticked it off, already overdue. The
-- round now starts at the closure date - `completed_on` for a completion, and this column for a
-- cancellation, which had no date of its own.
--
-- It is always today at the moment of cancelling and is never editable: "I cancelled this on
-- Tuesday" means nothing, which is exactly why it does not get the affordance `completed_on` has.

ALTER TABLE task
    ADD COLUMN cancelled_on DATE;

-- Existing cancellations are backfilled with their **firing date**, which reproduces today's
-- scheduling exactly - so no template's rhythm moves on the day this ships, and afterwards there is
-- one rule with no exception. Read the alternative that was rejected in ADR-0022: leaving these
-- null and falling back to the firing date at read time would run the fallback only for the
-- templates that already carry the defect.
--
-- The zone is Brussels, as in V4: a firing date is a day and `creation_date_time` is an instant, so
-- something has to name the zone that turns one into the other.
UPDATE task
SET cancelled_on = (creation_date_time AT TIME ZONE 'Europe/Brussels')::date
WHERE status = 'CANCELLED';

-- The history is the truth and the columns are its fold (ADR-0004), so the patch that cancelled the
-- task carries the same value. Without this the backfill would survive only until the next patch on
-- that task refolded it away, and ADR-0005's stored-vs-folded diff would report every cancelled task
-- as a mismatch.
WITH cancelling_patch AS (SELECT DISTINCT ON (p.task_id) p.id, t.cancelled_on
                          FROM task_patch p
                                   JOIN task t ON t.id = p.task_id
                          WHERE t.status = 'CANCELLED'
                            AND p.changes ->> 'status' = 'CANCELLED'
                          -- Fold order: the client's clock, ties broken by the patch id as a string.
                          ORDER BY p.task_id, p.date_time DESC, p.id::text DESC)
UPDATE task_patch p
SET changes = p.changes || jsonb_build_object('cancelledOn', to_jsonb(c.cancelled_on::text))
FROM cancelling_patch c
WHERE p.id = c.id;
