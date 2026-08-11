-- #46 / ADR-0004 (the epoch amendment, ADR-0007): `sequence` is only monotonic within one lineage
-- of history. Restoring a backup rewinds the counter, so the server reissues numbers it has already
-- handed out and an ahead-of-server client concludes it is up to date, permanently and silently.
--
-- One integer names the lineage. It lives in the database rather than in configuration because the
-- thing that rewinds history is `psql < dump`, and the restore procedure (ADR-0008's restore.sh,
-- step four) bumps it in the same breath, after the dump has been loaded:
--
--     UPDATE sync_epoch SET epoch = epoch + 1;
--
-- A client presenting a stale epoch is answered with a resync, not a stream.
CREATE TABLE sync_epoch
(
    id    SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    epoch BIGINT NOT NULL
);

INSERT INTO sync_epoch (id, epoch)
VALUES (1, 1);
