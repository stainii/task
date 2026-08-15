import { computed, inject, Injectable } from '@angular/core';

import { TaskTemplate } from '../domain/template';
import { LocalStore } from '../store/local-store';
import { SyncStatus } from './sync-status';
import { TemplateApi, TemplateEntry } from './template-api';

/**
 * The template half of sync, and it is nothing like the task half.
 *
 * There is no outbox, no fold and no patches here, because **templates are online-write-only**
 * (`CONTEXT.md`): a task is inert and can be merged after the fact, where a template is a rule that
 * keeps running in your absence. What this service does own is the read side — the list is fetched
 * and **held**, so the reminding list, the omnibox's template rows and ADR-0011's offline mint all
 * work with the radio off.
 */
@Injectable({ providedIn: 'root' })
export class TemplateService {
  private readonly api = inject(TemplateApi);
  private readonly store = inject(LocalStore);
  private readonly status = inject(SyncStatus);

  /**
   * Whether a template can be written at all, right now.
   *
   * ADR-0004 asks for template editing to be **visibly unavailable offline** rather than silently
   * failing, and this is the sentence the authoring screen reads. Both halves are needed for the
   * same reason the *online but not syncing* banner needs both: a train tunnel and a server that
   * will not answer are different facts, and either one makes a save impossible.
   */
  readonly writable = computed(() => this.status.online() && this.status.reachable());

  /**
   * Re-reads the whole list into the store.
   *
   * **A failed fetch leaves what is held alone.** Clearing on failure would empty the reminding
   * list the moment a train went into a tunnel — a screen saying you have no chores, which is
   * ADR-0009's silent-failure shape on the one screen ADR-0014 gave a daily job to. The stale list
   * is the honest answer: it is what the server last said.
   */
  async refresh(): Promise<void> {
    let templates: TaskTemplate[];
    try {
      templates = await this.api.list();
    } catch {
      // Reported as a fact rather than thrown: every caller is a fire-and-forget start-up path, and
      // `onlineButNotSyncing` is already the banner that says the server will not answer.
      //
      // Only the *fetch* is caught. A store failure is a different fact with its own flag
      // (`storeUnavailable`), and swallowing it as *unreachable* would blame the network for a
      // browser that has evicted the database — the wrong banner, and one that never clears.
      this.status.unreachable();
      return;
    }

    await this.store.replaceTemplates(templates);
    await this.status.succeeded();
    this.status.changed();
  }

  /** Every template this device holds, as the server last listed them. */
  templates(): Promise<TaskTemplate[]> {
    return this.store.templates();
  }

  /**
   * Saves a template, and re-reads the list.
   *
   * Re-read rather than patched into the store from the response, because the server owns three of
   * the fields it comes back with — `active`, `activeSince`, and the ids of definitions it has just
   * minted. Trusting the local copy would be a second model of a template on this device, and the
   * one thing this class exists to avoid is having two.
   */
  async save(template: TaskTemplate, options: { existing: boolean }): Promise<void> {
    await (options.existing ? this.api.update(template) : this.api.create(template));
    await this.refresh();
  }

  /** **Stop firing.** The template keeps its tasks and its history; it drops out of the list. */
  async deactivate(id: string): Promise<void> {
    await this.api.deactivate(id);
    await this.refresh();
  }

  async reactivate(id: string): Promise<void> {
    await this.api.reactivate(id);
    await this.refresh();
  }

  /** Allowed only while the template has no tasks at all — the server refuses the rest with `409`. */
  async remove(id: string): Promise<void> {
    await this.api.remove(id);
    await this.refresh();
  }

  /**
   * Runs a manual template, which creates its tasks immediately (ADR-0013).
   *
   * Server-side, unlike *"I already did this"*: this one is a deliberate act at a keyboard with the
   * anchor date being typed, so there is no offline requirement to satisfy and rendering stays in
   * one place. The tasks come back down the patch stream like any others.
   */
  run(id: string, entry: TemplateEntry): Promise<void> {
    return this.api.run(id, entry);
  }
}
