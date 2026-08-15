import { HttpClient } from '@angular/common/http';
import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

import { LocalStore } from '../store/local-store';

/**
 * Whether this browser has been granted permission to show a notification, injectable.
 *
 * A global with three states and no constructor, so a test cannot reach it any other way — the same
 * argument as `NOW`. `denied` where the API does not exist at all: nothing can be shown, which is
 * what that word means here.
 */
export const NOTIFICATION_PERMISSION = new InjectionToken<() => NotificationPermission>(
  'notification permission',
  {
    providedIn: 'root',
    factory: () => () => (typeof Notification === 'undefined' ? 'denied' : Notification.permission),
  },
);

/**
 * **One device's registration for the 07:30 push**
 * ([ADR-0012](../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md)), and the repair
 * that keeps it alive.
 *
 * ### Permission is spent on purpose
 *
 * Nothing here runs on arrival. Chrome drops an origin into quieter permissions after a dismissal
 * and recovering a denial means digging through site settings, so the prompt is close to one-shot —
 * it is raised from an explicit, gesture-initiated toggle and nowhere else.
 *
 * ### The channel repairs itself, from this end
 *
 * A Web Push subscription is not permanent: Chrome expires and rotates them, clearing browser data
 * kills them, and the server deletes any endpoint a push service answers `410 Gone` for. Untreated,
 * the failure is that notifications stop and *nothing says so*. So {@link restore} runs on every app
 * open and re-sends what this browser holds — registration is idempotent on the endpoint precisely
 * to allow that — and re-subscribes silently where the subscription has gone but permission has not.
 *
 * ### What is stored, and why anything is
 *
 * One flag: **did this device ask for push?** It is not redundant with the browser's own state.
 * Permission survives the toggle being turned off, so *permission is granted* cannot be the trigger
 * for repair — a device that deliberately went quiet would silently come back at the next launch.
 * It lives in the local store's `meta` beside the sync cursor, so one hard reset erases everything
 * this device remembers and nothing survives it in a second place.
 */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly swPush = inject(SwPush);
  private readonly store = inject(LocalStore);
  private readonly http = inject(HttpClient);
  private readonly permission = inject(NOTIFICATION_PERMISSION);

  /**
   * Whether a push could be received at all — there is a service worker, and it is running.
   *
   * Not a signal: it is settled before this class exists and never changes within a page. `/status`
   * says so in words rather than showing a toggle that could not do anything.
   */
  readonly available = this.swPush.isEnabled;

  private readonly on = signal(false);
  private readonly refused = signal(false);

  /** Whether this device is registered for the 07:30 push right now. */
  readonly enabled = this.on.asReadonly();

  /**
   * Permission was refused, so the toggle cannot be honoured from inside the app.
   *
   * The one case ADR-0012 says a banner is warranted for: everything else repairs itself silently,
   * and this is the one thing only site settings can undo.
   */
  readonly blocked = this.refused.asReadonly();

  /**
   * On every app open: re-send what this device holds, or quietly get it back.
   *
   * Never prompts. Every branch either uses permission already granted or does nothing at all.
   */
  async restore(): Promise<void> {
    if (!this.available || !(await this.store.pushWanted())) {
      return;
    }

    const held = await firstValueFrom(this.swPush.subscription);
    if (held !== null) {
      // The server may have pruned this endpoint on a `410` since the last launch. Re-registering
      // is idempotent, and it is the only thing that would ever put the row back.
      await this.register(held);
      this.on.set(true);
      this.refused.set(false);
      return;
    }

    if (this.permission() !== 'granted') {
      // Revoked in site settings. Nothing here can undo that, and pretending otherwise by raising
      // the prompt would spend a permission Chrome will not grant.
      this.on.set(false);
      this.refused.set(true);
      return;
    }

    await this.subscribe();
  }

  /** The toggle going on. Must be called from a user gesture — this is where the prompt happens. */
  async enable(): Promise<void> {
    if (!this.available) {
      return;
    }
    await this.store.setPushWanted(true);
    await this.subscribe();
  }

  /**
   * The toggle going off — **the browser first, the server second.**
   *
   * That order is what makes the failure safe. Once `unsubscribe` has run the endpoint is dead, so
   * even if the removal never reaches the server the next 07:30 push gets a `410` and prunes the row
   * itself. The reverse order would leave a device that is still subscribed if the local call
   * failed, and a toggle showing off while notifications kept arriving.
   */
  async disable(): Promise<void> {
    await this.store.setPushWanted(false);
    this.on.set(false);
    this.refused.set(false);

    const held = await firstValueFrom(this.swPush.subscription);
    if (held === null) {
      return;
    }
    const endpoint = held.endpoint;
    await this.swPush.unsubscribe().catch(() => undefined);
    await firstValueFrom(
      this.http.post('/api/push-subscriptions/removal', { endpoint }, { responseType: 'text' }),
    ).catch(() => undefined);
  }

  private async subscribe(): Promise<void> {
    try {
      const serverPublicKey = await firstValueFrom(
        this.http.get('/api/push-subscriptions/application-server-key', { responseType: 'text' }),
      );
      const subscription = await this.swPush.requestSubscription({ serverPublicKey });
      await this.register(subscription);
      this.on.set(true);
      this.refused.set(false);
    } catch {
      // Either the permission was refused, or the server could not be reached to hand out its key.
      // Both leave the device unsubscribed, and both are things the user has to see: a toggle that
      // silently springs back is the *reports success it did not have* shape ADR-0009 is about.
      this.on.set(false);
      this.refused.set(true);
      await this.store.setPushWanted(false);
    }
  }

  /**
   * `PushSubscription.toJSON()` verbatim, never re-assembled.
   *
   * `p256dh` and `auth` are opaque base64url strings, and a client that picks them apart is one
   * refactor away from swapping them — which registers cleanly and produces a device that can never
   * be decrypted to.
   */
  private register(subscription: PushSubscription): Promise<unknown> {
    return firstValueFrom(
      this.http.post('/api/push-subscriptions', subscription.toJSON(), { responseType: 'text' }),
    );
  }
}
