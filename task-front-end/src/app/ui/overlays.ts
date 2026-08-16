import { Injectable } from '@angular/core';

/**
 * The app's **one** Escape owner
 * ([ADR-0020](../../../../docs/adr/0020-one-overlay-layer-and-one-owner-of-escape.md)).
 *
 * Before this, `TaskPage` and `DateConfirm` each bound `(document:keydown.escape)` unconditionally,
 * and `TaskPage`'s **navigates away** — so a confirm open over the task dialog took one press to
 * cancel the confirm *and* leave the dialog. It was unreachable only because the task-page scrim
 * happens to cover the appbar, which is an accident of paint rather than a rule.
 *
 * What replaces them is a stack. An overlay says it is open and gets a closer back; `App` binds the
 * only `document:keydown.escape` in the application and asks the topmost overlay to dismiss itself.
 * *Two unconditional listeners for one key is what breaks the next time an overlay is added* — so
 * adding one now costs a registration and nothing else.
 *
 * **Removal is by identity, not by popping.** Nothing guarantees the stack unwinds top-first: a
 * route can drop a screen while something opened over it is still up.
 *
 * **Topmost means most recently opened, not highest painted**, and the two are the same only
 * because every overlay in this app is opened by the thing that paints it, over what is already
 * there. Nothing enforces it — a service cannot read the z-index of something it does not render —
 * so it is stated here rather than assumed: an overlay that registers before something it paints
 * *over* would take Escape in the wrong order.
 */
@Injectable({ providedIn: 'root' })
export class Overlays {
  private layers: readonly (() => void)[] = [];

  /**
   * Says an overlay is open, and hands back the closer.
   *
   * The closer is what a component calls from `DestroyRef.onDestroy` or an `effect` cleanup, so an
   * overlay that goes away by any route — a dismissal, a navigation, a signal turning false — stops
   * owning the key without having to remember to.
   */
  open(dismiss: () => void): () => void {
    this.layers = [...this.layers, dismiss];
    return () => {
      this.layers = this.layers.filter((layer) => layer !== dismiss);
    };
  }

  /** One press, one overlay: the topmost, or nothing at all. */
  escape(): void {
    this.layers.at(-1)?.();
  }
}
