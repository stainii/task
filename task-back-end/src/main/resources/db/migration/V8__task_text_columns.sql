-- #52 / ADR-0005: `task.name` and `task.context` become TEXT.
--
-- Found by the importer's first real dry run against the frozen archive, which failed on
-- `value too long for type character varying(255)`. One of portal's 11,855 tasks has a **286
-- character** name - a workshop task rendered from a template whose "speakers" placeholder was
-- filled in with five people's names.
--
-- (Written without the placeholder syntax itself: Flyway reads dollar-brace as its own placeholder
-- and refuses to parse the file, comment or not. V6 says the same thing, and this file walked into
-- it anyway - the warning only reaches you if you are reading the file that carries it.)
--
-- The limit was not defended anywhere. It arrived in V1 as the default shape of a string column,
-- `description` next to it is already TEXT, and #47's V6 gave `task_template.name` and
-- `task_template.context` TEXT without comment.
--
-- That inconsistency is a latent defect in its own right, independent of the migration: a template
-- name is TEXT and the task it produces is VARCHAR(255), so a template whose *rendered* name
-- crosses 255 characters throws inside the hourly firing job - in production, not at import, once
-- an hour, forever, with an ERROR line as the only trace. It is precisely the shape #49 left five
-- rows of. The archive proves the input exists, because it is the same template mechanism.
--
-- Widening only: no data is rewritten, no constraint is added, and Postgres does not rewrite the
-- table for varchar->text.
ALTER TABLE task
    ALTER COLUMN name TYPE TEXT,
    ALTER COLUMN context TYPE TEXT;
