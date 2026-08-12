/// The one-shot portal importer
/// ([ADR-0005](../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)).
///
/// **Kept in the repo permanently, not deleted after cutover**: it is the only executable record of
/// how a portal row became a `task` row, and a repair discovered in month three is built from it
/// plus the frozen archive.
///
/// It reads, it maps, and it hands the result to `task` and `template` through their own inbound
/// ports — never to their tables. Nothing here writes SQL against the target schema.
@NullMarked
package be.stijnhooft.task.backend.migration;

import org.jspecify.annotations.NullMarked;
