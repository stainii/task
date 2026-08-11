/// The `template` aggregate itself: internal to the module by [ADR-0003](../../../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md).
/// Nothing outside `template` reads a template - the traffic runs the other way.
@NullMarked
package be.stijnhooft.task.backend.template.domain;

import org.jspecify.annotations.NullMarked;
