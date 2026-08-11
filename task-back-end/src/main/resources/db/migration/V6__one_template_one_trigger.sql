-- #47 / ADR-0001, ADR-0013, ADR-0017: TaskTemplate absorbs RecurringTaskTemplate. One aggregate,
-- one sealed Trigger, and three concepts deleted rather than moved.
--
-- Nothing is preserved across this migration. There is no production data in this database - portal
-- is still production, and ADR-0005's importer builds the new tables from the frozen archive, not
-- from these rows. Migrating dev fixtures into a shape the importer will overwrite would be work
-- with a reader of exactly zero.

-- `Execution` is deleted. An occurrence is derived, never stored: the firing date is the task's
-- creation date, the close date is the patch that closed it, and "does this template have an open
-- occurrence?" is a query over tasks. Safe only because tasks have no delete endpoint.
DROP TABLE IF EXISTS execution;

-- The merge itself. Everything recurring_task_template carried is now a column of task_template or
-- a Trigger, and `active_task` - the flag that froze a template forever once its task was created -
-- has no successor at all.
DROP TABLE IF EXISTS recurring_task_template;

-- `variableNames` is deleted: any placeholder in a definition's name or description IS a variable.
-- Portal kept both a declared list and the placeholders, and they drifted - one template declares
-- four and uses three, so a "lector" variable has been asked for and discarded for years. With
-- inference that state cannot be represented.
--
-- (Written without the placeholder syntax itself: Flyway reads dollar-brace as its own placeholder
-- and refuses to parse the file, comment or not. See VariableUtils for the real thing.)
DROP TABLE IF EXISTS task_template_variable_name;

DROP TABLE IF EXISTS task_definition;
DROP TABLE IF EXISTS task_template;

CREATE TABLE task_template
(
    id           UUID PRIMARY KEY,
    name         TEXT    NOT NULL,

    -- On the template, not the definition: a context never varies inside one, and ADR-0006 made it
    -- the overview's grouping axis.
    context      TEXT    NOT NULL,

    -- Templates are deactivated, not deleted. #35 measured the cost of the old rule: 49% of
    -- portal's recurring tasks point at a template that no longer exists.
    active       BOOLEAN NOT NULL DEFAULT TRUE,

    -- ADR-0017: the date this template began firing under its current rule. The floor and phase of
    -- a calendar enumeration, and the seed a brand-new min/max template fires from - which is where
    -- REC-003's explicit start date lives.
    active_since DATE    NOT NULL,

    version      BIGINT  NOT NULL,

    -- The trigger: a discriminator plus typed nullable columns, deliberately not one JSON blob.
    -- ADR-0005's importer and whoever checks its work need min and max to be readable in SQL.
    trigger_type                  TEXT NOT NULL,

    -- MANUAL: the wording the template author gives the anchor - "When is the workshop?" - so the
    -- run-it dialog asks a question instead of presenting a date picker.
    trigger_anchor_label          TEXT,

    -- MIN_MAX: comes round every min days, due at max. min = max means due immediately, which is
    -- what ten of the 44 real templates say.
    trigger_min_days              INTEGER,
    trigger_max_days              INTEGER,

    -- CALENDAR: which of the four rules, and the fields that rule uses. Weekdays are comma
    -- separated because a weekly rule may name several - "Tuesday and Thursday" is one template.
    trigger_calendar_rule         TEXT,
    trigger_calendar_interval     INTEGER,
    trigger_calendar_weekdays     TEXT,
    trigger_calendar_day_of_month INTEGER,
    trigger_calendar_ordinal      TEXT,

    CONSTRAINT ck_task_template_trigger_type
        CHECK (trigger_type IN ('MANUAL', 'MIN_MAX', 'CALENDAR')),

    -- The sealed interface's guarantee, restated where the data actually lives: a row cannot be a
    -- min/max trigger without an interval, and cannot be a calendar trigger without a rule.
    CONSTRAINT ck_task_template_min_max
        CHECK (trigger_type <> 'MIN_MAX'
            OR (trigger_min_days IS NOT NULL AND trigger_max_days IS NOT NULL
                AND trigger_min_days > 0 AND trigger_max_days >= trigger_min_days)),

    CONSTRAINT ck_task_template_calendar
        CHECK (trigger_type <> 'CALENDAR'
            OR (trigger_calendar_rule IN ('DAYS', 'WEEKS', 'MONTHS', 'NTH_WEEKDAY')
                AND trigger_calendar_interval IS NOT NULL AND trigger_calendar_interval > 0))
);

CREATE TABLE task_definition
(
    id                     UUID    PRIMARY KEY,
    task_template_id       UUID    NOT NULL,
    index                  INTEGER NOT NULL,

    name                   TEXT    NOT NULL,

    -- One anchor, two offsets. Both *DeviationBase columns are gone: across all 11 real definitions
    -- the base did nothing in ten and was silently wrong in the eleventh.
    start_date_offset_days INTEGER,
    due_date_offset_days   INTEGER,

    -- Non-null, defaulting to IMPORTANT: a definition cannot produce a task that must have one from
    -- a value that does not.
    importance             TEXT    NOT NULL DEFAULT 'IMPORTANT',

    description            TEXT,

    CONSTRAINT fk_task_definition_template
        FOREIGN KEY (task_template_id)
            REFERENCES task_template (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_task_definition_template
    ON task_definition (task_template_id);

-- The firing predicate asks two questions of tasks per template per tick - has it an open one, and
-- when did its most recent closed one fire - and V4 already indexed task.task_template_id for them.
