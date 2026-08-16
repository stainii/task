/**
 * Lets every pending promise settle.
 *
 * Needed wherever a test resumes a suspended `await` inside a component rather than calling into
 * it — answering the shell's confirm, pressing a verb on a toast — because what resumes then records
 * patches before it reaches its next visible step. A macrotask drains that whole chain, where
 * `whenStable` alone does not: a bare promise is not work Angular knows is pending.
 *
 * Test-only, and in `src/` for the same reason the mothers are: it is imported by specs beside the
 * code they cover, and the production bundle never reaches it.
 */
export function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
