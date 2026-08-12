/// Reading the frozen archive: portal's rows, in portal's vocabulary.
///
/// Every dry run goes against a **restored dump, never live portal**
/// ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)),
/// which is what makes [#26](https://github.com/stainii/task/issues/26)'s restore drill a
/// prerequisite of the rehearsal rather than a ceremony.
@NullMarked
package be.stijnhooft.task.backend.migration.portal;

import org.jspecify.annotations.NullMarked;
