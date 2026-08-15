import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { TaskTemplate } from '../domain/template';
import { aTemplate } from '../domain/template.mother';
import { LocalStore } from '../store/local-store';
import { SyncStatus } from './sync-status';
import { TemplateApi } from './template-api';
import { TemplateService } from './templates';

/**
 * The template half of sync, which is nothing like the task half: no outbox, no fold, no patches.
 * What it does own is the one thing the reminding list cannot survive without — **the held list
 * outliving a failed fetch**.
 */

const BINS = aTemplate({ id: 'bins', name: 'Vuilbakken' });

describe('the templates the client holds', () => {
  let service: TemplateService;
  let status: SyncStatus;
  let held: TaskTemplate[];
  let listing: () => Promise<TaskTemplate[]>;
  const api = {
    list: () => listing(),
    create: vi.fn((template: TaskTemplate) => Promise.resolve(template)),
    update: vi.fn((template: TaskTemplate) => Promise.resolve(template)),
    deactivate: vi.fn(() => Promise.resolve(BINS)),
    reactivate: vi.fn(() => Promise.resolve(BINS)),
    remove: vi.fn(() => Promise.resolve()),
    run: vi.fn(() => Promise.resolve()),
  };

  beforeEach(() => {
    held = [];
    listing = () => Promise.resolve([BINS]);
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        { provide: TemplateApi, useValue: api },
        {
          provide: LocalStore,
          useValue: {
            templates: () => Promise.resolve([...held]),
            lastSyncedAt: () => Promise.resolve(null),
            setLastSyncedAt: () => Promise.resolve(),
            replaceTemplates: (templates: TaskTemplate[]) => {
              held = [...templates];
              return Promise.resolve();
            },
          },
        },
      ],
    });
    service = TestBed.inject(TemplateService);
    status = TestBed.inject(SyncStatus);
  });

  it('stores what the server listed, and says the store changed', async () => {
    const before = status.revision();

    await service.refresh();

    expect(held.map((template) => template.id)).toEqual(['bins']);
    expect(status.revision()).toBeGreaterThan(before);
  });

  /**
   * **A failed fetch is not an empty list.** The reminding list is the surface the ✓ lives on, and
   * wiping it because a train went into a tunnel would leave the author staring at a screen saying
   * they have no chores — the same lie in the same shape as ADR-0009's silent failure, on the
   * screen ADR-0014 gave a daily job to.
   */
  it('keeps what it holds when the server cannot be reached', async () => {
    await service.refresh();
    listing = () => Promise.reject(new Error('offline'));

    await service.refresh();

    expect(held.map((template) => template.id)).toEqual(['bins']);
    expect(status.reachable()).toBe(false);
  });

  describe('whether a template can be edited at all', () => {
    /** ADR-0004: **visibly unavailable offline**, never a save that silently goes nowhere. */
    it('is no while the device is offline', () => {
      status.online.set(false);

      expect(service.writable()).toBe(false);
    });

    it('is no while the server will not answer', () => {
      status.online.set(true);
      status.unreachable();

      expect(service.writable()).toBe(false);
    });

    it('is yes when both are true', () => {
      status.online.set(true);
      status.reachable.set(true);

      expect(service.writable()).toBe(true);
    });
  });

  describe('writing', () => {
    it('creates a template the client has never sent, and re-reads the list', async () => {
      const fresh = aTemplate({ id: 'fresh' });

      await service.save(fresh, { existing: false });

      expect(api.create).toHaveBeenCalledWith(fresh);
      expect(held.map((template) => template.id)).toEqual(['bins']);
    });

    it('updates one the server already has', async () => {
      await service.save(BINS, { existing: true });

      expect(api.update).toHaveBeenCalledWith(BINS);
      expect(api.create).not.toHaveBeenCalled();
    });

    it('deactivates rather than deletes, and refreshes what is held', async () => {
      await service.deactivate('bins');

      expect(api.deactivate).toHaveBeenCalledWith('bins');
      expect(held.map((template) => template.id)).toEqual(['bins']);
    });
  });
});
