import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { NOW } from '../../clock';
import { BuildSkew } from '../../pwa/build-skew';
import { PushService } from '../../sync/push';
import { SyncService } from '../../sync/sync';
import { Status } from './status';

/**
 * **The boring screen** ([ADR-0014](../../../../docs/adr/0014-two-destinations-and-you-capture-by-typing.md)):
 * what is left *after* a banner has already spoken.
 *
 * It is deliberately not a destination and not a peer of the two tabs, because ADR-0009 rules that
 * health must **come to you**. Nobody opens a status page daily, which is exactly why a dashboard
 * was rejected as an alert channel — so nothing here is the detection, and everything here is what
 * you look at once something has told you to.
 *
 * FE-030's profile shrinks to a single log-out item. There is no account screen, because there is
 * one account.
 */

const NOW_AT = new Date('2026-08-14T09:12:00+02:00');

describe('the status screen', () => {
  let fixture: ComponentFixture<Status>;
  let lastSyncedAt: string | null;
  let storeUnavailable: boolean;
  let frontEndBuiltAt: string | null;
  let backEndBuiltAt: string | null;
  let pushAvailable: boolean;
  let pushEnabled: ReturnType<typeof signal<boolean>>;
  let pushProblem: ReturnType<typeof signal<'refused' | 'unreachable' | null>>;
  let pushCalls: string[];
  let loggedOut: number;

  beforeEach(() => {
    lastSyncedAt = '2026-08-14T08:12:00+02:00';
    storeUnavailable = false;
    frontEndBuiltAt = '2026-08-14T02:10:00Z';
    backEndBuiltAt = '2026-08-14T02:14:00Z';
    pushAvailable = true;
    pushEnabled = signal(false);
    pushProblem = signal<'refused' | 'unreachable' | null>(null);
    pushCalls = [];
    loggedOut = 0;

    TestBed.configureTestingModule({
      providers: [
        { provide: NOW, useValue: () => NOW_AT },
        {
          provide: SyncService,
          useValue: {
            lastSyncedAt: () => lastSyncedAt,
            storeUnavailable: () => storeUnavailable,
            logout: () => {
              loggedOut++;
              return Promise.resolve();
            },
          },
        },
        {
          provide: BuildSkew,
          useValue: {
            frontEndBuiltAt: () => frontEndBuiltAt,
            backEndBuiltAt: () => backEndBuiltAt,
          },
        },
        {
          provide: PushService,
          useValue: {
            get available() {
              return pushAvailable;
            },
            enabled: pushEnabled,
            problem: pushProblem,
            enable: () => {
              pushCalls.push('enable');
              pushEnabled.set(true);
              return Promise.resolve();
            },
            disable: () => {
              pushCalls.push('disable');
              pushEnabled.set(false);
              return Promise.resolve();
            },
            restore: () => Promise.resolve(),
          },
        },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    fixture = TestBed.createComponent(Status);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function toggle(): HTMLInputElement {
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      'input.push',
    );
    if (input === null) {
      throw new Error('There is no push toggle on screen.');
    }
    return input;
  }

  it('shows both build dates and when this device last synced', async () => {
    const screen = await render();

    const facts = [...screen.querySelectorAll('.fact')].map((row) => row.textContent?.trim());
    expect(facts).toEqual([
      'Last synced 08:12',
      'App built 14 Aug',
      // Read from the server, never from the bundle: `ngsw` serves a cached bundle, so a server
      // date compiled in here would report when this device's cache was built.
      'Server built 14 Aug',
    ]);
  });

  it('says a build date it does not know rather than leaving a gap', async () => {
    backEndBuiltAt = null;

    const screen = await render();

    expect([...screen.querySelectorAll('.fact')].at(-1)?.textContent?.trim()).toBe(
      'Server built unknown',
    );
  });

  it('turns the 07:30 push on from an explicit tap, and never before', async () => {
    const screen = await render();

    // Nothing was asked for by arriving. Chrome is harsh about a dismissed prompt and recovering a
    // denial means site settings, so the prompt is close to one-shot and is spent on purpose.
    expect(pushCalls).toEqual([]);
    expect(toggle().checked).toBe(false);

    toggle().click();
    await fixture.whenStable();

    expect(pushCalls).toEqual(['enable']);
    expect(toggle().checked).toBe(true);
    expect(screen.querySelector('.push-blocked')).toBeNull();
  });

  it('turns it off again', async () => {
    pushEnabled.set(true);
    await render();

    toggle().click();
    await fixture.whenStable();

    expect(pushCalls).toEqual(['disable']);
    expect(toggle().checked).toBe(false);
  });

  it('says permission was refused rather than springing the toggle back in silence', async () => {
    pushProblem.set('refused');
    const screen = await render();

    // The one push failure a human has to act on. Everything else repairs itself from the client,
    // and only site settings can undo this one.
    expect(screen.querySelector('.push-blocked')?.textContent).toContain('site settings');
  });

  it('does not blame the browser when the server was the one that could not be reached', async () => {
    pushProblem.set('unreachable');
    const screen = await render();

    // Sending the reader into site settings for a `503` is worse than saying nothing: it is a
    // confident answer to the wrong question, on the screen whose whole job is telling the truth.
    expect(screen.querySelector('.push-blocked')).toBeNull();
    expect(screen.querySelector('.push-unreachable')?.textContent).toContain('server');
  });

  it('says so plainly where no push can arrive at all', async () => {
    pushAvailable = false;
    const screen = await render();

    // A toggle that could not do anything is worse than a sentence: it invites the one tap that
    // spends the permission for nothing.
    expect(screen.querySelector('input.push')).toBeNull();
    expect(screen.querySelector('.push-unavailable')).not.toBeNull();
  });

  it('reports a local store that cannot be reached, because nothing works in that state', async () => {
    // This app *is* its store: a failed IndexedDB write is the one front-end failure ADR-0009 could
    // not answer with telemetry, and #56's durable ack is what stops it looking like a tick.
    storeUnavailable = true;

    const screen = await render();

    expect(screen.querySelector('.store-unavailable')).not.toBeNull();
  });

  it('logs out, and that is the whole of the account screen', async () => {
    const screen = await render();

    screen.querySelector<HTMLButtonElement>('button.log-out')?.click();
    await fixture.whenStable();

    expect(loggedOut).toBe(1);
    // FE-030 shrank to this. No profile, no preferences, no account.
    expect(screen.querySelector('.profile')).toBeNull();
  });
});
