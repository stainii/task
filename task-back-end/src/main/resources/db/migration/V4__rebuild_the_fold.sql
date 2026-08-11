-- #45 / ADR-0004: the task is the fold of its patch history, and a patch carries two clocks.

-- A patch's date-time is the client's clock, and clients are in Brussels while the container is in
-- UTC. Stored as a local timestamp it silently changed meaning at the boundary: an undo stamped
-- 12:10 in one zone sorts behind an edit stamped 14:06 in another, and the undo does nothing.
ALTER TABLE task_patch
    ALTER COLUMN date_time TYPE TIMESTAMPTZ USING date_time AT TIME ZONE 'Europe/Brussels';

ALTER TABLE task
    ALTER COLUMN creation_date_time TYPE TIMESTAMPTZ USING creation_date_time AT TIME ZONE 'Europe/Brussels';

-- The server's clock. Assigned on receipt, monotonic, and the only cursor the read side uses -
-- querying on date_time never delivers a patch written offline before the reader's cursor.
CREATE SEQUENCE task_patch_sequence AS BIGINT START WITH 1 INCREMENT BY 1;

ALTER TABLE task_patch
    ADD COLUMN sequence BIGINT;

UPDATE task_patch
SET sequence = nextval('task_patch_sequence')
WHERE sequence IS NULL;

ALTER TABLE task_patch
    ALTER COLUMN sequence SET NOT NULL,
    ADD CONSTRAINT uq_task_patch_sequence UNIQUE (sequence);

-- Undo is a void marker, not a compensating patch.
ALTER TABLE task_patch
    ADD COLUMN voids UUID NULL;

-- Two devices editing the same task in the same millisecond is legal, and the fold breaks the tie
-- on the patch id. This constraint would have rejected the second patch with a 500, which under
-- ADR-0004's outbox rule stalls that device's queue permanently.
ALTER TABLE task_patch
    DROP CONSTRAINT uq_task_patch_task_date_time;

-- A patch is immutable and append-only, so there is no update to lose a race over. The aggregate's
-- own version is what serialises two concurrent folds.
ALTER TABLE task_patch
    DROP COLUMN version;

CREATE INDEX idx_task_patch_sequence ON task_patch (sequence);

-- ADR-0018: null importance was undefined, not unimportant - portal's ranking scored it above
-- NOT_SO_IMPORTANT while its buckets treated it as low. The case is deleted rather than ruled on.
UPDATE task
SET importance = 'NOT_SO_IMPORTANT'
WHERE importance IS NULL;

ALTER TABLE task
    ALTER COLUMN importance SET NOT NULL;

-- ADR-0011: when did I do it, as opposed to when did I say so.
ALTER TABLE task
    ADD COLUMN completed_on DATE NULL;

-- ADR-0001: an occurrence is derived, so provenance is a reference on the task and a group key.
ALTER TABLE task
    ADD COLUMN task_template_id UUID NULL,
    ADD COLUMN occurrence_id UUID NULL;

CREATE INDEX idx_task_task_template_id ON task (task_template_id);
