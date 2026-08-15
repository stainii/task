import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SwPush } from '@angular/service-worker';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { LocalStore } from '../store/local-store';
import { NOTIFICATION_PERMISSION, PushService } from './push';

/**
 * **The 07:30 toggle** ([ADR-0012](../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md)).
 *
 * Two rules carry the whole feature, and both are about a channel that fails silently:
 *
 * - **Permission is a toggle, never a prompt on arrival.** Chrome treats a dismissed prompt harshly
 *   and recovering a denial means digging through site settings, so the prompt is close to one-shot
 *   and is spent on purpose.
 * - **The channel repairs itself from the client.** Chrome rotates and expires endpoints, and the
 *   server deletes what a push service calls `410 Gone`. Untreated, notifications simply stop and
 *   nothing says so — and the author concludes nothing was due for three weeks.
 *
 * `SwPush` and the permission reading are the browser boundary and are stubbed; the HTTP is
 * asserted for real, because the exact body the server is handed is what decides whether a device
 * can be decrypted to at all.
 */

const ENDPOINT = 'https://fcm.googleapis.com/wp/a-device';

const A_SUBSCRIPTION = {
  endpoint: ENDPOINT,
  toJSON: () => ({
    endpoint: ENDPOINT,
    keys: { p256dh: 'a-public-key', auth: 'an-auth-secret' },
  }),
} as unknown as PushSubscription;

describe('the push toggle', () => {
  let http: HttpTestingController;
  let subscription: BehaviorSubject<PushSubscription | null>;
  let requested: { serverPublicKey: string }[];
  let unsubscribed: number;
  let refuseSubscription: Error | null;
  let serviceWorkerEnabled: boolean;
  let permission: NotificationPermission;
  let wanted: boolean;

  beforeEach(() => {
    wanted = false;
    subscription = new BehaviorSubject<PushSubscription | null>(null);
    requested = [];
    unsubscribed = 0;
    refuseSubscription = null;
    serviceWorkerEnabled = true;
    permission = 'default';

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NOTIFICATION_PERMISSION, useValue: () => permission },
        {
          provide: LocalStore,
          useValue: {
            pushWanted: () => Promise.resolve(wanted),
            setPushWanted: (value: boolean) => {
              wanted = value;
              return Promise.resolve();
            },
          },
        },
        {
          provide: SwPush,
          useValue: {
            get isEnabled() {
              return serviceWorkerEnabled;
            },
            subscription,
            requestSubscription: (options: { serverPublicKey: string }) => {
              requested.push(options);
              if (refuseSubscription !== null) {
                return Promise.reject(refuseSubscription);
              }
              subscription.next(A_SUBSCRIPTION);
              return Promise.resolve(A_SUBSCRIPTION);
            },
            unsubscribe: () => {
              unsubscribed++;
              subscription.next(null);
              return Promise.resolve();
            },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  /**
   * The service, built on first use rather than in `beforeEach`.
   *
   * `available` is read once at construction, so a test that wants a device without a service
   * worker has to say so *before* the class exists — building it eagerly would hand every such test
   * a stale `true` and hang it on a request nobody is going to answer.
   */
  function service(): PushService {
    return TestBed.inject(PushService);
  }

  /**
   * Let every pending microtask run.
   *
   * A real macrotask rather than `Promise.resolve()`: the service awaits the store before it ever
   * touches the network, so counting `await`s here would be asserting on how many hops an
   * implementation happens to take.
   */
  function settle(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0));
  }

  /** What this device asked for, last time it was asked — the one thing the toggle persists. */
  function wantedHere(): void {
    wanted = true;
  }

  async function answerTheKey(): Promise<void> {
    await settle();
    http.expectOne('/api/push-subscriptions/application-server-key').flush('a-vapid-public-key');
  }

  async function acceptTheRegistration(): Promise<void> {
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/push-subscriptions' })
      .flush(null, { status: 204, statusText: 'No Content' });
  }

  it('is off, and asks for nothing, on a device that has never turned it on', async () => {
    await service().restore();

    expect(service().enabled()).toBe(false);
    // The prompt is close to one-shot; arriving is not the moment to spend it (ADR-0012).
    expect(requested).toEqual([]);
    http.verify();
  });

  it('subscribes with the running server key and posts what the browser handed it', async () => {
    const enabling = service().enable();
    await answerTheKey();
    await settle();

    const registration = http.expectOne({ method: 'POST', url: '/api/push-subscriptions' });
    // Exactly `PushSubscription.toJSON()`. `p256dh` and `auth` are opaque base64url strings, and a
    // client that re-assembles them is one refactor from swapping them — which registers cleanly
    // and can never be decrypted on the device.
    expect(registration.request.body).toEqual({
      endpoint: ENDPOINT,
      keys: { p256dh: 'a-public-key', auth: 'an-auth-secret' },
    });
    registration.flush(null, { status: 204, statusText: 'No Content' });
    await enabling;

    // Read from the running server rather than duplicated into the bundle, so a rotated key cannot
    // leave a client subscribing against the old one.
    expect(requested).toEqual([{ serverPublicKey: 'a-vapid-public-key' }]);
    expect(service().enabled()).toBe(true);
    expect(service().blocked()).toBe(false);
  });

  it('reports a refused permission rather than a toggle that quietly did nothing', async () => {
    refuseSubscription = new Error('Notification permission denied.');

    const enabling = service().enable();
    await answerTheKey();
    await enabling;

    expect(service().enabled()).toBe(false);
    expect(service().blocked()).toBe(true);
    http.verify();
  });

  it('re-registers on every app open, because the server prunes what a push service calls gone', async () => {
    // The repair half of ADR-0012, seen from the client. A subscription this browser still holds
    // may have been deleted server-side after a `410`, and nothing anywhere would say so.
    wantedHere();
    subscription.next(A_SUBSCRIPTION);
    permission = 'granted';

    const restoring = service().restore();
    await acceptTheRegistration();
    await restoring;

    expect(service().enabled()).toBe(true);
    // Silently: no key is fetched and no prompt is raised, because it is re-sending what it holds.
    expect(requested).toEqual([]);
  });

  it('re-subscribes silently where permission is granted but the subscription has expired', async () => {
    // Chrome expiring a subscription. Permission is already granted, so nothing is prompted and the
    // author never sees anything — which is the point of putting the repair here.
    wantedHere();
    permission = 'granted';

    const restoring = service().restore();
    await answerTheKey();
    await acceptTheRegistration();
    await restoring;

    expect(requested).toEqual([{ serverPublicKey: 'a-vapid-public-key' }]);
    expect(service().enabled()).toBe(true);
  });

  it('does not re-subscribe a device that turned the toggle off', async () => {
    // Turning it off leaves permission granted for ever, so *permission is granted* cannot be the
    // trigger for repair. Without the stored answer, off would silently become on at the next
    // launch — the one outcome nobody asked for.
    permission = 'granted';

    await service().restore();

    expect(requested).toEqual([]);
    expect(service().enabled()).toBe(false);
    http.verify();
  });

  it('says permission was revoked rather than repairing what it cannot', async () => {
    wantedHere();
    permission = 'denied';

    await service().restore();

    expect(service().enabled()).toBe(false);
    expect(service().blocked()).toBe(true);
    expect(requested).toEqual([]);
    http.verify();
  });

  it('stops the browser and tells the server, naming the endpoint', async () => {
    wantedHere();
    subscription.next(A_SUBSCRIPTION);

    const disabling = service().disable();
    await settle();
    const removal = http.expectOne({ method: 'POST', url: '/api/push-subscriptions/removal' });
    // The endpoint, because that is what the device knows about itself; our row id is never handed
    // out, so it cannot be handed back.
    expect(removal.request.body).toEqual({ endpoint: ENDPOINT });
    removal.flush(null, { status: 204, statusText: 'No Content' });
    await disabling;

    expect(unsubscribed).toBe(1);
    expect(service().enabled()).toBe(false);
  });

  it('still goes quiet when the server cannot be told', async () => {
    // The browser has unsubscribed, so the endpoint is dead and the next 07:30 push gets `410` and
    // prunes the row by itself. Leaving the toggle on because one request failed would be the one
    // outcome the user did not ask for.
    wantedHere();
    subscription.next(A_SUBSCRIPTION);

    const disabling = service().disable();
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/push-subscriptions/removal' })
      .flush('down', { status: 503, statusText: 'Service Unavailable' });
    await disabling;

    expect(unsubscribed).toBe(1);
    expect(service().enabled()).toBe(false);
  });

  it('is unavailable where there is no service worker to receive a push', async () => {
    serviceWorkerEnabled = false;

    await service().restore();
    await service().enable();

    expect(service().available).toBe(false);
    expect(service().enabled()).toBe(false);
    http.verify();
  });
});
